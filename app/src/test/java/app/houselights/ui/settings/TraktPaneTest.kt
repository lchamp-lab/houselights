package app.houselights.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import app.houselights.core.SourceConfig
import app.houselights.core.TraktSession
import app.houselights.providers.jellyfin.account
import app.houselights.providers.local.TraktSyncError
import app.houselights.providers.trakt.DeviceCodeEnd
import app.houselights.providers.trakt.TraktSyncState
import app.houselights.ui.design.HlSpace
import app.houselights.ui.testing.FakeTraktLink
import app.houselights.ui.testing.FakeTraktSync
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Trakt pane as the remote drives it: connect with a code in a sheet, Back to cancel, disconnect, and its notes. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class TraktPaneTest {
    @get:Rule
    val compose = createComposeRule()

    private var session by mutableStateOf<TraktSession?>(null)
    private val lea = TraktSession("a1", "r1", expiresAt = 1_700_000_000, username = "lea")
    private var link = FakeTraktLink(onApproved = { session = lea }).apply { onSignOut = { session = null } }
    private val sync = FakeTraktSync()

    private fun show() {
        val vm = TraktViewModel(link, sync, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        compose.setTvContent {
            LazyColumn(Modifier.fillMaxSize()) { traktPane(vm, session) }
        }
    }

    /** Focus on the row that says [name], then OK, as the remote does it. */
    private fun ok(name: String) {
        compose.onNodeWithText(name).requestFocus()
        compose.press(Key.DirectionCenter)
    }

    @Test
    fun withNoAccountItSaysSoAndOffersToConnect() {
        show()
        compose.onNodeWithText("Trakt").assertExists()
        compose.onNodeWithText(DESCRIPTION).assertExists()
        compose.onNodeWithText("Non connecté").assertExists()
        compose.onNodeWithText(CONNECT).assertExists()
        compose.onNodeWithText("Avec un code à saisir sur votre téléphone ou votre ordinateur").assertExists()
    }

    @Test
    fun okShowsTheCodeBesideTheQrCodeOfItsPageWithTheTimeLeft() {
        show()
        ok(CONNECT)
        compose.onNodeWithText("ABCD1234").assertExists()
        compose.onNodeWithContentDescription("Code QR").assertExists()
        compose.onNodeWithText("trakt.tv/activate").assertExists()
        compose.onNodeWithText("En attente de votre accord").assertExists()
        compose.onNodeWithText("Ce code expire dans 10${NB}min").assertExists()
        compose.onNodeWithText(
            "Scannez le code QR avec votre téléphone, ou ouvrez son adresse sur un ordinateur, puis saisissez ce code.",
        ).assertExists()
    }

    /** The code beside its QR code, which with the address under it ends above the Back hint: nothing is cut. */
    @Test
    fun theWholeCodeFitsInTheSheet() {
        show()
        ok(CONNECT)
        val hint = compose.onNodeWithText("Retour pour fermer").getBoundsInRoot()
        val qr = compose.onNodeWithContentDescription("Code QR").getBoundsInRoot()
        val code = compose.onNodeWithText("ABCD1234").getBoundsInRoot()
        assertTrue("${code.right} beside ${qr.left}", code.right <= qr.left)
        assertTrue("${qr.bottom} over ${hint.top}", qr.bottom <= hint.top)
        assertTrue("${hint.bottom}", hint.bottom <= TV_HEIGHT - HlSpace.safeV)
    }

    @Test
    fun backCancelsAndFocusReturnsToTheRow() {
        show()
        ok(CONNECT)
        compose.onNodeWithText("ABCD1234").assertExists()

        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("ABCD1234").assertDoesNotExist()
        assertEquals(1, link.cancelled)
        compose.onNodeWithText(CONNECT).assertIsFocused()
        compose.onNodeWithText("Non connecté").assertExists()
    }

    @Test
    fun approvalClosesTheSheetAndTheSameRowNowDisconnects() {
        show()
        ok(CONNECT)
        compose.runOnIdle { link.end(DeviceCodeEnd.APPROVED) }
        compose.waitForIdle()
        compose.onNodeWithText("ABCD1234").assertDoesNotExist()
        compose.onNodeWithText("Connecté en tant que lea").assertExists()
        compose.onNodeWithText(DISCONNECT).assertIsFocused()
    }

    @Test
    fun aRefusalIsSaidUnderTheRowWhereFocusIs() {
        show()
        ok(CONNECT)
        compose.runOnIdle { link.end(DeviceCodeEnd.DENIED) }
        compose.waitForIdle()
        compose.onNodeWithText("ABCD1234").assertDoesNotExist()
        compose.onNodeWithText("L’accès a été refusé sur Trakt. Connectez votre compte à nouveau pour l’autoriser.").assertExists()
        compose.onNodeWithText("Non effectué").assertExists()
        compose.onNodeWithText(CONNECT).assertIsFocused()
    }

    @Test
    fun disconnectingForgetsTheAccountFocusStayingOnTheRow() {
        session = lea
        show()
        compose.onNodeWithText("Connecté en tant que lea").assertExists()
        ok(DISCONNECT)
        assertEquals(1, link.signedOut)
        compose.onNodeWithText("Non connecté").assertExists()
        compose.onNodeWithText(CONNECT).assertIsFocused()
    }

    @Test
    fun aConnectionTraktEndedIsSaidAboveConnect() {
        session = lea.copy(ended = true)
        show()
        compose.onNodeWithText("Trakt a mis fin à cette connexion. Connectez votre compte à nouveau.").assertExists()
        compose.onNodeWithText(CONNECT).assertExists()
    }

    @Test
    fun anAccountWhoseNameIsUnknownReadsIt() {
        session = lea.copy(username = null)
        show()
        compose.onNodeWithText("Connecté").assertExists()
        compose.runOnIdle { assertEquals(1, link.namesRead) }
    }

    @Test
    fun theLastSyncIsSaidWithWhatItCountedAndSyncingIsOneRow() {
        session = lea
        sync.state.value = TraktSyncState(syncedAt = System.currentTimeMillis() - 5 * 60_000, watched = 412, inProgress = 1)
        show()
        compose.onNodeWithText("Dernière synchronisation").assertExists()
        // The system words the time, its spaces breaking or not.
        compose.onNodeWithText("Il y a 5", substring = true).assertExists()
        compose.onNodeWithText("minutes${GAP}412 vus, 1 en cours", substring = true).assertExists()
        compose.onNodeWithText("Réunit ce que vous avez vu ici et sur Trakt").assertExists()
        compose.onNodeWithText(SYNC).assertIsEnabled()

        ok(SYNC)
        assertEquals(1, sync.asked)
        compose.onNodeWithText("Synchronisation…").assertExists()
        // Disabled while it runs, as the remote and accessibility services find it, and still focused.
        compose.onNodeWithText(SYNC).assertIsNotEnabled().assertIsFocused()
    }

    @Test
    fun beforeTheFirstSyncItSaysNever() {
        session = lea
        show()
        compose.onNodeWithText("Jamais").assertExists()
    }

    @Test
    fun aFailedExchangeIsSaidUnderTheSyncRow() {
        session = lea
        sync.state.value = TraktSyncState(syncedAt = System.currentTimeMillis(), error = TraktSyncError.OFFLINE)
        show()
        compose.onNodeWithText("Trakt ne répond pas. Ce que vous regardez lui sera envoyé dès qu’il répondra.").assertExists()
        compose.onNodeWithText("Non effectué").assertExists()
    }

    @Test
    fun aRefusalOfTheAccountSaysWhatToDo() {
        session = lea
        sync.state.value = TraktSyncState(syncedAt = System.currentTimeMillis(), error = TraktSyncError.REFUSED)
        show()
        compose.onNodeWithText(
            "Trakt refuse la synchronisation$NB: le compte est peut-être verrouillé, ou la clé de cette version refusée. " +
                "Vérifiez votre compte sur trakt.tv.",
        ).assertExists()
    }

    @Test
    fun theEntryShowsWithTheFoldersOnly() {
        assertTrue(Pane.Trakt in panesFor(SourceConfig.Local, pathMapping = false, counts = true))
        assertFalse(Pane.Trakt in panesFor(account, pathMapping = true, counts = true))
        assertFalse(Pane.Trakt in panesFor(null, pathMapping = false, counts = false))
    }

    @Test
    fun aBuildWithoutKeysSaysSoAndOffersNothing() {
        link = FakeTraktLink(available = false)
        show()
        compose.onNodeWithText("Cette version de Houselights n’a pas de clé Trakt.").assertExists()
        compose.onNodeWithText(CONNECT).assertDoesNotExist()
    }

    private companion object {
        const val NB = Typography.nbsp
        const val GAP = "$NB$NB "
        const val SYNC = "Synchroniser maintenant"
        const val CONNECT = "Connecter votre compte"
        const val DISCONNECT = "Déconnecter votre compte"
        const val DESCRIPTION =
            "Votre compte Trakt garde ce que vous regardez depuis vos dossiers$NB: les films et épisodes vus, et où vous en êtes."
        val TV_HEIGHT = 540.dp
    }
}
