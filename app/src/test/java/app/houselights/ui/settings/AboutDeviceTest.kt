package app.houselights.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import app.houselights.R
import app.houselights.player.PlayerFacts
import app.houselights.player.PlayerStatus
import app.houselights.player.ZidooPlayer.Decision
import app.houselights.ui.design.HlMotion
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.testing.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** About states the device, its player as playback decides and its control API; one state per test, as first composed. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class AboutDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val settle = SettleMainLooper()

    /** The player as playback decides, for the row under the state. */
    private var player by mutableStateOf<PlayerStatus>(PlayerStatus.NotDetected)

    private fun show(player: PlayerStatus, api: Boolean? = true) =
        compose.setTvContent { Column { DeviceFacts("ZIDOO Z9X Pro", player, api) } }

    @Test
    fun aTrustedPlayerIsDetected() {
        show(PlayerStatus.Detected("com.zidoo.player"))
        compose.onNodeWithText("ZIDOO Z9X Pro").assertExists()
        compose.onNodeWithText("Détecté").assertExists()
        compose.onNodeWithText("En ligne").assertExists()
        compose.onNodeWithText("com.zidoo.player").assertDoesNotExist()
    }

    @Test
    fun anApiBeingAskedSaysSo() {
        show(PlayerStatus.NotDetected, api = null)
        compose.onNodeWithText("Vérification…").assertExists()
    }

    @Test
    fun elsewhereThePlayerIsNotRecognisedAndWhyIsSaid() {
        show(PlayerStatus.Unrecognized("com.android.gallery3d", Decision.NOT_A_ZIDOO))
        compose.onNodeWithText("Détecté, non reconnu").assertExists()
        compose.onNodeWithText(text(R.string.player_not_a_zidoo), substring = true).assertExists()
        compose.onNodeWithText("com.android.gallery3d", substring = true).assertExists()
    }

    @Test
    fun aPlayerThatChangedIsNotRecognised() {
        show(PlayerStatus.Unrecognized("com.zidoo.player", Decision.CHANGED))
        compose.onNodeWithText("Détecté, non reconnu").assertExists()
        compose.onNodeWithText(text(R.string.player_changed_since), substring = true).assertExists()
    }

    @Test
    fun noPlayerIsNotDetected() {
        show(PlayerStatus.NotDetected)
        compose.onNodeWithText("Non détecté").assertExists()
        compose.onNodeWithText("com.", substring = true).assertDoesNotExist()
    }

    /**
     * While playback refuses the player as changed, and then only, a row under the state asks whether to trust it (#58),
     * its name, version and day under it; OK on it opens the sheet and trusts nothing. No other refusal has it.
     */
    @Test
    fun onlyAChangedPlayerHasTheRowThatAsksToTrustIt() {
        var asked = 0
        compose.setTvContent { Column { TrustRow(player, onClick = { asked++ }) } }
        listOf(
            PlayerStatus.NotDetected,
            PlayerStatus.Detected(GALLERY),
            PlayerStatus.Unrecognized(GALLERY, Decision.NOT_A_ZIDOO),
            PlayerStatus.Unrecognized(GALLERY, Decision.NO_CERTIFICATE),
        ).forEach {
            player = it
            compose.waitForIdle()
            compose.onNodeWithText(TRUST).assertDoesNotExist()
        }
        player = PlayerStatus.Unrecognized(GALLERY, Decision.CHANGED, UPDATED)
        compose.waitForIdle()
        compose.onNodeWithText("Lecteur vidéo 6.4.42, mis à jour le${NB}12${NB}octobre${NB}2026").assertExists()
        compose.onNode(hasText(TRUST) and hasClickAction()).requestFocus()
        compose.press(Key.DirectionCenter)
        assertEquals("it asks, and trusts nothing", 1, asked)

        // An app in the player's place says when it was installed.
        player = PlayerStatus.Unrecognized(GALLERY, Decision.CHANGED, UPDATED.copy(system = false, otherSignature = true, build = null))
        compose.waitForIdle()
        compose.onNodeWithText("Lecteur vidéo 6.4.42, installé le${NB}12${NB}octobre${NB}2026").assertExists()
    }

    /**
     * Trusted from its row (#58), the player is read again: the row leaves with what it said, focus going on to the pane's
     * next row first, and the player reads Détecté.
     */
    @Test
    fun aPlayerTrustedFromItsRowLeavesItAndReadsDetected() {
        val zidoo = FakePlayer(UPDATED)
        showStatus(zidoo)
        compose.onNodeWithText("Détecté, non reconnu").assertExists()
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText("Ne pas lui faire confiance").assertIsFocused()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE + FRAME_MS)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(UPDATED), zidoo.trusted)
        assertLeftForTheNextRow()
    }

    /**
     * Its updates uninstalled while the app was away, the player is no longer changed: OK on the row finds nothing to ask,
     * opens nothing and trusts nothing, and the row leaves as once trusted.
     */
    @Test
    fun aRowWithNothingLeftToAskLeavesAsOnceTrusted() {
        val zidoo = FakePlayer(UPDATED)
        val asked = showStatus(zidoo)
        zidoo.now = null
        compose.press(Key.DirectionCenter)
        assertNull("no sheet", asked.facts)
        assertEquals(emptyList<PlayerFacts>(), zidoo.trusted)
        assertLeftForTheNextRow()
    }

    /** No longer changed by the time the sheet is answered: nothing is pinned, the sheet closes, and the row leaves too. */
    @Test
    fun aPlayerNoLongerChangedWhenAnsweredTakesTheSheetAndTheRowAway() {
        val zidoo = FakePlayer(UPDATED)
        val asked = showStatus(zidoo)
        compose.press(Key.DirectionCenter)
        zidoo.now = null
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE + FRAME_MS)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertNull("the sheet is gone", asked.facts)
        assertEquals(emptyList<PlayerFacts>(), zidoo.trusted)
        assertLeftForTheNextRow()
    }

    /** This device's player as `ZidooPlayer` finds it, changed while [now] names it, trusted only as it is now. */
    private class FakePlayer(var now: PlayerFacts?) : PlayerTrust {
        val trusted = mutableListOf<PlayerFacts>()

        fun status(): PlayerStatus {
            val facts = now ?: return PlayerStatus.Detected(GALLERY)
            return PlayerStatus.Unrecognized(GALLERY, Decision.CHANGED, facts)
        }

        override fun changed(): PlayerFacts? = now

        override fun trust(seen: PlayerFacts): Boolean {
            if (seen != now) return false
            trusted += seen
            now = null
            return true
        }
    }

    /** About's state over [zidoo], the pane's next row under it, focus on the row that asks to trust it; the sheet's state. */
    private fun showStatus(zidoo: FakePlayer): TrustAsked {
        val vm = AboutViewModel(playerStatus = zidoo::status, apiAnswers = { true })
        val asked = TrustAsked(zidoo)
        compose.setTvContent {
            Column {
                AboutStatus(jellyfin = false, vm = vm, asked = asked)
                SupportRow()
            }
        }
        compose.onNode(hasText(TRUST) and hasClickAction()).requestFocus()
        compose.waitForIdle()
        return asked
    }

    /** The row gone with what it said, focus on the pane's next row, and the player Détecté. */
    private fun assertLeftForTheNextRow() {
        compose.onNodeWithText(TRUST).assertDoesNotExist()
        compose.onNodeWithText("Soutenir Houselights").assertIsFocused()
        compose.onNodeWithText("Détecté").assertExists()
        compose.onNodeWithText("Détecté, non reconnu").assertDoesNotExist()
    }

    private companion object {
        const val FRAME_MS = 32L
        const val NB = "\u00a0"
        const val GALLERY = "com.android.gallery3d"
        val TRUST: String get() = text(R.string.trust_player)

        /** The Z1000 Pro's player, updated on 12 October 2026 since passwords went to it. */
        val UPDATED = PlayerFacts(
            packageName = GALLERY,
            label = "Lecteur vidéo",
            version = "6.4.42",
            updatedAt = LocalDate.of(2026, 10, 12).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            system = true,
            otherSignature = false,
            certificate = "c0ffee",
            build = "6442@1",
        )
    }
}
