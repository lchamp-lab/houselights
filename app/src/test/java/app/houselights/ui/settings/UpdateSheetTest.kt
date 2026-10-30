package app.houselights.ui.settings

import android.view.KeyEvent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.houselights.ui.design.HlMotion
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.keyInSheet
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import app.houselights.update.PermissionScreen
import app.houselights.update.UpdateFailure
import app.houselights.update.UpdateState
import app.houselights.update.UpdateStep
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, the update sheet: one action row whose words follow the step, focus staying on it; a second OK given
 * too soon does nothing; Back closes and cancels nothing; the permission, the failures and the ways by hand in words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class UpdateSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val settle = SettleMainLooper()

    private var open by mutableStateOf(true)

    private fun show(state: MutableStateFlow<UpdateState>, origin: UpdateOrigin = UpdateOrigin.About): FakeControls {
        val controls = FakeControls(state)
        compose.setTvContent {
            val s by state.collectAsState()
            if (open) UpdateSheet(s, controls, origin) { open = false }
        }
        compose.waitForIdle()
        return controls
    }

    private fun MutableStateFlow<UpdateState>.step(step: UpdateStep) {
        value = value.copy(step = step)
        compose.waitForIdle()
    }

    private fun ok() = compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)

    @Test
    fun theActionKeepsFocusAsItsWordsChange() {
        val state = MutableStateFlow(updates())
        show(state)
        compose.onNodeWithText("Mettre à jour").assertIsFocused()
        compose.onNodeWithText("6,4\u00a0Mo depuis github.com").assertExists()
        state.step(UpdateStep.Downloading(2_100_000, OFFER.size))
        compose.onNodeWithText("Téléchargement…").assertIsFocused()
        compose.onNodeWithText("2,1\u00a0Mo / 6,4\u00a0Mo").assertExists()
        state.step(UpdateStep.Verifying)
        compose.onNodeWithText("Vérification…").assertIsFocused()
        state.step(UpdateStep.Ready)
        compose.onNodeWithText("Installer").assertIsFocused()
        compose.onNodeWithText("Prête à installer").assertExists()
        state.step(UpdateStep.Installing())
        compose.onNodeWithText("Installation…").assertIsFocused()
    }

    @Test
    fun aSecondOkRightAfterTheWordsChangeDoesNothing() {
        val state = MutableStateFlow(updates(offer = null, checkedAt = null))
        val controls = show(state)
        // GitHub answers at once: the row's words change under the finger.
        controls.onAsk = { if (it == "checkNow") state.value = updates() }
        compose.onNodeWithText("Rechercher maintenant").assertIsFocused()
        compose.mainClock.autoAdvance = false
        ok()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("Mettre à jour").assertIsFocused()
        ok()
        assertEquals("The second OK, too soon, downloads nothing", listOf("checkNow"), controls.actions)
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE + FRAME_MS)
        ok()
        assertEquals(listOf("checkNow", "update"), controls.actions)
    }

    /** OK pressed while the row only said what was under way, let go once it offers more: the press does nothing. */
    @Test
    fun anOkLetGoOverOtherWordsDoesNothing() {
        val state = MutableStateFlow(updates(offer = null).copy(step = UpdateStep.Checking))
        val controls = show(state)
        compose.onNodeWithText("Rechercher maintenant").assertIsFocused()
        compose.keyInSheet(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        // GitHub answers while the key is down.
        state.value = updates()
        compose.waitForIdle()
        compose.onNodeWithText("Mettre à jour").assertIsFocused()
        compose.keyInSheet(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("Nothing downloads", emptyList<String>(), controls.actions)
    }

    /** OK let go on the row without its press, which went down before the sheet or elsewhere: nothing. */
    @Test
    fun anOkLetGoWithoutItsPressDoesNothing() {
        val controls = show(MutableStateFlow(updates()))
        compose.onNodeWithText("Mettre à jour").assertIsFocused()
        compose.keyInSheet(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("Nothing downloads", emptyList<String>(), controls.actions)
        ok()
        assertEquals("a press of its own does", listOf("update"), controls.actions)
    }

    /** On home the sheet opens by itself, maybe under an OK meant for home: its first moment, OK does nothing. */
    @Test
    fun onHomeAnOkAsTheSheetOpensDoesNothing() {
        compose.mainClock.autoAdvance = false
        val controls = show(MutableStateFlow(updates()), UpdateOrigin.Home)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Mettre à jour").assertIsFocused()
        ok()
        assertEquals("Meant for home", emptyList<String>(), controls.actions)
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE + FRAME_MS)
        ok()
        assertEquals(listOf("update"), controls.actions)
    }

    @Test
    fun okDoesNothingWhileTheRowOnlySaysWhatIsUnderWay() {
        val state = MutableStateFlow(updates().copy(step = UpdateStep.Downloading(0, OFFER.size)))
        val controls = show(state)
        ok()
        state.step(UpdateStep.Verifying)
        ok()
        state.step(UpdateStep.Installing())
        ok()
        assertEquals(emptyList<String>(), controls.actions)
    }

    @Test
    fun backClosesTheSheetAndTheDownloadGoesOn() {
        val state = MutableStateFlow(updates().copy(step = UpdateStep.Downloading(2_100_000, OFFER.size)))
        val controls = show(state)
        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Téléchargement…").assertDoesNotExist()
        assertFalse(open)
        assertEquals("Nothing cancelled", listOf("opened", "closed"), controls.asked)
    }

    @Test
    fun theDownloadIsCancelledFromTheRowUnderItAlone() {
        val state = MutableStateFlow(updates().copy(step = UpdateStep.Downloading(2_100_000, OFFER.size)))
        val controls = show(state)
        controls.onAsk = { if (it == "cancel") state.value = state.value.copy(step = UpdateStep.Idle) }
        ok()
        assertEquals(emptyList<String>(), controls.actions)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Annuler le téléchargement").assertIsFocused()
        compose.onNodeWithText("Le fichier reçu est effacé").assertExists()
        ok()
        assertEquals(listOf("cancel"), controls.actions)
        // Cancelled, the sheet is back to « Mettre à jour », focused.
        compose.onNodeWithText("Annuler le téléchargement").assertDoesNotExist()
        compose.onNodeWithText("Mettre à jour").assertIsFocused()
    }

    @Test
    fun focusOnCancelComesBackToTheActionAsTheDownloadEnds() {
        val state = MutableStateFlow(updates().copy(step = UpdateStep.Downloading(2_100_000, OFFER.size)))
        show(state)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Annuler le téléchargement").assertIsFocused()
        state.step(UpdateStep.Verifying)
        compose.onNodeWithText("Vérification…").assertIsFocused()
    }

    @Test
    fun onHomeLaterClosesItAsBackDoes() {
        val state = MutableStateFlow(updates())
        val controls = show(state, UpdateOrigin.Home)
        compose.onNodeWithText("Une nouvelle version est disponible").assertExists()
        compose.onNodeWithText("Version 0.4.1").assertExists()
        compose.onNodeWithText("Les sous-titres forcés sont de nouveau choisis.").assertExists()
        compose.onNodeWithText("Mettre à jour").assertIsFocused()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Plus tard").assertIsFocused()
        compose.onNodeWithText("Vous la retrouverez dans Réglages, À propos").assertExists()
        ok()
        assertFalse(open)
        assertEquals(emptyList<String>(), controls.actions)
    }

    @Test
    fun fromAboutItIsTheUpdatesSheetWithoutLater() {
        val state = MutableStateFlow(updates())
        show(state)
        compose.onNodeWithText("Mises à jour").assertExists()
        compose.onNodeWithText("Disponible").assertExists()
        compose.onNodeWithText("Plus tard").assertDoesNotExist()
    }

    @Test
    fun shortNotesTakeNoFocus() {
        show(MutableStateFlow(updates()))
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithText("Mettre à jour").assertIsFocused()
    }

    @Test
    fun notesThatScrollAreReadThrough() {
        val long = (1..40).joinToString("\n") { "- Une nouveauté de plus, la numéro $it, assez longue pour prendre sa ligne." }
        show(MutableStateFlow(updates(OFFER.copy(notes = mapOf("en" to long, "fr" to long)))))
        compose.onNodeWithText("Mettre à jour").assertIsFocused()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithText("Mettre à jour").assertIsNotFocused()
    }

    @Test
    fun androidsSettingsAreOpenedToAllowTheInstall() {
        val state = MutableStateFlow(updates().copy(step = UpdateStep.NeedsPermission(PermissionScreen.APP)))
        val controls = show(state)
        compose.onNodeWithText("Autorisation d’installer requise").assertExists()
        compose.onNodeWithText(
            "Dans les paramètres d’Android, autorisez Houselights à installer des applications, puis appuyez sur Retour.",
        ).assertExists()
        compose.onNodeWithText("Autoriser l’installation").assertIsFocused()
        compose.onNodeWithText("Ouvre les paramètres d’Android").assertExists()
        ok()
        assertEquals(listOf("allow"), controls.actions)
    }

    @Test
    fun withNoPermissionScreenTheWayIsSaid() {
        val state = MutableStateFlow(updates().copy(step = UpdateStep.NeedsPermission(PermissionScreen.NONE)))
        show(state)
        compose.onNodeWithText(
            "Ce lecteur n’ouvre pas le réglage d’Android qui autorise Houselights à installer des applications.",
        ).assertExists()
        val address = compose.onNodeWithText(RELEASES, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val adb = compose.onNodeWithText(ADB, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("The release page's address first, then adb's line", address.bottom <= adb.top)
        compose.onNodeWithText("Nouveautés").assertDoesNotExist()
        // A row to focus always: a newer release may be installed by hand as well.
        compose.onNodeWithText("Rechercher maintenant").assertIsFocused()
    }

    @Test
    fun comingBackStillRefusedSaysTheWayToo() {
        show(MutableStateFlow(updates().copy(step = UpdateStep.NeedsPermission(PermissionScreen.SECURITY, refused = true))))
        compose.onNodeWithText(ADB).assertExists()
        compose.onNodeWithText("Rechercher maintenant").assertIsFocused()
    }

    @Test
    fun eachFailureOffersItsRemedy() {
        val now = System.currentTimeMillis()
        val remedies = mapOf(
            UpdateFailure.OFFLINE to UpdateAction.RETRY,
            UpdateFailure.CERTIFICATE to UpdateAction.RETRY,
            UpdateFailure.BUSY to UpdateAction.RETRY,
            UpdateFailure.GITHUB_FAILED to UpdateAction.RETRY,
            UpdateFailure.UNREADABLE to UpdateAction.CHECK,
            UpdateFailure.STORAGE to UpdateAction.RETRY,
            UpdateFailure.DOWNLOAD_CUT to UpdateAction.RETRY,
            UpdateFailure.CORRUPT to UpdateAction.RETRY,
            UpdateFailure.NOT_GENUINE to UpdateAction.CHECK,
            UpdateFailure.NOT_NEWER to UpdateAction.CHECK,
            UpdateFailure.BLOCKED to UpdateAction.ALLOW,
            UpdateFailure.CONFLICT to UpdateAction.CHECK,
            UpdateFailure.INCOMPATIBLE to UpdateAction.CHECK,
            UpdateFailure.INSTALL_FAILED to UpdateAction.RETRY,
        )
        assertEquals(UpdateFailure.entries.toSet(), remedies.keys)
        remedies.forEach { (failure, action) -> assertEquals(failure.name, action, actionOf(updates().copy(failure = failure), now)) }
        val waiting = updates().copy(failure = UpdateFailure.BUSY, waitUntil = now + MINUTE_MS)
        assertEquals("OK waits while GitHub asks to", UpdateAction.WAIT, actionOf(waiting, now))
        val byHand = UpdateFailure.entries.filter { it.byHand }.toSet()
        val expected = setOf(UpdateFailure.UNREADABLE, UpdateFailure.NOT_GENUINE, UpdateFailure.CONFLICT, UpdateFailure.INCOMPATIBLE)
        assertEquals(expected, byHand)
    }

    @Test
    fun aFailureSaysWhatToDoAndItsRowDoesIt() {
        val state = MutableStateFlow(updates(offer = null).copy(failure = UpdateFailure.OFFLINE))
        val controls = show(state)
        compose.onNodeWithText("GitHub ne répond pas").assertExists()
        compose.onNodeWithText("Vérifiez la connexion Internet du lecteur, puis réessayez.").assertExists()
        compose.onNodeWithText("Version 0.4.0").assertExists()
        compose.onNodeWithText("Réessayer").assertIsFocused()
        compose.onNodeWithText("Demande de nouveau à github.com").assertExists()
        ok()
        assertEquals(listOf("retry"), controls.actions)
    }

    @Test
    fun aFailureNothingHereMendsKeepsARowAndTheWayByHand() {
        show(MutableStateFlow(updates().copy(failure = UpdateFailure.NOT_GENUINE)))
        compose.onNodeWithText("Signature refusée").assertExists()
        compose.onNodeWithText(RELEASES).assertExists()
        compose.onNodeWithText(ADB).assertDoesNotExist()
        compose.onNodeWithText("Rechercher maintenant").assertIsFocused()
    }

    @Test
    fun githubsWaitIsSaidAndOkWaitsForIt() {
        val until = System.currentTimeMillis() + 20 * MINUTE_MS + 30_000
        val state = MutableStateFlow(updates(offer = null).copy(failure = UpdateFailure.BUSY, waitUntil = until))
        val controls = show(state)
        compose.onNodeWithText("GitHub demande de patienter").assertExists()
        compose.onNodeWithText("Trop de demandes depuis votre réseau. Réessayez dans 20 min.").assertExists()
        compose.onNodeWithText("Réessayer").assertIsFocused()
        ok()
        assertEquals(emptyList<String>(), controls.actions)
    }

    @Test
    fun whatAndroidWillDoIsSaidUnderTheRow() {
        val state = MutableStateFlow(updates().copy(step = UpdateStep.Ready))
        show(state)
        compose.onNodeWithText(
            "Android vous demandera de confirmer. Votre bibliothèque, votre historique et vos réglages sont conservés.",
        ).assertExists()
        compose.onNodeWithText("Fichier vérifié, signé par le projet").assertExists()
        // Android 9: the app opens again once replaced.
        state.step(UpdateStep.Installing())
        compose.onNodeWithText("Confirmez dans la fenêtre d’Android. Houselights se fermera puis se rouvrira.").assertExists()
    }

    @Test
    fun androidsWindowLeftWithHomeOpensAgain() {
        val state = MutableStateFlow(updates().copy(step = UpdateStep.Installing(reopen = true)))
        val controls = show(state)
        compose.onNodeWithText("Installer").assertIsFocused()
        ok()
        assertEquals(listOf("reopen"), controls.actions)
    }

    @Test
    fun upToDateTheSheetSaysSoAndChecksOnOk() {
        val controls = show(MutableStateFlow(updates(offer = null)))
        compose.onNodeWithText("À jour").assertExists()
        compose.onNodeWithText("Version 0.4.0").assertExists()
        compose.onNodeWithText("Rechercher maintenant").assertIsFocused()
        compose.onNodeWithText("Demande à github.com la dernière version").assertExists()
        ok()
        assertEquals(listOf("checkNow"), controls.actions)
        assertEquals("opened", controls.asked.first())
    }

    private companion object {
        const val FRAME_MS = 32L
        const val MINUTE_MS = 60_000L
        const val RELEASES = "github.com/lchamp-lab/houselights/releases"
        const val ADB = "adb shell appops set app.houselights REQUEST_INSTALL_PACKAGES allow"
    }
}
