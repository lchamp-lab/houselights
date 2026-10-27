package app.houselights

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.SourceConfig
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.Matroska
import app.houselights.providers.local.ScanBench
import app.houselights.providers.local.toFolder
import app.houselights.settings.SettingsStore
import app.houselights.settings.SourceSwitcher
import app.houselights.ui.settings.SettingsViewModel
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.testSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.reflect.KClass

/**
 * A library is a Jellyfin server or one folder at least: without one, every screen that shows the source gives way to
 * the welcome screen, alone, as soon as the last folder is gone or the only server signed out of.
 */
@RunWith(RobolectricTestRunner::class)
class NoLibraryTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val temp = TemporaryFolder()

    private val files = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val salon = SourceConfig.Jellyfin("https://nas:8920", "NAS du salon", "u1", "léa", "token")
    private val revoked = mutableListOf<SourceConfig.Jellyfin>()
    private lateinit var bench: ScanBench
    private lateinit var library: LocalLibrary
    private lateinit var store: SettingsStore
    private lateinit var sources: SourceSwitcher
    private lateinit var nav: NavHostController
    private lateinit var settings: SettingsViewModel

    @Before
    fun open() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        bench = ScanBench(temp.newFolder("artwork"), tmdbKey = false)
        library = LocalLibrary(context, bench.dao, bench.scanner, ArtworkCache(temp.newFolder("shown"))) {}
        store = testSettings(temp.root, files)
        store.setSource(SourceConfig.Local)
        sources = SourceSwitcher({ store.state.value }, store::setSource, work, signOut = { server, _ -> revoked += server })
    }

    @After
    fun close() {
        work.cancel()
        files.cancel()
        bench.close()
    }

    /** The app's screens as AppNavigation holds them, each giving way to Welcome as it does. */
    private fun app() {
        compose.setContent {
            nav = rememberNavController()
            val s by store.state.collectAsStateWithLifecycle()
            val folders by library.anyFolder.collectAsStateWithLifecycle(null)
            val source = shownSource(s.source, folders ?: return@setContent)
            // Chosen once: another start would make navigation set a new graph, its back stack cleared.
            val start = remember { if (source != null) Route.Home else Route.Welcome }
            NavHost(nav, startDestination = start) {
                composable<Route.Welcome> {}
                composable<Route.Home> {}
                composable<Route.Settings> {
                    settings = viewModel { SettingsViewModel(store, library, { FakeProvider() }, work, sources, images = null) }
                }
            }
            WelcomeWhenLost(nav, source)
        }
        waitFor { ::nav.isInitialized && nav.currentDestination != null }
    }

    private fun openSettings() {
        compose.runOnUiThread { nav.navigate(Route.Settings) }
        waitFor { on(Route.Settings::class) }
    }

    private fun on(screen: KClass<*>) = nav.currentDestination?.hasRoute(screen) == true

    /** Welcome alone: Back on it quits. */
    private fun assertWelcomeAlone() {
        waitFor { on(Route.Welcome::class) }
        assertNull("nothing under Welcome", nav.previousBackStackEntry)
    }

    @Test
    fun theFoldersWithoutAFolderAreNoLibrary() {
        assertNull(shownSource(SourceConfig.Local, hasFolders = false))
        assertEquals(SourceConfig.Local, shownSource(SourceConfig.Local, hasFolders = true))
        assertEquals(salon, shownSource(salon, hasFolders = false))
        assertNull(shownSource(null, hasFolders = true))
    }

    /** On the folders without one, as after a library lost, the app starts on Welcome, alone. */
    @Test
    fun aLaunchWithoutAFolderStartsOnWelcome() {
        app()
        assertWelcomeAlone()
    }

    @Test
    fun theLastFolderRemovedFromTheSettingsOpensWelcome() {
        val films = runBlocking { bench.folder("Films") }
        app()
        openSettings()
        settings.removeFolder(films.toFolder(), last = true)
        assertWelcomeAlone()
        assertEquals("derived, never stored", SourceConfig.Local, store.state.value.source)
    }

    /** The removal waits for the file a scan reads in the folder; home, shown meanwhile, gives way once it is done. */
    @Test
    fun theLastFolderRemovedWhileAScanReadsItOpensWelcomeFromHome() {
        val films = runBlocking { bench.folder("Films") }
        bench.nas.put("Dune (2021).mkv", size = 80_000_000_000, bytes = Matroska.sample())
        val reading = CompletableDeferred<Unit>()
        val read = CompletableDeferred<Unit>()
        bench.nas.onRead = { _, _, _ ->
            reading.complete(Unit)
            read.await()
        }
        app()
        openSettings()
        bench.scanner.scanAll()
        waitFor { reading.isCompleted }
        settings.removeFolder(films.toFolder(), last = true)
        compose.runOnUiThread { nav.popBackStack() }
        waitFor { on(Route.Home::class) }
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        assertTrue("home stays while the scan reads", on(Route.Home::class))
        assertTrue(runBlocking { bench.dao.source(films.id) } != null)

        read.complete(Unit)
        assertWelcomeAlone()
        runBlocking {
            assertTrue(bench.dao.sources().isEmpty())
            assertTrue("nothing written in the folder removed", bench.dao.filesOf(films.id).isEmpty())
        }
    }

    @Test
    fun signingOutWithoutAFolderSignsOutThenOpensWelcome() {
        runBlocking {
            store.setSource(salon, null)
            store.state.first { it.source == salon }
        }
        app()
        openSettings()
        runBlocking {
            chooseFolders(
                keepServer = false,
                hasFolders = library::hasFolders,
                sources = sources,
                addFolder = { error("No form: the server is signed out of") },
                goHome = { error("No home without a library") },
            )
        }
        assertWelcomeAlone()
        assertEquals(SourceConfig.Local, store.state.value.source)
        assertNull("not kept", store.state.value.keptServer)
        waitFor { revoked.isNotEmpty() }
        assertEquals(listOf(salon), revoked)
    }

    /** Waits for [done], running what DataStore, Room and the scan send back to the main thread meanwhile. */
    private fun waitFor(done: () -> Boolean) {
        val until = System.currentTimeMillis() + TIMEOUT_MS
        while (!done()) {
            check(System.currentTimeMillis() < until) { "Still waiting after $TIMEOUT_MS ms" }
            compose.waitForIdle()
            Thread.sleep(POLL_MS)
        }
        compose.waitForIdle()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L

        /** Long enough for a removal that did not wait to be done. */
        const val SETTLE_MS = 300L
    }
}
