package app.houselights.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.houselights.player.PlayerStatus
import app.houselights.player.ZidooPlayer.Decision
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** About states the device, its player as playback decides and its control API; one state per test, as first composed. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class AboutDeviceTest {
    @get:Rule
    val compose = createComposeRule()

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
}
