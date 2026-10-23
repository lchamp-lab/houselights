package app.houselights.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import app.houselights.core.SourceConfig
import app.houselights.core.TraktSession
import app.houselights.providers.jellyfin.account
import app.houselights.providers.local.TraktSyncError
import app.houselights.providers.trakt.DeviceCodeEnd
import app.houselights.providers.trakt.TraktSyncState
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.HlNavItem
import app.houselights.ui.design.components.HlTwoPane
import app.houselights.ui.testing.FakeTraktLink
import app.houselights.ui.testing.FakeTraktSync
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
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

/** The Trakt pane as the remote drives it: connect with a code shown in the pane, Back to cancel, disconnect, and its notes. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class TraktPaneTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var session by mutableStateOf<TraktSession?>(null)
    private val lea = TraktSession("a1", "r1", expiresAt = 1_700_000_000, username = "lea")
    private var link = FakeTraktLink(onApproved = { session = lea }).apply { onSignOut = { session = null } }
    private val sync = FakeTraktSync()

    private fun show() {
        val vm = TraktViewModel(link, sync, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        compose.setTvContent {
            HlTwoPane(side = {}) { LazyColumn(Modifier.fillMaxSize()) { traktPane(vm, session) } }
        }
    }

    /** Back, as the remote sends it to the screen. */
    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
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
        compose.onNodeWithText("Avec un code à saisir sur téléphone ou ordinateur").assertExists()
    }

    @Test
    fun okShowsTheCodeBesideTheQrCodeOfItsPageWithTheTimeLeft() {
        show()
        ok(CONNECT)
        compose.onNodeWithText(CODE).assertExists()
        compose.onNodeWithText(CONNECT).assertDoesNotExist()
        compose.onNodeWithContentDescription("Code QR").assertExists()
        compose.onNodeWithText("trakt.tv/activate").assertExists()
        compose.onNodeWithText("En attente de votre accord").assertExists()
        compose.onNodeWithText("Ce code expire dans 10${NB}min").assertExists()
        compose.onNodeWithText(
            "Scannez le code QR avec votre téléphone, ou ouvrez son adresse sur un ordinateur, puis saisissez ce code.",
        ).assertExists()
    }

    /** The code beside its QR code, which with the address under it ends inside the pane and the safe area: nothing is cut. */
    @Test
    fun theWholeCodeFitsInThePane() {
        show()
        ok(CONNECT)
        // The QR code and its address are one element.
        val qr = compose.onNodeWithContentDescription("Code QR").getBoundsInRoot()
        val code = compose.onNodeWithText(CODE).getBoundsInRoot()
        assertTrue("${code.right} beside ${qr.left}", code.right <= qr.left)
        assertTrue("${qr.right}", qr.right <= TV_WIDTH - HlSpace.safeH)
        assertTrue("${qr.bottom}", qr.bottom <= TV_HEIGHT - HlSpace.safeV)
    }

    /** Going through the places while a code waits never jumps into the pane: arriving on it takes no focus. */
    @Test
    fun arrivingOnThePaneWhileACodeWaitsLeavesFocusOnThePlaces() {
        val vm = TraktViewModel(link, sync, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        var open by mutableStateOf(false)
        compose.setTvContent {
            val place = Modifier.testTag(PLACE)
            HlTwoPane(side = { HlNavItem(HlIcons.History, "Trakt", selected = open, onFocused = { open = true }, place) }) {
                LazyColumn(Modifier.fillMaxSize()) { if (open) traktPane(vm, session) }
            }
        }
        compose.runOnIdle { vm.connect() }
        compose.onNodeWithTag(PLACE).requestFocus()
        compose.waitForIdle()
        compose.onNodeWithText(CODE).assertExists()
        compose.onNodeWithTag(PLACE).assertIsFocused()
    }

    /** Connecting from its row, focus goes to the code, never to the places beside the pane. */
    @Test
    fun connectingTakesFocusToTheCodeNotToThePlaces() {
        val vm = TraktViewModel(link, sync, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        var placesFocused = 0
        compose.setTvContent {
            HlTwoPane(side = { HlNavItem(HlIcons.Folder, "Bibliothèque", selected = false, onFocused = { placesFocused++ }) }) {
                LazyColumn(Modifier.fillMaxSize()) { traktPane(vm, session) }
            }
        }
        ok(CONNECT)
        compose.onNodeWithText(CODE).assertExists()
        compose.runOnIdle { assertEquals("the places never took focus", 0, placesFocused) }
        compose.onNode(hasAnyDescendant(hasText(CODE)) and isFocused()).assertExists()
        compose.onNodeWithText(CONNECT).assertDoesNotExist()
        // And back: the row takes focus as the code goes.
        back()
        compose.onNodeWithText(CONNECT).assertIsFocused()
        compose.runOnIdle { assertEquals("the places never took focus", 0, placesFocused) }
    }

    @Test
    fun backCancelsAndFocusReturnsToTheRow() {
        show()
        ok(CONNECT)
        compose.onNodeWithText(CODE).assertExists()

        back()
        compose.onNodeWithText(CODE).assertDoesNotExist()
        assertEquals(1, link.cancelled)
        compose.onNodeWithText(CONNECT).assertIsFocused()
        compose.onNodeWithText("Non connecté").assertExists()
    }

    @Test
    fun approvalShowsTheAccountAndTheSameRowNowDisconnects() {
        show()
        ok(CONNECT)
        compose.runOnIdle { link.end(DeviceCodeEnd.APPROVED) }
        compose.waitForIdle()
        compose.onNodeWithText(CODE).assertDoesNotExist()
        compose.onNodeWithText("Connecté").assertExists()
        compose.onNodeWithText("lea").assertExists()
        compose.onNodeWithText(DISCONNECT).assertIsFocused()
    }

    @Test
    fun aRefusalIsSaidUnderTheRowWhereFocusIs() {
        show()
        ok(CONNECT)
        compose.runOnIdle { link.end(DeviceCodeEnd.DENIED) }
        compose.waitForIdle()
        compose.onNodeWithText(CODE).assertDoesNotExist()
        compose.onNodeWithText("L’accès a été refusé sur Trakt. Connectez votre compte à nouveau pour l’autoriser.").assertExists()
        compose.onNodeWithText("Non effectué").assertExists()
        compose.onNodeWithText(CONNECT).assertIsFocused()
    }

    @Test
    fun disconnectingForgetsTheAccountFocusStayingOnTheRow() {
        session = lea
        show()
        compose.onNodeWithText("lea").assertExists()
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
    fun theLastSyncIsSaidBesideWhatItCountedAndSyncingIsOneRow() {
        session = lea
        sync.state.value = TraktSyncState(syncedAt = System.currentTimeMillis() - 5 * 60_000, watched = 412, inProgress = 1)
        show()
        compose.onNodeWithText("Dernière synchronisation").assertExists()
        // The system words the time, its spaces breaking or not.
        compose.onNodeWithText("Il y a 5", substring = true).assertExists()
        compose.onNodeWithText("Vus").assertExists()
        compose.onNodeWithText("412").assertExists()
        compose.onNodeWithText("En cours").assertExists()
        compose.onNodeWithText("1").assertExists()
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
        assertTrue(Pane.Trakt in panesFor(SourceConfig.Local, pathMapping = false, counts = true, tracks = false))
        fun playback(tracks: Boolean) = Pane.Playback in panesFor(SourceConfig.Local, pathMapping = false, counts = true, tracks = tracks)
        assertTrue("the tracks of a Zidoo player", playback(tracks = true))
        assertFalse("nothing to set elsewhere", playback(tracks = false))
        assertFalse(Pane.Trakt in panesFor(account, pathMapping = true, counts = true, tracks = false))
        assertFalse(Pane.Trakt in panesFor(null, pathMapping = false, counts = false, tracks = true))
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
        const val CODE = "ABCD 1234"
        const val PLACE = "place"
        const val SYNC = "Synchroniser maintenant"
        const val CONNECT = "Connecter votre compte"
        const val DISCONNECT = "Déconnecter votre compte"
        const val DESCRIPTION =
            "Votre compte Trakt garde ce que vous regardez$NB: les films et les épisodes vus, et où vous en êtes. " +
                "Sur cet appareil comme ailleurs."
        val TV_WIDTH = 960.dp
        val TV_HEIGHT = 540.dp
    }
}
