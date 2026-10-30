package app.houselights.ui.settings

import android.content.Context
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.SourceConfig
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.FolderCounts
import app.houselights.providers.local.ItemEntity
import app.houselights.providers.local.ItemKind
import app.houselights.providers.local.LibraryDao
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.nfsFolder
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.settings.SettingsStore
import app.houselights.settings.SourceSwitcher
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.testing.testSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Réglages › Bibliothèque while the library is read: the row of showcases keeps its room until the folders, what they
 * hold and their titles held are read, so the actions under it never move as they come, and no folder says « 0 titre »
 * before it is counted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class ShowcasesReadTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val folder = TemporaryFolder()

    private val files = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val screens = ViewModelStore()
    private lateinit var db: LibraryDatabase
    private lateinit var store: SettingsStore
    private lateinit var vm: SettingsViewModel

    /** The library's folders and what they hold, each answering once let through, as a busy player's database does. */
    private class Slow(private val dao: LibraryDao) : LibraryDao by dao {
        val folders = CompletableDeferred<Unit>()
        val counts = CompletableDeferred<Unit>()

        override fun observeSources(): Flow<List<SourceEntity>> = flow {
            folders.await()
            emitAll(dao.observeSources())
        }

        override fun observeFolderCounts(token: String): Flow<List<FolderCounts>> = flow {
            counts.await()
            emitAll(dao.observeFolderCounts(token))
        }
    }

    private lateinit var slow: Slow

    @Before
    fun open() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Room answers on the main thread, as in the app (FolderRemovalTest).
        db = testDatabase { onCallingThread() }
        val films = db.dao().insertSource(nfsFolder("Films", lastScanAt = System.currentTimeMillis() - THREE_HOURS_MS))
        (1..TITLES).forEach { n ->
            db.dao().insert(
                ItemEntity(
                    kind = ItemKind.MOVIE,
                    sourceId = films,
                    title = "Film $n",
                    sortTitle = "film %02d".format(n),
                    year = 2000 + n,
                    filePath = "Film $n.mkv",
                    addedAt = 0,
                ),
            )
        }
        val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        // No scan runs: it would go to the network.
        val idle = CoroutineScope(Job().apply { cancel() })
        slow = Slow(db.dao())
        val scanner = LibraryScanner(slow, { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
        val library = LocalLibrary(context, slow, scanner, artwork) {}
        store = testSettings(folder.root, files)
        store.setSource(SourceConfig.Local, null)
        store.state.first { it.source == SourceConfig.Local }
        val factory = viewModelFactory {
            initializer {
                val sources = SourceSwitcher({ store.state.value }, store::setSource, work, signOut = { _, _ -> })
                SettingsViewModel(store, library, { FakeProvider() }, work, sources, images = null)
            }
        }
        vm = ViewModelProvider(screens, factory)[SettingsViewModel::class.java]
    }

    @After
    fun close() {
        screens.clear()
        work.cancel()
        files.cancel()
        db.close()
    }

    private fun show() {
        compose.setTvContent {
            CompositionLocalProvider(LocalSettingsViewModel provides vm) {
                val s by vm.settings.collectAsStateWithLifecycle()
                val rows = PaneRows(rememberFocusMemory(), "library")
                LazyColumn { libraryPane(s, { _, _, _ -> }, SourceDoors({}, {}, {}), rows) }
            }
        }
        compose.waitForIdle()
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Where « Analyser maintenant », the first of the actions under the showcases, stands. */
    private fun actionsTop() = compose.onNodeWithText("Analyser maintenant").getBoundsInRoot().top.value

    @Test
    fun theActionsKeepTheirPlaceAsTheShowcasesAreRead() {
        show()
        val before = actionsTop()
        slow.folders.complete(Unit)
        slow.counts.complete(Unit)
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText("Films") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before, actionsTop(), 1f)
    }

    @Test
    fun noFolderSaysItHoldsNothingBeforeItIsCounted() {
        show()
        slow.folders.complete(Unit)
        // The folders read: the pane's summary says them, beside its title.
        compose.waitUntil(TIMEOUT_MS) { shown("$TITLES titres dans 1 dossier") }
        compose.waitForIdle()
        compose.onNodeWithText("0 titre", useUnmergedTree = true).assertDoesNotExist()
        slow.counts.complete(Unit)
        compose.waitUntil(TIMEOUT_MS) { shown("$TITLES titres") }
        compose.onNodeWithText("0 titre", useUnmergedTree = true).assertDoesNotExist()
    }

    private companion object {
        const val TITLES = 13
        const val TIMEOUT_MS = 5_000L
        const val THREE_HOURS_MS = 3 * 60 * 60_000L
    }
}
