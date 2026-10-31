package app.houselights.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.houselights.R
import app.houselights.core.SourceConfig
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.nfsFolder
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.settings.SettingsStore
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.idleLibrary
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.testing.testSettings
import app.houselights.ui.testing.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Removing a folder asks first, and the last one says where the app goes once it is gone. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class FolderRemovalTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val folder = TemporaryFolder()

    private val files = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val screens = ViewModelStore()
    private val salon = SourceConfig.Jellyfin("https://nas:8920", "NAS du salon", "u1", "léa", "token")
    private lateinit var db: LibraryDatabase
    private lateinit var store: SettingsStore
    private lateinit var vm: SettingsViewModel

    @Before
    fun open() = runBlocking {
        // Room answers on the main thread, as in the app: from its own threads, two flows answering at once can lose a
        // showcase's change in the test's recomposer, which runs on whichever thread wakes it.
        db = testDatabase { onCallingThread() }
        val library = idleLibrary(db.dao())
        store = testSettings(folder.root, files)
        vm = settingsModel(screens, store, library, work)
    }

    @After
    fun close() {
        screens.clear()
        work.cancel()
        files.cancel()
        db.close()
    }

    /** The library pane on [folders], [kept] beside them. */
    private fun show(vararg folders: String, kept: SourceConfig.Jellyfin? = null) {
        runBlocking {
            folders.forEach { db.dao().insertSource(nfsFolder(it)) }
            store.setSource(SourceConfig.Local, kept)
            store.state.first { it.source == SourceConfig.Local && it.keptServer == kept }
        }
        compose.setTvContent {
            CompositionLocalProvider(LocalSettingsViewModel provides vm) {
                val s by vm.settings.collectAsStateWithLifecycle()
                val rows = PaneRows(rememberFocusMemory(), "library")
                LazyColumn { libraryPane(s, { _, _, _ -> }, SourceDoors({}, {}, {}), rows) }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasText(folders.last()) and hasClickAction()).assertExists()
    }

    /** OK on [folder]'s showcase, then on Retirer ce dossier in its sheet: the question it asks. */
    private fun askToRemove(folder: String) {
        compose.onNode(hasText(folder) and hasClickAction()).requestFocus()
        compose.press(Key.DirectionCenter)
        compose.onNode(hasText("Retirer ce dossier") and hasClickAction()).requestFocus()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Retirer «${NB}$folder$NB»$NB?").assertExists()
    }

    @Test
    fun aFolderAmongOthersSaysOnlyWhatLeaves() {
        show("Films", "Séries")
        askToRemove("Séries")
        compose.onNodeWithText(text(R.string.remove_folder_desc)).assertExists()
    }

    @Test
    fun theLastFolderSaysTheWelcomeScreenComesBack() {
        show("Films")
        askToRemove("Films")
        compose.onNodeWithText(
            text(R.string.remove_last_folder_desc),
        ).assertExists()
    }

    @Test
    fun theLastFolderNamesTheKeptServerThatTakesItsPlace() {
        show("Films", kept = salon)
        askToRemove("Films")
        compose.onNodeWithText(
            "Ses titres quittent la bibliothèque$NB; rien n’est supprimé sur le NAS. " +
                "C’est votre dernier dossier$NB: vous retrouverez «${NB}NAS du salon$NB», sans vous reconnecter.",
        ).assertExists()
    }

    private companion object {
        const val NB = Typography.nbsp
    }
}
