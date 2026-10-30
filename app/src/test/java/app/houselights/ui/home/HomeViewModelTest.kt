package app.houselights.ui.home

import android.database.sqlite.SQLiteException
import app.houselights.core.HomeSection
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.SectionType
import app.houselights.core.SourceConfig
import app.houselights.core.UserState
import app.houselights.providers.local.FirstTitleRow
import app.houselights.providers.local.Folder
import app.houselights.providers.local.HeldRow
import app.houselights.providers.local.ItemKind
import app.houselights.providers.local.LocalProvider
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.ScanState
import app.houselights.providers.local.TmdbClient.Companion.ImageType
import app.houselights.providers.local.nfsFolder
import app.houselights.providers.local.toFolder
import app.houselights.settings.AppSettings
import app.houselights.ui.design.FirstScanDefaults
import app.houselights.ui.library.NotSaved
import app.houselights.ui.library.favoredAs
import app.houselights.ui.library.markedAs
import app.houselights.ui.testing.FakeCatalog
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.film
import app.houselights.ui.testing.series
import app.houselights.ui.util.UiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
            { provider },
            scan,
            folders.filterNotNull(),
            settings,
            scanAll = {},
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

    /** The library's first pass on folder 1, its files counted, a file read of 412; [finishing] once every folder's are. */
    private fun firstPass(finishing: Boolean = false) =
        ScanState(running = true, sourceId = 1, processed = 1, total = 412, homeFilling = true, firstPass = true, finishing = finishing)

    /** Home as the screen holds it, « Aller à l'accueil » kept by [left], as the app keeps it with the scan. */
    private fun TestScope.home(left: FirstScanLeft): HomeViewModel =
        HomeViewModel({ provider }, scan, folders.filterNotNull(), settings, scanAll = {}, left = left).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
        }

    /** Home's rows as a first pass leaves them once titles are found: the latest films, whose door reads its shelf. */
    private fun titlesFound() {
        provider.home = listOf(HomeSection(SectionType.LATEST, listOf(film("m1")), landscape = false, libraryId = "films"))
        provider.walls = mapOf("films" to (1..20).map { film("f$it") })
    }

    /**
     * Over the library's first pass, the first scan holds home until every folder's files are read, though home's row
     * fills meanwhile: home reads nothing behind it, its rows, doors and posters asked nothing as the library changes,
     * then reads once and cuts to the grid with its shelves; it then follows the scan again.
     */
    @Test
    fun theFirstScanHoldsHomeForTheWholePassReadingNothingBehindIt() = runTest {
        titlesFound()
        scan.value = firstPass()
        val vm = home()
        advanceUntilIdle()
        assertEquals(HomeContent.FirstScan, vm.content())
        assertTrue("the first pass runs", vm.state.value.firstPass)

        scan.value = scan.value.copy(processed = 300, homeFilling = false)
        vm.onLibraryChanged()
        advanceUntilIdle()
        assertEquals("home's row full, the pass goes on", HomeContent.FirstScan, vm.content())
        assertEquals("nothing read behind it", emptyList<String>(), provider.calls)

        val shelves = provider.gate("page 0 12")
        scan.value = scan.value.copy(processed = 412, finishing = true)
        advanceUntilIdle()
        assertEquals("until the shelves are read", HomeContent.FirstScan, vm.content())
        shelves.complete(Unit)
        advanceUntilIdle()
        assertEquals(HomeContent.Rows(provider.home), vm.content())
        assertEquals(true, vm.state.value.doors?.shelvesRead)
        assertEquals("read once, at the cut", listOf("libraries", "home"), provider.rowCalls)

        vm.onLibraryChanged()
        advanceUntilIdle()
        assertEquals("after the cut, home follows the scan", listOf("libraries", "home", "libraries", "home"), provider.rowCalls)
    }

    /**
     * « Aller à l'accueil » cuts to the grid once home's one read has its shelves, the scan going on; meanwhile the first
     * scan stays and a second press asks nothing more.
     */
    @Test
    fun goingHomeCutsOnceItsShelvesAreReadASecondPressAskingNothing() = runTest {
        titlesFound()
        scan.value = firstPass()
        val vm = home()
        advanceUntilIdle()
        val shelves = provider.gate("page 0 12")
        vm.goHome()
        advanceUntilIdle()
        assertEquals(HomeContent.FirstScan, vm.content())
        vm.goHome()
        advanceUntilIdle()
        shelves.complete(Unit)
        advanceUntilIdle()
        assertEquals(HomeContent.Rows(provider.home), vm.content())
        assertEquals("one read", listOf("libraries", "home"), provider.rowCalls)
        assertTrue("the scan goes on", vm.state.value.firstPass)
    }

    /**
     * « Aller à l'accueil » is kept with the pass while the app runs, not with the screen: quitting and coming back, home
     * shows its grid; once the pass ends it is forgotten, and another first pass holds home again.
     */
    @Test
    fun goingHomeIsKeptAfterLeavingUntilThePassEnds() = runTest {
        val left = FirstScanLeft(backgroundScope, scan)
        titlesFound()
        scan.value = firstPass()
        val first = home(left)
        advanceUntilIdle()
        first.goHome()
        advanceUntilIdle()
        assertEquals(HomeContent.Rows(provider.home), first.content())

        // Back twice quits; the app, back, makes a new home, the scan having waited.
        val again = home(left)
        advanceUntilIdle()
        assertEquals("the grid, not the first scan again", HomeContent.Rows(provider.home), again.content())

        scan.value = ScanState()
        advanceUntilIdle()
        assertFalse("forgotten with the pass", left.left.value)
        scan.value = firstPass()
        advanceUntilIdle()
        assertEquals(HomeContent.FirstScan, again.content())
    }

    /** A pass found past its files when home comes shows the grid at once: the first scan only holds the files' reading. */
    @Test
    fun aPassPastItsFilesShowsTheGrid() = runTest {
        titlesFound()
        scan.value = firstPass(finishing = true)
        val vm = home()
        advanceUntilIdle()
        assertEquals(HomeContent.Rows(provider.home), vm.content())
    }

    /**
     * The first scan shows the folder the pass reads, the first while it reaches none, and the titles found with their
     * posters as the library gives them and their count, read as soon as it holds home and while it does, nothing
     * showing it yet or Réglages over it, so its wall comes back with them; no longer once it gave way with nothing
     * showing it, and forgotten then, so another first pass's wall waits for its own titles.
     */
    @Test
    fun theFirstScanShowsTheFolderReadAndTheTitlesFound() = runTest {
        folders.value = listOf(folder(1), folder(2))
        val found = listOf(FirstTitleRow(1, "Dune", 2021, "/dune.jpg"), FirstTitleRow(2, "Heat", 1995, null))
        val asked = mutableListOf<Int>()
        var reading = 0
        scan.value = firstPass().copy(sourceId = null)
        val vm = HomeViewModel(
            { provider },
            scan,
            folders.filterNotNull(),
            settings,
            scanAll = {},
            firstTitles = { limit ->
                asked += limit
                flow {
                    reading++
                    try {
                        emit(found)
                        awaitCancellation()
                    } finally {
                        reading--
                    }
                }
            },
            firstTitleCount = flowOf(214),
        )
        advanceUntilIdle()
        assertEquals(listOf(FirstScanDefaults.FRAMES), asked)
        assertEquals("read as it holds home, nothing showing it", 1, reading)
        val shows = vm.firstScan.value
        assertEquals("the first while the pass reaches none", 1L, shows.folder?.id)
        assertEquals(listOf("Dune", "Heat"), shows.titles.orEmpty().map { it.title })
        assertEquals(listOf(LocalProvider.image("/dune.jpg", ImageType.POSTER), null), shows.titles.orEmpty().map { it.poster })
        assertEquals(214, shows.found)
        scan.value = scan.value.copy(sourceId = 2)
        advanceUntilIdle()
        assertEquals(2L, vm.firstScan.value.folder?.id)

        scan.value = ScanState()
        advanceUntilIdle()
        assertEquals("no longer read once it gave way, nothing showing it", 0, reading)
        assertNull("forgotten with it, unread again", vm.firstScan.value.titles)

        scan.value = firstPass()
        advanceUntilIdle()
        assertEquals("read again for another first pass", 1, reading)
        assertEquals(listOf("Dune", "Heat"), vm.firstScan.value.titles.orEmpty().map { it.title })
    }

    /**
     * In « À regarder », a read of the rows begun before a title is marked from its card, that lands after its save with
     * the rows as they stood, never brings it back to Reprendre; a read begun after its save shows the rows as the source
     * then says them, the title in progress again as it was marked again elsewhere.
     */
    @Test
    fun aReadBegunBeforeAMarkNeverBringsItBackToReprendre() = runTest {
        val heat = film("heat").copy(user = UserState(positionMs = 60_000))
        val drive = film("drive").copy(user = UserState(positionMs = 60_000))
        provider.home = listOf(
            HomeSection(SectionType.CONTINUE, listOf(heat, drive), landscape = true),
            HomeSection(SectionType.LATEST, listOf(heat), landscape = false),
        )
        val vm = HomeViewModel({ provider }, scan, folders.filterNotNull(), settings, scanAll = {}, work = this, events = FakeEvents())
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        fun row(type: SectionType) = (vm.content() as HomeContent.Rows).sections.single { it.type == type }.items

        val reread = provider.gate("home")
        vm.onLibraryChanged()
        runCurrent()
        vm.mark(heat)
        advanceUntilIdle()
        assertTrue("played heat true" in provider.calls)

        reread.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("drive"), row(SectionType.CONTINUE).map { it.id })
        assertTrue("marked where it stays", row(SectionType.LATEST).single().user.played)

        vm.onLibraryChanged()
        advanceUntilIdle()
        assertEquals(listOf("heat", "drive"), row(SectionType.CONTINUE).map { it.id })
    }

    /**
     * In « À regarder », a title of Reprendre marked then made a favourite from Nouveautés, neither saved, the rows not
     * read again either, offline (#118): it is back in Reprendre at its place, as the source has it, whichever fails
     * first.
     */
    @Test
    fun aTitleOfReprendreChangedTwiceNotSavedIsBackAtItsPlaceOffline() = runTest {
        listOf(true, false).forEach { markFailsFirst ->
            val heat = film("heat").copy(user = UserState(positionMs = 60_000))
            val drive = film("drive").copy(user = UserState(positionMs = 60_000))
            provider.home = listOf(
                HomeSection(SectionType.CONTINUE, listOf(heat, drive), landscape = true),
                HomeSection(SectionType.LATEST, listOf(heat), landscape = false),
            )
            provider.fail = { null }
            val vm = HomeViewModel({ provider }, scan, folders.filterNotNull(), settings, scanAll = {}, work = this, events = FakeEvents())
            val collecting = backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()
            fun row(type: SectionType) = (vm.content() as HomeContent.Rows).sections.single { it.type == type }.items

            provider.fail = { if (it.startsWith("played") || it.startsWith("favorite") || it == "home") IOException("NAS away") else null }
            val marking = provider.gate("played heat true")
            val favouring = provider.gate("favorite heat true")
            vm.mark(row(SectionType.LATEST).single())
            runCurrent()
            vm.favorite(row(SectionType.LATEST).single())
            runCurrent()
            assertEquals(listOf("drive"), row(SectionType.CONTINUE).map { it.id })

            (if (markFailsFirst) marking else favouring).complete(Unit)
            advanceUntilIdle()
            (if (markFailsFirst) favouring else marking).complete(Unit)
            advanceUntilIdle()
            assertEquals("$markFailsFirst", listOf(heat, drive), row(SectionType.CONTINUE))
            assertEquals("$markFailsFirst", heat, row(SectionType.LATEST).single())
            collecting.cancel()
        }
    }

    /**
     * In « À regarder », two changes of one title from its card, neither saved, the rows not read again either, show it
     * as the source has it whichever fails first, the one still saving showing meanwhile: marked then made a favourite,
     * or marked then unmarked; a mark not saved leaves a favourite saved showing.
     */
    @Test
    fun twoChangesNotSavedLeaveTheTitleAsTheSourceHasIt() = runTest {
        listOf(true, false).forEach { markFailsFirst ->
            val heat = film("heat")
            provider.home = listOf(HomeSection(SectionType.LATEST, listOf(heat, film("drive")), landscape = false))
            provider.fail = { null }
            val vm = HomeViewModel({ provider }, scan, folders.filterNotNull(), settings, scanAll = {}, work = this, events = FakeEvents())
            val collecting = backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()
            fun shown() = (vm.content() as HomeContent.Rows).sections.single().items.first()
            fun twice(first: String, second: String, both: MediaItem, change: () -> Unit) {
                val asked = provider.gate(first) to provider.gate(second)
                vm.mark(shown())
                runCurrent()
                change()
                runCurrent()
                assertEquals(both, shown())
                (if (markFailsFirst) asked.first else asked.second).complete(Unit)
                advanceUntilIdle()
            }
            fun then(first: String, second: String) {
                provider.gates.getValue(if (markFailsFirst) second else first).complete(Unit)
                advanceUntilIdle()
            }

            provider.fail = { if (it.startsWith("played") || it.startsWith("favorite") || it == "home") IOException("NAS away") else null }
            twice("played heat true", "favorite heat true", heat.markedAs(true).favoredAs(true)) { vm.favorite(shown()) }
            val other = if (markFailsFirst) heat.favoredAs(true) else heat.markedAs(true)
            assertEquals("$markFailsFirst: the other still saving shows", other, shown())
            then("played heat true", "favorite heat true")
            assertEquals("$markFailsFirst", heat, shown())
            assertEquals(NotSaved("heat", UiError.OFFLINE), vm.notSaved.value)

            twice("played heat true", "played heat false", heat.markedAs(true).markedAs(false)) { vm.mark(shown()) }
            assertEquals("$markFailsFirst", !markFailsFirst, shown().user.played)
            then("played heat true", "played heat false")
            assertEquals("$markFailsFirst", heat, shown())

            provider.fail = { if (it.startsWith("played") || it == "home") IOException("NAS away") else null }
            twice("played heat true", "favorite heat true", heat.markedAs(true).favoredAs(true)) { vm.favorite(shown()) }
            then("played heat true", "favorite heat true")
            assertEquals("$markFailsFirst", heat.favoredAs(true), shown())
            collecting.cancel()
        }
    }
}
