package app.houselights.ui.settings

import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.ViewModelStore
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.TmdbKeyCheck
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.settings.SettingsStore
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.idleLibrary
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.testing.testSettings
import app.houselights.ui.testing.waitWithLooper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The TMDB key sheet as the remote drives it: what TMDB answers to the key typed, and only its own attempt closes it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class TmdbKeySheetTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val folder = TemporaryFolder()

    /** What TMDB answers to the key being checked, given by each test. */
    private val tmdb = CompletableDeferred<TmdbKeyCheck>()
    private val files = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val screens = ViewModelStore()
    private lateinit var db: LibraryDatabase
    private lateinit var store: SettingsStore
    private lateinit var vm: SettingsViewModel
    private var shown by mutableStateOf(true)

    /** The settings' ViewModel as SettingsViewModelTest makes it, TMDB answering when told. */
    @Before
    fun open() = runBlocking {
        db = testDatabase { onCallingThread() }
        val library = idleLibrary(db.dao())
        store = testSettings(folder.root, files)
        vm = settingsModel(screens, store, library, work, checkTmdbKey = { tmdb.await() })
    }

    @After
    fun close() {
        screens.clear()
        work.cancel()
        files.cancel()
        db.close()
    }

    /** The sheet while [shown], Back or an accepted key hiding it. */
    private fun show() {
        compose.setTvContent {
            CompositionLocalProvider(LocalSettingsViewModel provides vm) {
                if (shown) TmdbKeySheet(issue = null, onDismiss = { shown = false })
            }
        }
        compose.waitForIdle()
    }

    /** The key's field, which focus opens on. */
    private fun field() = compose.onNodeWithContentDescription("Clé personnelle")

    /** OK on the field, then [key] typed on the keyboard. */
    private fun type(key: String) {
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNode(hasSetTextAction()).performTextInput(key)
    }

    /** Focus on Enregistrer, then OK: TMDB is asked. */
    private fun save() {
        compose.onNode(hasText("Enregistrer") and hasClickAction()).requestFocus()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /** TMDB answers [check], and the sheet takes it in. */
    private fun tmdbSays(check: TmdbKeyCheck) {
        tmdb.complete(check)
        compose.waitWithLooper("the sheet taking TMDB's answer in") { vm.tmdbKey?.outcome != null }
    }

    @Test
    fun anAcceptedKeyIsSavedAndClosesTheSheet() {
        show()
        field().assertIsFocused()
        type("k3y")
        save()
        compose.onNodeWithText("Vérification…").assertExists()
        assertTrue(shown)

        tmdbSays(TmdbKeyCheck.ACCEPTED)
        assertFalse(shown)
        compose.waitWithLooper("the key saved") { store.state.value.tmdbApiKey == "k3y" }
    }

    /** Nothing is saved, the sheet says why, and focus goes back to the key to correct it. */
    @Test
    fun aRefusedKeyTakesFocusBackToTheKey() {
        show()
        type("k3y")
        save()
        field().assertIsNotFocused()

        tmdbSays(TmdbKeyCheck.REFUSED)
        field().assertIsFocused()
        compose.onNodeWithText("TMDB refuse cette clé").assertExists()
        assertTrue(shown)
        assertNull(store.state.value.tmdbApiKey)
    }

    /** TMDB does not answer: the key is saved anyway, and the sheet stays to say it was not checked. */
    @Test
    fun anUncheckedKeyStaysToSaySo() {
        show()
        type("k3y")
        save()

        tmdbSays(TmdbKeyCheck.UNCHECKED)
        compose.waitWithLooper("the key saved") { store.state.value.tmdbApiKey == "k3y" }
        compose.onNodeWithText("Enregistrée sans vérification", substring = true).assertExists()
        assertTrue(shown)
    }

    /** Opened again once a key was taken, the sheet stays: an earlier attempt's outcome is not its own. */
    @Test
    fun anEarlierOutcomeNeverClosesTheSheet() {
        show()
        type("k3y")
        save()
        tmdbSays(TmdbKeyCheck.ACCEPTED)
        assertFalse(shown)

        shown = true
        compose.waitForIdle()
        assertTrue(shown)
        field().assertIsFocused()
    }

    /** Closed and opened again while TMDB checks the key: the sheet awaits that attempt, and closes once it is taken. */
    @Test
    fun aSheetOpenedDuringTheCheckClosesOnceTheKeyIsTaken() {
        show()
        type("k3y")
        save()
        shown = false
        compose.waitForIdle()
        shown = true
        compose.waitForIdle()
        compose.onNodeWithText("Vérification…").assertExists()

        tmdbSays(TmdbKeyCheck.ACCEPTED)
        assertFalse(shown)
    }
}
