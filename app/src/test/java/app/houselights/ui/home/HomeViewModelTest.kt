package app.houselights.ui.home

import android.database.sqlite.SQLiteException
import app.houselights.core.HomeSection
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.SectionType
import app.houselights.core.SourceConfig
import app.houselights.core.UserState
import app.houselights.providers.local.Folder
import app.houselights.providers.local.HeldRow
import app.houselights.providers.local.ItemKind
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.ScanState
import app.houselights.providers.local.nfsFolder
import app.houselights.providers.local.toFolder
import app.houselights.settings.AppSettings
import app.houselights.ui.testing.FakeCatalog
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.film
import app.houselights.ui.testing.series
import app.houselights.ui.util.UiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** What home shows, decided by its ViewModel from the rows, the scan, the folders and the source. */
@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val main = MainDispatcherRule()

    private val rows = listOf(HomeSection(SectionType.CONTINUE, listOf(film("m1")), landscape = true))
    private val provider = FakeProvider().apply { home = rows }
    private val scan = MutableStateFlow(ScanState())

    /** The folders as the database gives them; null: not read yet. */
    private val folders = MutableStateFlow<List<Folder>?>(emptyList())
    private val settings = MutableStateFlow(AppSettings(loaded = true, source = SourceConfig.Local))
    private var scans = 0

    private fun folder(id: Long, lastScanAt: Long = 0) =
        nfsFolder(host = "nas$id", share = "volume1", path = "Films", id = id, lastScanAt = lastScanAt).toFolder()

    /** Home as the screen holds it: [HomeViewModel.state] is read while the screen is there. */
    private fun TestScope.home(): HomeViewModel =
        HomeViewModel({ provider }, scan, folders.filterNotNull(), settings, scanAll = { scans++ }).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
        }

    private fun HomeViewModel.content() = state.value.content

    @Test
    fun theLibrarysRowsComeAfterTheLaunch() = runTest {
        provider.catalog = FakeCatalog().apply { best = (1..6).map { film("best$it") } }
        val launchLeft = CompletableDeferred<Unit>()
        val vm = HomeViewModel(
            { provider }, scan, folders.filterNotNull(), settings, scanAll = {},
            libraryRows = LibraryRows({ provider }),
            afterLaunch = { launchLeft.await() },
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals("home's own rows first", rows, (vm.content() as HomeContent.Rows).sections)
        launchLeft.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(SectionType.CONTINUE, SectionType.BEST_RATED), (vm.content() as HomeContent.Rows).sections.map { it.type })
    }

    /** A scan's progress reloads home, its library rows kept as drawn until a change beyond it, such as the scan's end. */
    @Test
    fun aScansProgressLeavesTheLibraryRowsAsDrawn() = runTest {
        val catalog = FakeCatalog().apply { best = (1..6).map { film("best$it") } }
        provider.catalog = catalog
        var settled = 0L
        val drawnRows = LibraryRows({ provider }, { settled })
        val vm = HomeViewModel({ provider }, scan, folders.filterNotNull(), settings, scanAll = {}, libraryRows = drawnRows)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        fun best() = (vm.content() as HomeContent.Rows).sections.single { it.type == SectionType.BEST_RATED }.items
        val drawn = best()

        catalog.best = (1..6).map { film("found$it") }
        vm.onLibraryChanged()
        advanceUntilIdle()
        assertEquals(drawn, best())

        settled++
        vm.onLibraryChanged()
        advanceUntilIdle()
        assertEquals(catalog.best, best())
    }

    @Test
    fun theFirstLoadShowsNothingYet() = runTest {
        provider.gate("libraries")
        val vm = home()
        advanceUntilIdle()
        assertEquals(HomeContent.Loading, vm.content())
    }

    @Test
    fun loadsTheLibrariesAndTheRows() = runTest {
        val vm = home()
        advanceUntilIdle()
        assertEquals(provider.libraries, vm.state.value.libraries)
        assertEquals(HomeContent.Rows(rows), vm.content())
    }

    @Test
    fun aFileReadChangesNothingHomeShows() = runTest {
        scan.value = ScanState(running = true, sourceId = 1, processed = 1, total = 9)
        val vm = home()
        advanceUntilIdle()
        val before = vm.state.value
        scan.value = scan.value.copy(processed = 2, current = "Playtime (1967).mkv")
        advanceUntilIdle()
        assertSame(before, vm.state.value)
    }

    @Test
    fun aScanOverNoRowsIsItsCounter() = runTest {
        provider.home = emptyList()
        scan.value = ScanState(running = true, sourceId = 1)
        val vm = home()
        advanceUntilIdle()
        assertEquals(HomeContent.FirstScan, vm.content())
    }

    /** A failure with nothing shown says why; trying again keeps it, its button at work, until the answer. */
    @Test
    fun tryingAgainWithNothingShownKeepsTheErrorAtWork() = runTest {
        provider.fail = { IOException("Server away") }
        val vm = home()
        advanceUntilIdle()
        assertEquals(HomeContent.Failed(UiError.OFFLINE, retrying = false), vm.content())

        val gate = provider.gate("libraries")
        vm.load()
        advanceUntilIdle()
        assertEquals(HomeContent.Failed(UiError.OFFLINE, retrying = true), vm.content())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(HomeContent.Failed(UiError.OFFLINE, retrying = false), vm.content())
    }

    @Test
    fun tryingAgainThatWorksShowsTheRows() = runTest {
        provider.fail = { IOException("Server away") }
        val vm = home()
        advanceUntilIdle()
        provider.fail = { null }
        vm.load()
        advanceUntilIdle()
        assertEquals(HomeContent.Rows(rows), vm.content())
    }

    @Test
    fun aScanOverAFailureIsItsCounter() = runTest {
        provider.fail = { IOException("Server away") }
        scan.value = ScanState(running = true, sourceId = 1)
        val vm = home()
        advanceUntilIdle()
        assertEquals(HomeContent.FirstScan, vm.content())
    }

    @Test
    fun foldersNotReadYetSayNothing() = runTest {
        provider.home = emptyList()
        folders.value = null
        val vm = home()
        advanceUntilIdle()
        assertEquals(HomeContent.ReadingFolders, vm.content())
    }

    /** Folders the database cannot read are never a crash, nor taken for none; the rows still show, else the settings door. */
    @Test
    fun foldersThatCannotBeReadAreNeverNone() = runTest {
        val unreadable = flow<List<Folder>> { throw SQLiteException("disk I/O error") }
        val vm = HomeViewModel({ provider }, scan, unreadable, settings, scanAll = {})
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(HomeContent.Rows(rows), vm.content())
        provider.home = emptyList()
        vm.load()
        advanceUntilIdle()
        assertEquals(HomeContent.ReadingFolders, vm.content())
    }

    @Test
    fun aFolderWhoseScanFailedSaysWhyNotAddAFolder() = runTest {
        provider.home = emptyList()
        val failed = folder(2)
        val error = ScanError(ScanErrorKind.UNREACHABLE)
        folders.value = listOf(folder(1), failed)
        scan.value = ScanState(errors = mapOf(failed.id to error, 9L to error))
        val vm = home()
        advanceUntilIdle()
        assertEquals(HomeContent.ScanFailed(failed, error), vm.content())

        vm.rescan()
        assertEquals("trying again scans the folders", 1, scans)
    }

    /** Without a folder there is no library: Welcome takes over, and home draws nothing meanwhile, never « Aucun film ». */
    @Test
    fun withoutAFolderHomeDrawsNothing() = runTest {
        provider.home = emptyList()
        val vm = home()
        advanceUntilIdle()
        assertEquals(HomeContent.Loading, vm.content())
    }

    @Test
    fun foldersWithoutAFilmAskForTheFolderThatHoldsThem() = runTest {
        provider.home = emptyList()
        folders.value = listOf(folder(1, lastScanAt = 1_800_000_000_000L))
        val vm = home()
        advanceUntilIdle()
        assertEquals(HomeContent.NoFilms, vm.content())
    }

    /** Back on home after a while away, its intertitle stays while the database reads the folders again. */
    @Test
    fun comingBackAfterAWhileShowsNoBlankBeforeTheFolders() = runTest {
        provider.home = emptyList()
        folders.value = listOf(folder(1))
        // The database answers a little after it is asked, as Room does.
        val database = flow {
            delay(10)
            emitAll(folders.filterNotNull())
        }
        val vm = HomeViewModel({ provider }, scan, database, settings, scanAll = {})
        val screen = backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(HomeContent.NoFilms, vm.content())
        screen.cancel()
        advanceTimeBy(60_000)

        val seen = mutableListOf<HomeContent>()
        backgroundScope.launch { vm.state.collect { seen += it.content } }
        // Only the screen listens now: time moves by hand, past the database's answer.
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf<HomeContent>(HomeContent.NoFilms), seen)
    }

    @Test
    fun anEmptyServerSaysSo() = runTest {
        provider.home = emptyList()
        settings.value = AppSettings(loaded = true, source = SourceConfig.Jellyfin("http://nas:8096", "Maison", "u1", "viewer", "token"))
        folders.value = null
        val vm = home()
        advanceUntilIdle()
        assertEquals("a server has no folders to wait for", HomeContent.ServerEmpty, vm.content())
    }

    @Test
    fun aNewerLoadReplacesAnOlderOne() = runTest {
        val first = provider.gate("libraries")
        val vm = home()
        advanceUntilIdle()
        provider.gates.clear()
        vm.load()
        advanceUntilIdle()
        // The first load was cancelled while it waited: only the second asked for the rows.
        first.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("libraries", "libraries", "home"), provider.rowCalls)
    }

    /** During a first scan, home loads again every few seconds over no rows: what it shows stays still. */
    @Test
    fun anEmptyHomeLoadingAgainStaysStill() = runTest {
        provider.home = emptyList()
        folders.value = listOf(folder(1))
        val vm = home()
        advanceUntilIdle()
        provider.gate("libraries")
        vm.load()
        advanceUntilIdle()
        assertEquals(HomeContent.NoFilms, vm.content())
    }

    @Test
    fun rowsShownStayWhileTheyLoadAgain() = runTest {
        val vm = home()
        advanceUntilIdle()
        provider.gate("libraries")
        vm.load()
        advanceUntilIdle()
        assertEquals(HomeContent.Rows(rows), vm.content())
    }

    @Test
    fun rowsShownStayWhenLoadingAgainFails() = runTest {
        val vm = home()
        advanceUntilIdle()
        provider.fail = { IOException("Server away") }
        vm.load()
        advanceUntilIdle()
        assertEquals(HomeContent.Rows(rows), vm.content())
    }
    @Test
    fun theDoorsAreReadAfterTheRows() = runTest {
        provider.libraries = listOf(Library("films", LibraryKind.MOVIES, "Films"), Library("shows", LibraryKind.SHOWS, "Séries"))
        provider.walls = mapOf("films" to (1..20).map { film("f$it") }, "shows" to (1..3).map { film("s$it") })
        provider.items.putAll((1..20).associate { "f$it" to film("f$it") })
        val vm = home()
        advanceUntilIdle()
        val doors = checkNotNull(vm.state.value.doors)
        assertEquals(listOf(20, 3), doors.shelves.map { it.count })
        assertNotNull("read while a film is in progress, for when it ends", doors.proposed)
        // Read again with the rows, kept meanwhile.
        provider.gate("page 0 12")
        vm.load()
        advanceUntilIdle()
        assertSame(doors, vm.state.value.doors)
    }

    @Test
    fun withNothingInProgressAFilmNotSeenIsProposedAndKept() = runTest {
        provider.home = listOf(HomeSection(SectionType.LATEST, listOf(film("m1")), landscape = false, libraryId = "films"))
        provider.walls = mapOf("films" to (1..20).map { film("f$it") })
        provider.items.putAll((1..20).associate { "f$it" to film("f$it") })
        val vm = home()
        advanceUntilIdle()
        val unseen = checkNotNull(vm.state.value.doors?.proposed)
        vm.load()
        advanceUntilIdle()
        assertEquals("the same all session", unseen, vm.state.value.doors?.proposed)
    }

    /** With nothing in progress, the case shows what it proposes before the libraries' doors are read. */
    @Test
    fun whatTheCaseProposesComesBeforeTheShelves() = runTest {
        provider.home = listOf(HomeSection(SectionType.LATEST, listOf(film("m1")), landscape = false, libraryId = "films"))
        provider.walls = mapOf("films" to (1..20).map { film("f$it") })
        val shelves = provider.gate("page 0 12")
        val vm = home()
        advanceUntilIdle()
        val doors = checkNotNull(vm.state.value.doors)
        assertNotNull(doors.proposed)
        assertFalse("the shelves to come", doors.shelvesRead)
        shelves.complete(Unit)
        advanceUntilIdle()
        assertEquals(true, vm.state.value.doors?.shelvesRead)
        assertEquals(listOf(20), vm.state.value.doors?.shelves?.map { it.count })
    }

    /** The libraries' doors are read while what the case proposes is drawn, not after it. */
    @Test
    fun theShelvesAreReadWhileWhatTheCaseProposesIsDrawn() = runTest {
        provider.home = listOf(HomeSection(SectionType.LATEST, listOf(film("m1")), landscape = false, libraryId = "films"))
        provider.walls = mapOf("films" to (1..20).map { film("f$it") })
        val drawing = provider.gate("page 0 1")
        val vm = home()
        advanceUntilIdle()
        assertTrue("the shelves asked meanwhile: ${provider.calls}", "page 0 12" in provider.calls)
        drawing.complete(Unit)
        advanceUntilIdle()
        assertNotNull(vm.state.value.doors?.proposed)
        assertEquals(listOf(20), vm.state.value.doors?.shelves?.map { it.count })
    }

    /**
     * What the case proposes is asked first, the shelves after it, however eagerly they start (the main thread's
     * immediate dispatch): a source answering a few at a time (OkHttp's five a host) answers the case first.
     */
    @Test
    fun whatTheCaseProposesIsAskedBeforeTheShelves() = runTest(UnconfinedTestDispatcher()) {
        provider.libraries = listOf(Library("films", LibraryKind.MOVIES, "Films"), Library("shows", LibraryKind.SHOWS, "Séries"))
        provider.walls = mapOf("films" to (1..20).map { film("f$it") }, "shows" to (1..3).map { series("s$it") })
        val doors = readDoors(provider, provider.libraries, emptyList(), UnseenPick())
        assertEquals("the case's draw first: ${provider.calls}", "page 0 1", provider.calls.first())
        assertEquals(listOf(20, 3), doors.shelves.map { it.count })
    }

    /** A shows library's new episodes are counted while its door's page is read, not after it. */
    @Test
    fun aShowsLibrarysNewEpisodesAreCountedWhileItsPageIsRead() = runTest {
        provider.libraries = listOf(Library("shows", LibraryKind.SHOWS, "Séries"))
        provider.walls = mapOf("shows" to (1..3).map { series("s$it") })
        provider.newEpisodeCounts = mapOf("shows" to 2)
        val page = provider.gate("page 0 12")
        val vm = home()
        advanceUntilIdle()
        assertTrue("counted meanwhile: ${provider.calls}", "new episodes shows" in provider.calls)
        page.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(2), vm.state.value.doors?.shelves?.map { it.newEpisodes })
    }

    /** A film proposed, then watched to its end: back home, another takes its place before the shelves are read again. */
    @Test
    fun aFilmProposedThenWatchedGivesWayAtOnce() = runTest {
        provider.home = listOf(HomeSection(SectionType.LATEST, listOf(film("m1")), landscape = false, libraryId = "films"))
        provider.walls = mapOf("films" to (1..20).map { film("f$it") })
        provider.items.putAll((1..20).associate { "f$it" to film("f$it") })
        val vm = home()
        advanceUntilIdle()
        val watched = checkNotNull(vm.state.value.doors?.proposed).copy(user = UserState(played = true))
        provider.items[watched.id] = watched
        provider.walls = mapOf("films" to (1..20).map { provider.items.getValue("f$it") })
        provider.gate("page 0 12")
        vm.load()
        advanceUntilIdle()
        val proposed = checkNotNull(vm.state.value.doors?.proposed)
        assertNotEquals(watched.id, proposed.id)
    }

    @Test
    fun aDoorThatFailsLeavesTheRowsAndTheOthers() = runTest {
        provider.libraries = listOf(Library("films", LibraryKind.MOVIES, "Films"))
        provider.fail = { call -> IOException("Away").takeIf { call.startsWith("page") } }
        val vm = home()
        advanceUntilIdle()
        assertEquals(HomeContent.Rows(rows), vm.content())
        assertEquals("its count unread", listOf<Int?>(null), vm.state.value.doors?.shelves?.map { it.count })
    }

    private fun heldRow(id: Long, folder: Long) = HeldRow(
        id = id, sourceId = folder, kind = ItemKind.MOVIE, title = "Film $id", year = 2020, filePath = "Film $id.mkv",
        missingSince = 1, showTitle = null, seasonNumber = null, episodeNumber = null,
    )

    /**
     * Home says the titles held of the first folder the user has not kept, in the folders' order, with the local folders
     * alone; kept, nothing asks again. Its answers are about the folder and the rows shown.
     */
    @Test
    fun homeSaysTheFirstFolderNotKept() = runTest {
        val held = MutableStateFlow(listOf(heldRow(1, folder = 1), heldRow(2, folder = 2), heldRow(3, folder = 2)))
        folders.value = listOf(folder(1).copy(heldKept = true), folder(2))
        val answers = mutableListOf<String>()
        val vm = HomeViewModel(
            { provider }, scan, folders.filterNotNull(), settings, scanAll = {}, held = held,
            keep = { folder, shown -> answers += "keep $folder $shown" }, remove = { answers += "remove $it" },
            // In the test's own scope, which advanceUntilIdle runs, unlike its background.
            work = this,
        )
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        val note = checkNotNull(vm.state.value.held) { "no note" }
        assertEquals("the folder kept says nothing", 2L, note.folder.id)
        assertEquals(listOf(2L, 3L), note.ids)
        vm.keepHeld(note)
        vm.removeHeld(note)
        advanceUntilIdle()
        assertEquals(listOf("keep 2 [2, 3]", "remove [2, 3]"), answers)

        folders.value = listOf(folder(1).copy(heldKept = true), folder(2).copy(heldKept = true))
        advanceUntilIdle()
        assertNull("kept, nothing asks again", vm.state.value.held)

        folders.value = listOf(folder(1), folder(2))
        advanceUntilIdle()
        assertEquals("another drop asks again", 1L, vm.state.value.held?.folder?.id)
        held.value = emptyList()
        advanceUntilIdle()
        assertNull("their files back", vm.state.value.held)

        held.value = listOf(heldRow(1, folder = 1))
        settings.value = AppSettings(loaded = true, source = SourceConfig.Jellyfin("http://nas:8096", "Maison", "u1", "viewer", "token"))
        advanceUntilIdle()
        assertNull("a server's home says nothing of the folders", vm.state.value.held)
    }
}
