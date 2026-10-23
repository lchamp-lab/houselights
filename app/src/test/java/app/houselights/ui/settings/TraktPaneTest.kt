package app.houselights.ui.settings

import android.content.Context
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.SourceConfig
import app.houselights.core.TraktSession
import app.houselights.providers.jellyfin.account
import app.houselights.providers.local.TraktSyncError
import app.houselights.providers.trakt.DeviceCodeEnd
import app.houselights.providers.trakt.SkippedEntry
import app.houselights.providers.trakt.SkippedKind
import app.houselights.providers.trakt.TraktSyncState
import app.houselights.settings.InterfaceLanguage
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.HlNavItem
import app.houselights.ui.design.components.HlTwoPane
import app.houselights.ui.testing.FakeTraktLink
import app.houselights.ui.testing.FakeTraktSync
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.FACT_GAP
import java.util.Locale
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
    fun aSyncThatReadEverythingShowsNoSkippedRow() {
        session = lea
        sync.state.value = TraktSyncState(syncedAt = System.currentTimeMillis())
        show()
        compose.onNodeWithText(SYNC).assertExists()
        compose.onNodeWithText("ignoré", substring = true).assertDoesNotExist()
        compose.onNodeWithText(SKIPPED_DESC).assertDoesNotExist()
    }

    /** Between syncing and disconnecting, as a row like them: the sync went through, nothing asks for attention. */
    @Test
    fun whatASyncLeftOutIsCountedBetweenSyncingAndDisconnecting() {
        session = lea
        sync.state.value = TraktSyncState(syncedAt = System.currentTimeMillis(), skipped = listOf(SHOW, PAUSED_FILM))
        show()
        compose.onNodeWithText(SKIPPED_DESC).assertExists()
        compose.onNodeWithText("Non effectué").assertDoesNotExist()
        val above = compose.onNodeWithText(SYNC).getBoundsInRoot()
        val row = compose.onNodeWithText("2 éléments ignorés").getBoundsInRoot()
        val below = compose.onNodeWithText(DISCONNECT).getBoundsInRoot()
        assertTrue("${above.bottom} ${row.top} ${row.bottom} ${below.top}", above.bottom <= row.top && row.bottom <= below.top)
    }

    @Test
    fun okOpensWhatWasLeftOutWhatItHoldsBackAndWhatToDo() {
        session = lea
        sync.state.value = TraktSyncState(syncedAt = System.currentTimeMillis(), skipped = listOf(SHOW, PAUSED_FILM))
        show()
        ok("2 éléments ignorés")
        compose.onNodeWithText(
            "Trakt les envoie sous une forme que cette version ne sait pas lire, souvent une lecture en pause laissée par une " +
                "autre application. Le reste est synchronisé, et vos marquages partent normalement.",
        ).assertExists()
        compose.onNodeWithText("Breaking Bad").assertExists()
        compose.onNodeWithText("Historique des épisodes").assertExists()
        compose.onNodeWithText("The Matrix${FACT_GAP}1999").assertExists()
        compose.onNodeWithText("Lecture en pause").assertExists()
        compose.onNodeWithText(EPISODES_HELD).assertExists()
        compose.onNodeWithText(MOVIES_HELD).assertDoesNotExist()
        compose.onNodeWithText(
            "Pour régler$NB: supprimez-les sur trakt.tv, dans Progression ou Historique, ou mettez Houselights à jour quand une " +
                "version saura les lire.",
        ).assertExists()
    }

    /** The sentence on marks held back follows the history read in part, and a pause alone holds nothing back. */
    @Test
    fun theMarksHeldBackAreSaidForTheHistoryReadInPartOnly() {
        var skipped by mutableStateOf(listOf(SkippedEntry(SkippedKind.MOVIE_HISTORY, "Heat", 1995)))
        compose.setTvContent { SkippedSheet(skipped, onDismiss = {}) }
        compose.onNodeWithText(MOVIES_HELD).assertExists()
        compose.onNodeWithText(EPISODES_HELD).assertDoesNotExist()

        skipped = listOf(PAUSED_FILM)
        compose.onNodeWithText("Lecture en pause").assertExists()
        compose.onNodeWithText(MOVIES_HELD).assertDoesNotExist()
        compose.onNodeWithText("Tant que", substring = true).assertDoesNotExist()

        skipped = listOf(SHOW, SkippedEntry(SkippedKind.MOVIE_HISTORY))
        compose.onNodeWithText(
            "Tant que l’historique des films et celui des épisodes ne se lisent pas en entier, Houselights n’envoie pas à Trakt " +
                "les films et les épisodes vus ici qui lui manquent, pour ne pas créer de doublons.",
        ).assertExists()
    }

    /** One entry is said in the singular; those Trakt names nothing of are counted on one line of their kind. */
    @Test
    fun entriesWithoutATitleAreCountedByKind() {
        var skipped by mutableStateOf(listOf(SkippedEntry(SkippedKind.PAUSED)))
        compose.setTvContent { SkippedSheet(skipped, onDismiss = {}) }
        compose.onNodeWithText("1 élément ignoré").assertExists()
        compose.onNodeWithText("1 élément sans titre").assertExists()
        compose.onNodeWithText("Trakt l’envoie", substring = true).assertExists()
        compose.onNodeWithText("supprimez-le sur", substring = true).assertExists()

        val nameless = SkippedEntry(SkippedKind.PAUSED)
        skipped = listOf(nameless, PAUSED_FILM, nameless, SkippedEntry(SkippedKind.EPISODE_HISTORY))
        compose.onNodeWithText("4 éléments ignorés").assertExists()
        compose.onNodeWithText("2 éléments sans titre").assertExists()
        compose.onNodeWithText("1 élément sans titre").assertExists()
        compose.onAllNodesWithText("Lecture en pause", useUnmergedTree = true).assertCountEquals(2)
    }

    /** Taller than the sheet, Down rings the list and the remote reads the sheet through, its end included. */
    @Test
    fun aSheetTallerThanTheScreenIsReadThroughWithTheRemote() {
        session = lea
        sync.state.value = TraktSyncState(
            syncedAt = System.currentTimeMillis(),
            skipped = (1..12).map { SkippedEntry(SkippedKind.MOVIE_HISTORY, "Film $it") },
        )
        show()
        ok("12 éléments ignorés")
        compose.onNodeWithText(FIX, substring = true).assertIsNotDisplayed()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        // The plate reads as one, its lines merged; its first line is looked for alone.
        compose.onNode(hasText("Film 1") and isFocused()).assertExists()
        repeat(12) { compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNodeWithText(FIX, substring = true).assertIsDisplayed()
        compose.onNodeWithText("Film 1", useUnmergedTree = true).assertIsNotDisplayed()
        repeat(12) { compose.pressInSheet(KeyEvent.KEYCODE_DPAD_UP) }
        compose.onNodeWithText("Film 1", useUnmergedTree = true).assertIsDisplayed()
    }

    /**
     * A history Trakt changed the shape of leaves hundreds out: ten are named, the rest of each kind counted on one line,
     * those naming nothing among them, and the fix stays a few presses away.
     */
    @Test
    fun hundredsLeftOutAreNamedTenAndTheRestCountedByKind() {
        session = lea
        sync.state.value = TraktSyncState(
            syncedAt = System.currentTimeMillis(),
            skipped = (1..600).map { SkippedEntry(SkippedKind.EPISODE_HISTORY, "Série $it") } +
                SkippedEntry(SkippedKind.EPISODE_HISTORY) + (1..3).map { SkippedEntry(SkippedKind.PAUSED, "Film $it") },
        )
        show()
        ok("604 éléments ignorés")
        compose.onNodeWithText("Série 10", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Série 11", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Et 591 autres", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Et 3 autres", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Film 1", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("sans titre", substring = true, useUnmergedTree = true).assertDoesNotExist()
        repeat(8) { compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNodeWithText(FIX, substring = true).assertIsDisplayed()
    }

    /** A sheet that fits takes no focus, as the code's: Back is the one key, and focus is back on the row. */
    @Test
    fun aSheetThatFitsTakesNoFocus() {
        session = lea
        sync.state.value = TraktSyncState(syncedAt = System.currentTimeMillis(), skipped = listOf(PAUSED_FILM))
        show()
        ok("1 élément ignoré")
        compose.onNodeWithText(FIX, substring = true).assertIsDisplayed()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(hasText("The Matrix${FACT_GAP}1999") and isFocused()).assertDoesNotExist()
        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(FIX, substring = true).assertDoesNotExist()
        compose.onNodeWithText("1 élément ignoré").assertIsFocused()
    }

    /** The fix is led by its label, set apart, in every language. */
    @Test
    fun theFixStartsWithItsLabelInEveryLanguage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        InterfaceLanguage.LANGUAGES.forEach { tag ->
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val local = context.createConfigurationContext(config).resources
            val label = local.getString(R.string.trakt_skipped_fix_label)
            listOf(1, 2, 5, 22).forEach { n ->
                val fix = local.getQuantityString(R.plurals.trakt_skipped_fix, n)
                assertTrue("$tag, $n: «$fix» after «$label»", fix.startsWith(label) && fix.length > label.length)
            }
        }
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
        const val SKIPPED_DESC = "Trakt les envoie sous une forme que l’app ne sait pas lire"
        const val FIX = "Pour régler"
        const val MOVIES_HELD = "Tant que l’historique des films ne se lit pas en entier, Houselights n’envoie pas à Trakt les films vus " +
            "ici qui lui manquent, pour ne pas créer de doublons."
        const val EPISODES_HELD = "Tant que l’historique des épisodes ne se lit pas en entier, Houselights n’envoie pas à Trakt les " +
            "épisodes vus ici qui lui manquent, pour ne pas créer de doublons."
        val SHOW = SkippedEntry(SkippedKind.EPISODE_HISTORY, "Breaking Bad")
        val PAUSED_FILM = SkippedEntry(SkippedKind.PAUSED, "The Matrix", 1999)
        val TV_WIDTH = 960.dp
        val TV_HEIGHT = 540.dp
    }
}
