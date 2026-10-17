package app.houselights.ui.home

import app.houselights.LaunchGate
import app.houselights.LaunchGate.Reason
import app.houselights.core.HomeSection
import app.houselights.core.SectionType
import app.houselights.core.SourceConfig
import app.houselights.providers.local.ScanState
import app.houselights.providers.local.SourceEntity
import app.houselights.settings.AppSettings
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.film
import app.houselights.ui.util.UiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * A cold start: home's rows and the pictures of its first view are asked once, while the launch
 * screen holds; the gate opens on them (or at once on another start screen, or at the cap), and
 * home shows the same rows without asking the source again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeLaunchTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val rows = listOf(HomeSection(SectionType.LATEST, listOf(film("m1"), film("m2")), landscape = false, libraryId = "films"))
    private val provider = FakeProvider().apply { home = rows }
    private val settings = MutableStateFlow(AppSettings(loaded = true, source = SourceConfig.Local))
    private val said = mutableListOf<String>()

    /** The pictures home shows first, loaded as the test lets them: each launch asks once. */
    private var pictures: suspend (HomeRows) -> Unit = {}
    private val picturesAsked = mutableListOf<HomeRows>()

    /** The library's version: a scan or a title watched moves it on. */
    private var version = 0L

    private fun TestScope.gate() = LaunchGate(sinceStart = { testScheduler.currentTime }, capMs = CAP, report = { said += it })

    private fun launchOf(gate: LaunchGate) = HomeLaunch(gate, settings, { provider }, { version })

    /** A cold start: the launch begins as the first window of the process opens. */
    private fun TestScope.coldStart(gate: LaunchGate, restored: Boolean = false) = launchOf(gate).also { launch -> begin(launch, restored) }

    private fun TestScope.begin(launch: HomeLaunch, restored: Boolean = false) = launch.begin(this, restored, pictures = { asked ->
        picturesAsked += asked
        pictures(asked)
    }, work = main.dispatcher)

    /** Home as the app makes it: its first load takes what the launch asked. */
    private fun TestScope.home(launch: HomeLaunch): HomeViewModel = HomeViewModel(
        { provider },
        MutableStateFlow(ScanState()),
        MutableStateFlow(emptyList<SourceEntity>()),
        settings,
        scanAll = {},
        launched = { launch.take(settings.value.source) },
    ).also { vm -> backgroundScope.launch { vm.state.collect {} } }

    private fun HomeViewModel.content() = state.value.content

    @Test
    fun theGateOpensOnHomesRowsAndItsPictures() = runTest {
        val loaded = CompletableDeferred<Unit>()
        pictures = { loaded.await() }
        val gate = gate()
        val launch = coldStart(gate)
        val vm = home(launch)
        advanceTimeBy(100)
        assertTrue("the rows are in, the pictures not yet", gate.keepOnScreen())
        assertEquals("home waits under the launch screen", HomeContent.Loading(firstScan = false), vm.content())
        loaded.complete(Unit)
        runCurrent()
        assertEquals(Reason.READY, gate.reason)
        assertEquals(HomeContent.Rows(rows, firstScan = false), vm.content())
        assertEquals(listOf(HomeRows(provider.libraries, rows)), picturesAsked)
    }

    @Test
    fun homeShowsTheRowsTheLaunchAskedWithoutAskingAgain() = runTest {
        val launch = coldStart(gate())
        val vm = home(launch)
        advanceUntilIdle()
        assertEquals(HomeContent.Rows(rows, firstScan = false), vm.content())
        assertEquals("each asked once", listOf("home", "libraries"), provider.calls.sorted())
    }

    @Test
    fun homeLoadingAgainAsksTheSource() = runTest {
        val launch = coldStart(gate())
        val vm = home(launch)
        advanceUntilIdle()
        vm.load()
        advanceUntilIdle()
        assertEquals("the launch's rows are taken once", listOf("home", "home", "libraries", "libraries"), provider.calls.sorted())
    }

    @Test
    fun theCapOpensItAndHomeAsksForThePicturesLeft() = runTest {
        var cancelled = false
        pictures = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val gate = gate()
        val vm = home(coldStart(gate))
        advanceTimeBy(CAP - 1)
        assertNull(gate.reason)
        advanceTimeBy(2)
        assertEquals(Reason.CAP, gate.reason)
        runCurrent()
        assertEquals("the rows show at once", HomeContent.Rows(rows, firstScan = false), vm.content())
        assertTrue("home asks for its pictures itself, not twice", cancelled)
    }

    @Test
    fun rowsSlowerThanTheCapShowWhenTheyCome() = runTest {
        val answer = provider.gate("home")
        val gate = gate()
        val vm = home(coldStart(gate))
        advanceTimeBy(CAP + 1)
        assertEquals(Reason.CAP, gate.reason)
        assertEquals(HomeContent.Loading(firstScan = false), vm.content())
        answer.complete(Unit)
        advanceUntilIdle()
        assertEquals(HomeContent.Rows(rows, firstScan = false), vm.content())
        assertEquals(listOf("home", "libraries"), provider.calls.sorted())
        assertTrue("home shows already: it asks for its pictures itself, not twice", picturesAsked.isEmpty())
    }

    @Test
    fun theWelcomeScreenOpensItAtOnce() = runTest {
        settings.value = AppSettings(loaded = true, source = null)
        val gate = gate()
        coldStart(gate)
        runCurrent()
        assertEquals(Reason.START_SCREEN, gate.reason)
        assertTrue("nothing to ask", provider.calls.isEmpty())
    }

    @Test
    fun settingsThatCannotBeReadOpenItAtOnce() = runTest {
        settings.value = AppSettings(readFailed = true)
        val gate = gate()
        coldStart(gate)
        runCurrent()
        assertEquals(Reason.START_SCREEN, gate.reason)
        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun itWaitsForTheSettings() = runTest {
        settings.value = AppSettings()
        val gate = gate()
        coldStart(gate)
        advanceTimeBy(100)
        assertNull(gate.reason)
        settings.value = AppSettings(loaded = true, source = SourceConfig.Local)
        runCurrent()
        assertEquals(Reason.READY, gate.reason)
    }

    @Test
    fun aScreenTheSystemBringsBackOpensItAtOnce() = runTest {
        val gate = gate()
        coldStart(gate, restored = true)
        runCurrent()
        assertEquals(Reason.START_SCREEN, gate.reason)
        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun aFailureOpensItAndHomeSaysWhyWithoutAskingAgain() = runTest {
        provider.fail = { IOException("NAS asleep") }
        val gate = gate()
        val vm = home(coldStart(gate))
        advanceUntilIdle()
        assertEquals(Reason.READY, gate.reason)
        assertEquals(HomeContent.Failed(UiError.OFFLINE, retrying = false), vm.content())
        assertTrue("nothing to show: no pictures", picturesAsked.isEmpty())
        assertEquals("no call twice", provider.calls.distinct(), provider.calls)
    }

    @Test
    fun aSecondWindowStartsNothing() = runTest {
        val gate = gate()
        val launch = coldStart(gate)
        advanceUntilIdle()
        begin(launch)
        advanceUntilIdle()
        assertEquals(listOf("home", "libraries"), provider.calls.sorted())
        assertEquals(1, picturesAsked.size)
        assertEquals(1, said.size)
    }

    /** A warm start, or a home opened later: no launch asked for it, home asks itself. */
    @Test
    fun withoutALaunchHomeAsksItself() = runTest {
        val vm = home(launchOf(gate()))
        advanceUntilIdle()
        assertEquals(HomeContent.Rows(rows, firstScan = false), vm.content())
        assertEquals(listOf("libraries", "home"), provider.calls)
    }

    @Test
    fun rowsOfAnotherSourceAreNotShown() = runTest {
        val launch = coldStart(gate())
        advanceUntilIdle()
        assertNull(launch.take(SourceConfig.Jellyfin("http://nas:8096", "Maison", "u1", "viewer", "token")))
    }

    /**
     * The first window closed under the launch screen (Back), and the app opens again later, the
     * process kept: the rows the launch read are shown only while the library has not moved on.
     */
    @Test
    fun rowsTheLibraryMovedOnFromAreNotShown() = runTest {
        val launch = coldStart(gate())
        advanceUntilIdle()
        version++
        assertNull(launch.take(SourceConfig.Local))

        val unchanged = coldStart(gate())
        advanceUntilIdle()
        assertEquals(HomeRows(provider.libraries, rows), unchanged.take(SourceConfig.Local))
    }

    private companion object {
        const val CAP = 1_500L
    }
}
