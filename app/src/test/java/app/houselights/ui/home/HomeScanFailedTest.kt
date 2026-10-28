package app.houselights.ui.home

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.nfsFolder
import app.houselights.providers.local.toFolder
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home without a film, its folder's scan failed, says why in its place, as Réglages › Bibliothèque's showcase does
 * (scanErrorText): a password kept that the Keystore did not open for now as kept and tried again at the next scan, never
 * as a folder that could not be read; a password the NAS refused as refused, as ever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class HomeScanFailedTest {
    @get:Rule
    val compose = createComposeRule()

    private val series = nfsFolder("Séries", host = "192.168.1.20", share = "video", path = "Séries", id = 1)
        .copy(protocol = Protocol.SMB, username = "houselights")
        .toFolder()

    private fun show(kind: ScanErrorKind) {
        compose.setTvContent { HomeGrid(HomeState(content = HomeContent.ScanFailed(series, ScanError(kind)))) }
        compose.waitForIdle()
    }

    @Test
    fun aPasswordNotReadForNowIsSaidKeptAndTriedAgainAtTheNextScan() {
        show(ScanErrorKind.PASSWORD_UNREAD)
        compose.onNodeWithText("L’analyse n’a pas abouti").assertExists()
        compose.onNodeWithText(
            "Le mot de passe enregistré de ce dossier n’a pas pu être lu pour l’instant$NB: il est conservé, et sera essayé " +
                "de nouveau à la prochaine analyse.",
        ).assertExists()
        compose.onNodeWithText("Le dossier n’a pas pu être lu", substring = true).assertDoesNotExist()
        compose.onNodeWithText("refusé", substring = true).assertDoesNotExist()
    }

    @Test
    fun aPasswordTheNasRefusedIsStillSaidRefused() {
        show(ScanErrorKind.LOGIN_FAILED)
        compose.onNodeWithText("Le NAS a refusé l’identifiant ou le mot de passe.").assertExists()
    }

    private companion object {
        const val NB = Typography.nbsp
    }
}
