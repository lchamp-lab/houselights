package app.houselights.ui.settings

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.SourceConfig
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.Volume
import app.houselights.providers.local.nfsFolder
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.settings.SettingsStore
import app.houselights.settings.SourceSwitcher
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.testing.testSettings
import app.houselights.ui.testing.waitWithLooper
import app.houselights.ui.util.FACT_GAP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
 * Folders as « Ajouter un dossier » leaves them in Réglages › Bibliothèque: after the first, focus on the new folder's
 * showcase once it shows, said shown once ([NewFolder]), so focus then goes where the viewer moves it; a folder of this
 * player's disks named by its disk, as the flow names it, and edited through the flow's steps, its sheet open again
 * when the edit is left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class FolderFromTheFlowTest {
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

    /** Films on the player's storage. */
    private val diskFolder = SourceEntity(
        id = DISK_FOLDER, name = "Films", kind = SourceKind.MOVIES, protocol = Protocol.LOCAL, host = Volume.INTERNAL, share = "",
        path = "Films", username = "", password = Secret(""),
    )

    @Before
    fun open() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Room answers on the main thread, as in the app (FolderRemovalTest).
        db = testDatabase { onCallingThread() }
        val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        // No scan runs: it would go to the network.
        val idle = CoroutineScope(Job().apply { cancel() })
        val scanner = LibraryScanner(db.dao(), { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
        val library = LocalLibrary(context, db.dao(), scanner, artwork) {}
        store = testSettings(folder.root, files)
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

    /** Runs the main looper, where the library's answers come back here, until [done]. */
    private fun until(done: () -> Boolean) = compose.waitWithLooper("the settings to take the flow's folder in", done = done)

    /**
     * The folder added before its showcase shows, as the flow returns before the library's list has it: focus goes to
     * its showcase once it does, then shown is said once; a folder added later leaves focus where it is.
     */
    @Test
    fun theFolderJustAddedTakesFocusOnItsShowcaseOnce() {
        runBlocking {
            db.dao().insertSource(nfsFolder("Films", id = FILMS))
            store.setSource(SourceConfig.Local, null)
            store.state.first { it.source == SourceConfig.Local }
        }
        var added by mutableStateOf<Long?>(SERIES)
        var shown = 0
        compose.setTvContent {
            CompositionLocalProvider(LocalSettingsViewModel provides vm) {
                val s by vm.settings.collectAsStateWithLifecycle()
                val rows = PaneRows(rememberFocusMemory(), "library")
                // As the app does: once shown, the folder is no longer the one just added.
                val new = added?.let { id ->
                    NewFolder(id) {
                        shown++
                        added = null
                    }
                }
                LazyColumn { libraryPane(s, { _, _, _ -> }, SourceDoors({}, {}, {}), rows, new) }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasText("Films") and hasClickAction()).requestFocus()
        assertEquals("not shown before its showcase is", 0, shown)

        runBlocking { db.dao().insertSource(nfsFolder("Séries", kind = SourceKind.SHOWS, id = SERIES)) }
        until { shown > 0 }
        compose.onNode(isFocused() and hasText("Séries")).assertExists()
        assertEquals(1, shown)

        compose.onNode(hasText("Films") and hasClickAction()).requestFocus()
        runBlocking { db.dao().insertSource(nfsFolder("Séries 2", kind = SourceKind.SHOWS)) }
        until { compose.onAllNodes(hasText("Séries 2") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(isFocused() and hasText("Films")).assertExists()
        assertEquals(1, shown)
    }

    /**
     * A folder of the player's storage: its sheet says « Disque interne » and its path, as the flow's panel does, no
     * protocol printed on it or its showcase; « Modifier le dossier » says it changes its folder on the disk, and OK
     * edits it through the flow's steps, not the NAS's form.
     */
    @Test
    fun aFolderOnThisPlayersDiskSaysItsDiskAndIsEditedThroughTheFlow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runBlocking {
            db.dao().insertSource(diskFolder)
            store.setSource(SourceConfig.Local, null)
            store.state.first { it.source == SourceConfig.Local }
        }
        val edits = mutableListOf<Pair<Long?, Boolean>>()
        compose.setTvContent {
            CompositionLocalProvider(LocalSettingsViewModel provides vm) {
                val s by vm.settings.collectAsStateWithLifecycle()
                val rows = PaneRows(rememberFocusMemory(), "library")
                LazyColumn { libraryPane(s, { id, _, onDisk -> edits += id to onDisk }, SourceDoors({}, {}, {}), rows) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(Protocol.LOCAL.name).assertDoesNotExist()
        compose.onNode(hasText("Films") and hasClickAction()).requestFocus()
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText(context.getString(R.string.disk_internal) + FACT_GAP + "/Films").assertExists()
        compose.onNodeWithText(Protocol.LOCAL.name).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.remove_folder)).assertExists()
        compose.onNode(hasText(context.getString(R.string.edit_folder)) and hasText(context.getString(R.string.edit_folder_disk_desc)))
            .requestFocus()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        until { edits.isNotEmpty() }
        assertEquals(listOf<Pair<Long?, Boolean>>(DISK_FOLDER to true), edits)
    }

    /**
     * Back from a disk folder's edit: its showcase takes focus and its sheet opens again over it, focus on « Modifier le
     * dossier », the row that opened the edit; said shown once.
     */
    @Test
    fun aFoldersEditLeftOpensItsSheetAgainOnModifier() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runBlocking {
            db.dao().insertSource(nfsFolder("Séries", kind = SourceKind.SHOWS, id = SERIES))
            db.dao().insertSource(diskFolder)
            store.setSource(SourceConfig.Local, null)
            store.state.first { it.source == SourceConfig.Local }
        }
        var left by mutableStateOf<Long?>(DISK_FOLDER)
        var shown = 0
        compose.setTvContent {
            CompositionLocalProvider(LocalSettingsViewModel provides vm) {
                val s by vm.settings.collectAsStateWithLifecycle()
                val rows = PaneRows(rememberFocusMemory(), "library")
                val back = left?.let { id ->
                    NewFolder(id, sheet = true) {
                        shown++
                        left = null
                    }
                }
                LazyColumn { libraryPane(s, { _, _, _ -> }, SourceDoors({}, {}, {}), rows, back) }
            }
        }
        until { shown > 0 }
        compose.onNodeWithText(context.getString(R.string.disk_internal) + FACT_GAP + "/Films").assertExists()
        compose.onNode(isFocused() and hasText(context.getString(R.string.edit_folder))).assertExists()
        assertEquals(1, shown)
    }

    private companion object {
        const val FILMS = 1L
        const val SERIES = 7L
        const val DISK_FOLDER = 12L
    }
}
