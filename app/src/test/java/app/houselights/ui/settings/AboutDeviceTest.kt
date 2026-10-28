package app.houselights.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import app.houselights.player.PlayerFacts
import app.houselights.player.PlayerStatus
import app.houselights.player.ZidooPlayer.Decision
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
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
        compose.onNodeWithText("Cet appareil n’est pas une Zidoo", substring = true).assertExists()
        compose.onNodeWithText("com.android.gallery3d", substring = true).assertExists()
    }

    @Test
    fun aPlayerThatChangedIsNotRecognised() {
        show(PlayerStatus.Unrecognized("com.zidoo.player", Decision.CHANGED))
        compose.onNodeWithText("Détecté, non reconnu").assertExists()
        compose.onNodeWithText("A changé depuis la dernière lecture", substring = true).assertExists()
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

    private companion object {
        const val NB = "\u00a0"
        const val GALLERY = "com.android.gallery3d"
        const val TRUST = "Faire confiance au nouveau lecteur"

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
