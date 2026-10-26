package app.houselights.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import app.houselights.update.PermissionScreen
import app.houselights.update.UpdateFailure
import app.houselights.update.UpdateState
import app.houselights.update.UpdateStep
import app.houselights.update.Updated
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Réglages › À propos, « Mises à jour »: the state row's title stays while its words follow the update, OK opens the
 * sheet focused on its action, and the two settings are saved as the others are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class UpdateRowTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var settings: SettingsFixture

    @Before
    fun open() {
        settings = SettingsFixture(folder.root)
    }

    @After
    fun close() = settings.close()

    private val now = System.currentTimeMillis()

    private fun show(state: MutableStateFlow<UpdateState>): FakeControls {
        val controls = FakeControls(state)
        compose.setTvContent {
            val memory = rememberFocusMemory()
            val rows = remember { PaneRows(memory, Pane.About.name) }
            CompositionLocalProvider(LocalSettingsViewModel provides settings.vm) {
                LazyColumn { item { UpdatesGroup(controls.ui, rows) } }
            }
        }
        compose.waitForIdle()
        return controls
    }

    /** The row says [description] and [value], under its title, which never changes. */
    private fun says(state: MutableStateFlow<UpdateState>, to: UpdateState, description: String, value: String?) {
        state.value = to
        compose.waitForIdle()
        compose.onNodeWithText("Houselights 0.4.0").assertExists()
        // A time ago as Android writes it ("il y a 2 h"), the space before its unit its own.
        compose.onNodeWithText(description, substring = true).assertExists()
        if (value != null) compose.onNodeWithText(value).assertExists()
    }

    @Test
    fun theRowSaysWhereTheUpdateStandsUnderATitleThatStays() {
        val state = MutableStateFlow(updates(offer = null, checkedAt = now - 2 * HOUR_MS))
        show(state)
        says(state, state.value, "Vérifié il y a 2", "À jour")
        says(state, updates(offer = null, checkedAt = null), "Jamais vérifié", null)
        says(state, updates(offer = null, checkedAt = null).copy(automatic = false), "Recherche automatique désactivée", null)
        says(state, updates(offer = null).copy(step = UpdateStep.Checking), "Vérification…", null)
        says(state, updates(), "Version 0.4.1 disponible", "6,4 Mo")
        val downloading = updates().copy(step = UpdateStep.Downloading(2_100_000, OFFER.size))
        says(state, downloading, "Téléchargement de la 0.4.1…", "2,1 Mo / 6,4 Mo")
        says(state, updates().copy(step = UpdateStep.Verifying), "Vérification de la 0.4.1…", null)
        says(state, updates().copy(step = UpdateStep.Ready), "Version 0.4.1 prête à installer", null)
        val allowing = updates().copy(step = UpdateStep.NeedsPermission(PermissionScreen.APP))
        says(state, allowing, "Android doit autoriser l’installation", "Requise")
        says(state, updates().copy(step = UpdateStep.Installing()), "Installation de la 0.4.1…", null)
        says(state, updates().copy(failure = UpdateFailure.OFFLINE), "GitHub ne répond pas", "Échec")
        val waiting = updates(offer = null).copy(failure = UpdateFailure.BUSY, waitUntil = now + 20 * MINUTE_MS + 30_000)
        says(state, waiting, "GitHub demande de patienter", "Dans 20 min")
        val updated = updates(offer = null).copy(updated = Updated("0.4.0", installed = true, at = now - 3 * MINUTE_MS))
        says(state, updated, "Installée il y a 3", "À jour")
    }

    /** About in a build that does not update itself (debug, bench, CI's, F-Droid's): no « Mises à jour », nor its rows. */
    @Test
    fun withoutTheUpdaterAboutShowsNoUpdates() {
        val state = MutableStateFlow(updates().copy(on = false))
        val controls = FakeControls(state)
        compose.setTvContent {
            val memory = rememberFocusMemory()
            val rows = remember { PaneRows(memory, Pane.About.name) }
            // As About lays out its group, from what the screen hands it.
            val ui = aboutUpdates(state, controls)
            CompositionLocalProvider(LocalSettingsViewModel provides settings.vm) {
                LazyColumn { if (ui != null) item { UpdatesGroup(ui, rows) } }
            }
        }
        compose.onNodeWithText("Mises à jour").assertDoesNotExist()
        compose.onNodeWithText("Recherche automatique").assertDoesNotExist()
        state.value = updates()
        compose.waitForIdle()
        compose.onNodeWithText("Mises à jour").assertExists()
        compose.onNodeWithText("Houselights 0.4.0").assertExists()
    }

    @Test
    fun anOldAnswerIsNotUpToDate() {
        show(MutableStateFlow(updates(offer = null, checkedAt = now - 6 * DAY_MS)))
        compose.onNodeWithText("Vérifié il y a 6", substring = true).assertExists()
        compose.onNodeWithText("À jour").assertDoesNotExist()
    }

    @Test
    fun okOpensTheSheetWithFocusOnItsAction() {
        val controls = show(MutableStateFlow(updates()))
        compose.onNodeWithText("Houselights 0.4.0").requestFocus()
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText("Mettre à jour").assertIsFocused()
        assertEquals(listOf("opened"), controls.asked)
        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Mettre à jour").assertDoesNotExist()
        compose.onNodeWithText("Houselights 0.4.0").assertIsFocused()
    }

    @Test
    fun theAutomaticCheckIsTurnedOffAsAnySettingIs() {
        show(MutableStateFlow(updates()))
        compose.onNodeWithText("Auprès de github.com, sans compte").assertExists()
        compose.onNodeWithText("Recherche automatique").requestFocus()
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText("Vous chercherez dans Mises à jour").assertExists()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil { !settings.store.state.value.updateCheck }
        assertFalse(settings.store.state.value.updateCheck)
    }

    @Test
    fun theBetasAreChosenAsAnySettingIs() {
        show(MutableStateFlow(updates()))
        compose.onNodeWithText("Avec ou sans les bêta").assertExists()
        compose.onNodeWithText("Versions proposées").requestFocus()
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText("Plus tôt, avec leurs défauts").assertExists()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil { settings.store.state.value.updatePrereleases == true }
        assertEquals(true, settings.store.state.value.updatePrereleases)
    }

    /** Waits for [done], running what comes back to the main thread from DataStore's meanwhile, unseen by the test. */
    private fun waitUntil(done: () -> Boolean) {
        val until = System.currentTimeMillis() + WAIT_MS
        while (!done()) {
            check(System.currentTimeMillis() < until) { "Still waiting after $WAIT_MS ms" }
            compose.waitForIdle()
            Thread.sleep(POLL_MS)
        }
        compose.waitForIdle()
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
        const val DAY_MS = 24 * HOUR_MS
        const val WAIT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
