package app.houselights.ui.home

import app.houselights.core.HomeSection
import app.houselights.core.SectionType
import app.houselights.core.SourceConfig
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.ScanState
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.settings.AppSettings
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.film
import app.houselights.ui.util.UiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** What home shows, decided by its ViewModel from the rows, the scan, the folders and the source. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val rows = listOf(HomeSection(SectionType.CONTINUE, listOf(film("m1")), landscape = true))
    private val provider = FakeProvider().apply { home = rows }
    private val scan = MutableStateFlow(ScanState())

    /** The folders as the database gives them; null: not read yet. */
    private val folders = MutableStateFlow<List<SourceEntity>?>(emptyList())
    private val settings = MutableStateFlow(AppSettings(loaded = true, source = SourceConfig.Local))
    private var scans = 0

    private fun folder(id: Long, lastScanAt: Long = 0) = SourceEntity(
        id = id, name = "Films", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = "nas$id", share = "volume1",
        path = "Films", username = "", password = Secret(""), lastScanAt = lastScanAt,
    )

    /** Home as the screen holds it: [HomeViewModel.state] is read while the screen is there. */
    private fun TestScope.home(): HomeViewModel =
        HomeViewModel({ provider }, scan, folders.filterNotNull(), settings, scanAll = { scans++ }).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
        }

    private fun HomeViewModel.content() = state.value.content

    @Test
    fun theFirstLoadShowsTheHerosRoomAndEmptyFrames() = runTest {
        provider.gate("libraries")
        val vm = home()
        advanceUntilIdle()
        assertEquals(HomeContent.Loading(firstScan = false), vm.content())
    }

    @Test
    fun aScanDuringTheFirstLoadCountsAboveTheEmptyFrames() = runTest {
        provider.gate("libraries")
        scan.value = ScanState(running = true, sourceId = 1)
        val vm = home()
        assertEquals("before the screen listens", HomeContent.Loading(firstScan = true), vm.content())
        advanceUntilIdle()
        assertEquals(HomeContent.Loading(firstScan = true), vm.content())
    }

    @Test
    fun loadsTheLibrariesAndTheRows() = runTest {
        val vm = home()
        advanceUntilIdle()
        assertEquals(provider.libraries, vm.state.value.libraries)
        assertEquals(HomeContent.Rows(rows, firstScan = false), vm.content())
    }

    @Test
    fun aFolderReadForTheFirstTimeCountsAboveTheRows() = runTest {
        folders.value = listOf(folder(1, lastScanAt = 1_800_000_000_000L), folder(2))
        val vm = home()
        scan.value = ScanState(running = true, sourceId = 1)
        advanceUntilIdle()
        assertEquals("a folder read again: no counter", HomeContent.Rows(rows, firstScan = false), vm.content())
        scan.value = ScanState(running = true, sourceId = 2)
        advanceUntilIdle()
        assertEquals(HomeContent.Rows(rows, firstScan = true), vm.content())
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

    @Test
    fun aFailureSaysWhy() = runTest {
        provider.fail = { IOException("Server away") }
        val vm = home()
        advanceUntilIdle()
        assertEquals(HomeContent.Failed(UiError.OFFLINE, retrying = false), vm.content())
    }

    @Test
    fun tryingAgainWithNothingShownKeepsTheErrorAtWork() = runTest {
        provider.fail = { IOException("Server away") }
        val vm = home()
        advanceUntilIdle()

        val gate = provider.gate("libraries")
        vm.load()
        advanceUntilIdle()
        // The intertitle stays, its button at work, until the answer.
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
        assertEquals(HomeContent.Rows(rows, firstScan = false), vm.content())
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

    @Test
    fun noFolderOrNoFilmAsksForTheFolderThatHoldsThem() = runTest {
        provider.home = emptyList()
        val vm = home()
        advanceUntilIdle()
        assertEquals(HomeContent.NoFilms(hasFolders = false), vm.content())
        folders.value = listOf(folder(1, lastScanAt = 1_800_000_000_000L))
        advanceUntilIdle()
        assertEquals(HomeContent.NoFilms(hasFolders = true), vm.content())
    }

    /** Back on home after a while away, its intertitle stays while the database reads the folders again. */
    @Test
    fun comingBackAfterAWhileShowsNoBlankBeforeTheFolders() = runTest {
        provider.home = emptyList()
        // The database answers a little after it is asked, as Room does.
        val database = flow {
            delay(10)
            emitAll(folders.filterNotNull())
        }
        val vm = HomeViewModel({ provider }, scan, database, settings, scanAll = {})
        val screen = backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(HomeContent.NoFilms(hasFolders = false), vm.content())
        screen.cancel()
        advanceTimeBy(60_000)

        val seen = mutableListOf<HomeContent>()
        backgroundScope.launch { vm.state.collect { seen += it.content } }
        // Only the screen listens now: time moves by hand, past the database's answer.
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf<HomeContent>(HomeContent.NoFilms(hasFolders = false)), seen)
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
        assertEquals(listOf("libraries", "libraries", "home"), provider.calls)
    }

    /** During a first scan, home loads again every few seconds over no rows: what it shows stays still. */
    @Test
    fun anEmptyHomeLoadingAgainStaysStill() = runTest {
        provider.home = emptyList()
        val vm = home()
        advanceUntilIdle()
        provider.gate("libraries")
        vm.load()
        advanceUntilIdle()
        assertEquals(HomeContent.NoFilms(hasFolders = false), vm.content())
    }

    @Test
    fun rowsShownStayWhileTheyLoadAgain() = runTest {
        val vm = home()
        advanceUntilIdle()
        provider.gate("libraries")
        vm.load()
        advanceUntilIdle()
        assertEquals(HomeContent.Rows(rows, firstScan = false), vm.content())
    }

    @Test
    fun rowsShownStayWhenLoadingAgainFails() = runTest {
        val vm = home()
        advanceUntilIdle()
        provider.fail = { IOException("Server away") }
        vm.load()
        advanceUntilIdle()
        assertEquals(HomeContent.Rows(rows, firstScan = false), vm.content())
    }
}
