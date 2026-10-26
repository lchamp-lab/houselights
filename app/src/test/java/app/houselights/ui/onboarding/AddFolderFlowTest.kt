package app.houselights.ui.onboarding

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.FoundNas
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.DS920
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.PASSWORD
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.PLAYER
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.SILENT_NAS
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.USER
import app.houselights.ui.onboarding.FlowKeys.FIELD_HOST
import app.houselights.ui.onboarding.FlowKeys.FIELD_PASSWORD
import app.houselights.ui.onboarding.FlowKeys.FIELD_PATH
import app.houselights.ui.onboarding.FlowKeys.FIELD_USER
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.afterProcessDeath
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.wholePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * « Ajouter un dossier » driven from the remote over the board's network (FakeFlowNetwork): the first focus of each step,
 * the search row that becomes the first NAS found, Back at each level, each row's state and each message where it goes,
 * how each row stands to the library, a path typed and what the NAS proposes for it, and the flow found again after
 * Android stopped the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class AddFolderFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val db: LibraryDatabase by lazy { testDatabase { onCallingThread() } }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val network = FakeFlowNetwork()

    /** How the flow ended, once it did. */
    private var done: FlowDone? = null

    /** Back left the flow from its first step. */
    private var left = false

    private lateinit var library: LocalLibrary

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    private fun text(id: Int, vararg args: Any): String = context.getString(id, *args)

    /** The flow over a library holding [folders], drawn and settled; [saved] as a stopped process left it. */
    private fun flow(vararg folders: SourceEntity, saved: SavedStateHandle = SavedStateHandle()): FolderFlow {
        val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        // The scan asked after an add never runs: it would go to the network.
        val idle = CoroutineScope(Job().apply { cancel() })
        val scanner = LibraryScanner(db.dao(), { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
        runBlocking { folders.forEach { db.dao().insertSource(it) } }
        library = LocalLibrary(context, db.dao(), scanner, artwork) {}
        val flow = FolderFlow(network, library, scope, useFolders = { false }, saved = saved)
        compose.setTvContent { AddFolderFlow(flow, onLeave = { left = true }, onDone = { done = it }) }
        compose.waitUntil { flow.ready }
        compose.waitForIdle()
        return flow
    }

    private fun focusIsOn(words: String) = compose.onNode(isFocused() and hasText(words, substring = true)).assertExists()

    private fun ok() = compose.press(Key.DirectionCenter)

    private fun down(times: Int = 1) = compose.press(Key.DirectionDown, times)

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Films, NFS, on DS920: the library of a second folder. */
    private val films = SourceEntity(
        name = "Films", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = DS920, share = "volume1/video", path = "Films",
        username = "", password = Secret(""), uid = 1026, gid = 100,
    )

    /** Films over SMB with the account kept. */
    private val smbFilms =
        films.copy(protocol = Protocol.SMB, share = "video", username = USER, password = Secret(PASSWORD), uid = null, gid = null)

    private val ds920 = FoundNas("DS920", DS920, setOf(Protocol.SMB, Protocol.NFS))

    /**
     * A first folder: the network's first row holds the first focus while it searches, and the first NAS found takes its
     * place, focus staying on it; the next one comes under it, and its protocols once its ports answered.
     */
    @Test
    fun theSearchRowTakesTheFirstFocusAndBecomesTheFirstNasFound() {
        flow()
        focusIsOn(text(R.string.nas_searching))
        network.announce(FoundNas("DS920", DS920))
        compose.waitForIdle()
        focusIsOn("DS920")
        compose.onNodeWithText(text(R.string.checking), useUnmergedTree = true).assertExists()
        network.announce(FoundNas("NAS du bureau", "192.168.1.31"))
        network.announce(ds920)
        compose.waitForIdle()
        focusIsOn("DS920")
        compose.onNodeWithText("SMB · NFS", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.nas_searching), useUnmergedTree = true).assertExists()
        network.searchEnds()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.nas_searching), useUnmergedTree = true).assertDoesNotExist()
        focusIsOn("DS920")
    }

    /** None answering, the search row becomes « Rechercher à nouveau », the same row, focus kept. */
    @Test
    fun noNasAnsweringTheSearchRowSearchesAgain() {
        flow()
        network.searchEnds()
        compose.waitForIdle()
        focusIsOn(text(R.string.nas_search_again))
        compose.onNodeWithText(text(R.string.nas_none_found), useUnmergedTree = true).assertExists()
    }

    /**
     * A second folder on a NAS already used is five OKs: the NAS (first, by the name it announces, its protocol kept, no
     * protocol asked), its shared folder (the last folder's), the subfolder named for the kind the library lacks,
     * « Choisir ce dossier », the kind its name says; its NFS user and group carried over.
     */
    @Test
    fun aSecondFolderOnTheSameNasIsFiveOks() {
        val flow = flow(films)
        network.announce(ds920)
        compose.waitForIdle()
        focusIsOn("DS920")
        compose.onNodeWithText(DS920 + FACT_GAP + "Films", useUnmergedTree = true).assertExists()
        ok()
        compose.waitUntil { flow.step == FlowStep.SHARES }
        compose.waitForIdle()
        focusIsOn("video")
        assertEquals("1026" to "100", flow.uid to flow.gid)
        ok()
        compose.waitUntil { flow.step == FlowStep.BROWSE }
        compose.waitForIdle()
        focusIsOn("Séries")
        ok()
        compose.waitUntil { flow.path == listOf("Séries") }
        compose.waitForIdle()
        focusIsOn(text(R.string.choose_folder))
        ok()
        compose.waitUntil { flow.step == FlowStep.KIND }
        compose.waitForIdle()
        focusIsOn(text(R.string.library_shows))
        ok()
        compose.waitUntil { done != null }
        val added = runBlocking { db.dao().sources() }.single { it.path == "Séries" }
        val where = listOf(added.kind, added.protocol, added.host, added.share)
        assertEquals(listOf(SourceKind.SHOWS, Protocol.NFS, DS920, "volume1/video"), where)
        assertEquals(1026 to 100, added.uid to added.gid)
        assertEquals(FlowDone(switched = false, folderId = added.id), done)
    }

    /** Back goes up one level, focus on the folder left; from the shared folder's root, a step; from the first, out. */
    @Test
    fun backGoesUpALevelThenAStep() {
        val flow = flow(films)
        ok()
        compose.waitUntil { flow.step == FlowStep.SHARES }
        ok()
        compose.waitUntil { flow.step == FlowStep.BROWSE }
        compose.waitForIdle()
        flow.openFolder("Films2")
        compose.waitUntil { flow.path == listOf("Films2") }
        compose.waitForIdle()
        focusIsOn(text(R.string.choose_folder))
        back()
        assertEquals(emptyList<String>(), flow.path)
        focusIsOn("Films2")
        back()
        assertEquals(FlowStep.SHARES, flow.step)
        focusIsOn("video")
        back()
        assertEquals(FlowStep.WHERE, flow.step)
        focusIsOn(DS920)
        back()
        assertTrue("Back from the first step leaves", left)
    }

    /**
     * Each row says how it stands to the library before anything is chosen: the share holding Films, Films itself
     * disabled, « Choisir ce dossier » disabled at the share's root that holds it, an empty folder disabled, ringed.
     */
    @Test
    fun eachRowSaysHowItStandsToTheLibrary() {
        val flow = flow(films)
        ok()
        compose.waitUntil { flow.step == FlowStep.SHARES }
        compose.waitForIdle()
        compose.onNodeWithText("/volume1/video" + FACT_GAP + text(R.string.share_holds_one, "Films"), useUnmergedTree = true).assertExists()
        ok()
        compose.waitUntil { flow.step == FlowStep.BROWSE }
        compose.waitForIdle()
        compose.onNode(hasText(text(R.string.folder_holds_added, "Films"))).assertIsNotEnabled()
        compose.onNode(hasText("Films") and hasText(text(R.string.folder_overlaps_state))).assertIsNotEnabled()
        flow.openFolder("Archives 2019")
        compose.waitUntil { flow.path == listOf("Archives 2019") }
        compose.waitForIdle()
        focusIsOn(text(R.string.folder_empty_short))
        compose.onNode(hasText(text(R.string.folder_empty_short))).assertIsNotEnabled()
        // OK on it does nothing.
        ok()
        assertEquals(FlowStep.BROWSE, flow.step)
    }

    /** A level holding Films and Séries is said, still choosable, focus on its first subfolder; a level's content is counted. */
    @Test
    fun aLevelSaysWhatItHolds() {
        val flow = flow()
        flow.chooseFound(ds920)
        compose.waitUntil { flow.step == FlowStep.PROTOCOLS }
        compose.waitForIdle()
        focusIsOn("SMB")
        flow.chooseProtocol(Protocol.NFS)
        compose.waitUntil { flow.step == FlowStep.SHARES }
        flow.openShare(FlowShare("/volume1/media"))
        compose.waitUntil { flow.step == FlowStep.BROWSE }
        compose.waitForIdle()
        // At an export's root, an empty library: the first subfolder named for either kind.
        focusIsOn("Films")
        compose.onNodeWithText(text(R.string.folder_holds_both), useUnmergedTree = true).assertExists()
        back()
        flow.openShare(FlowShare("/volume1/video"))
        compose.waitUntil { flow.step == FlowStep.BROWSE }
        flow.openFolder("Films2")
        compose.waitUntil { flow.path == listOf("Films2") }
        compose.waitForIdle()
        val folders = context.resources.getQuantityString(R.plurals.folder_count, FakeFlowNetwork.FILMS2, FakeFlowNetwork.FILMS2)
        val videos = context.resources.getQuantityString(R.plurals.video_count, 2, 2)
        focusIsOn(text(R.string.folder_content, folders, videos))
    }

    /**
     * An export not allowed for this player is marked before it is opened; opened, its row says « Non autorisé » and the
     * panel what to allow and where, the NAS having answered: « Connexion réussie », the sentence the only mark.
     */
    @Test
    fun anExportNotAllowedSaysWhatToAllow() {
        val flow = flow(films)
        ok()
        compose.waitUntil { flow.step == FlowStep.SHARES }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.not_allowed), useUnmergedTree = true).assertExists()
        down()
        focusIsOn("photo")
        ok()
        compose.waitUntil { flow.message != null }
        compose.waitForIdle()
        focusIsOn("photo")
        val said = text(R.string.error_nfs_refused, PLAYER, wholePath("/volume1/photo"))
        compose.onNodeWithText(said, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.connection_ok), useUnmergedTree = true).assertExists()
        assertEquals(RowFailure.NOT_ALLOWED, flow.rowFailures[FlowKeys.shareKey(FlowShare("/volume1/photo"))])
    }

    /** While a row is under way it says so in place of its value, the panel « Connexion en cours… ». */
    @Test
    fun theRowPressedSaysWhatIsUnderWay() {
        val flow = flow(films)
        val exports = network.hold("exports")
        ok()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.signing_in), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.connection_testing), useUnmergedTree = true).assertExists()
        assertEquals(FlowStep.WHERE, flow.step)
        // OK on it again does nothing: one call.
        ok()
        exports.complete(Unit)
        compose.waitUntil { flow.step == FlowStep.SHARES }
        assertEquals(1, network.asked.count { it.startsWith("exports") })
    }

    /** A NAS silent: its row « Ne répond pas », the panel « Connexion impossible » and why; OK tries again. */
    @Test
    fun aTypedAddressThatDoesNotAnswerIsSaidUnderTheField() {
        val flow = flow()
        flow.typeAddress()
        compose.waitForIdle()
        compose.onNode(isFocused()).assertExists()
        flow.type(FIELD_HOST, "")
        flow.continueAddress()
        compose.waitForIdle()
        assertEquals(FieldError(FIELD_HOST, FlowMessage.Missing(R.string.error_host_missing)), flow.fieldError)
        flow.type(FIELD_HOST, SILENT_NAS)
        flow.continueAddress()
        compose.waitUntil { flow.fieldError != null }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.error_nas_unreachable, SILENT_NAS), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.connection_failed), useUnmergedTree = true).assertExists()
        assertNull("one mark: none in the panel", flow.message)
        back()
        assertEquals(FlowStep.WHERE, flow.step)
        focusIsOn(text(R.string.type_address))
        assertEquals("what was typed is kept", SILENT_NAS, flow.typedHost)
    }

    /**
     * SMB: the account step, the refusal under the password, which takes focus; the panel « Connexion réussie »; then
     * signed in, the shares listed with « Changer de compte », the account among the panel's facts.
     */
    @Test
    fun aRefusedPasswordIsSaidUnderIt() {
        val flow = flow()
        flow.chooseFound(ds920)
        compose.waitUntil { flow.step == FlowStep.PROTOCOLS }
        ok()
        compose.waitUntil { flow.step == FlowStep.ACCOUNT }
        compose.waitForIdle()
        compose.onNode(isFocused() and hasContentDescription(text(R.string.field_user))).assertExists()
        flow.type(FIELD_USER, USER)
        flow.type(FIELD_PASSWORD, "wrong")
        flow.signIn()
        compose.waitUntil { flow.fieldError != null }
        compose.waitForIdle()
        assertEquals(FieldError(FIELD_PASSWORD, FlowMessage.LoginRefused), flow.fieldError)
        compose.onNodeWithText(text(R.string.error_login_failed), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.connection_ok), useUnmergedTree = true).assertExists()
        flow.type(FIELD_PASSWORD, PASSWORD)
        flow.signIn()
        compose.waitUntil { flow.step == FlowStep.SHARES }
        compose.waitForIdle()
        focusIsOn("home")
        compose.onNodeWithText(text(R.string.change_account_desc, USER), useUnmergedTree = true).assertExists()
        assertTrue("signed in as the account", flow.signedIn && !flow.guest)
    }

    /** A guest the NAS refuses is said under the user; a guest let in is « Connecté en invité ». */
    @Test
    fun aGuestIsSaidUnderTheUser() {
        val flow = flow()
        flow.chooseFound(ds920)
        compose.waitUntil { flow.step == FlowStep.PROTOCOLS }
        flow.chooseProtocol(Protocol.SMB)
        compose.waitUntil { flow.step == FlowStep.ACCOUNT }
        network.guests = false
        flow.signIn(asGuest = true)
        compose.waitUntil { flow.fieldError != null }
        assertEquals(FieldError(FIELD_USER, FlowMessage.GuestRefused), flow.fieldError)
        network.guests = true
        flow.signIn(asGuest = true)
        compose.waitUntil { flow.step == FlowStep.SHARES }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.change_account_guest_desc), useUnmergedTree = true).assertExists()
        // A share not open to guests says so, and where to go.
        flow.openShare(FlowShare("video"))
        compose.waitUntil { flow.message != null }
        assertEquals(FlowMessage.GuestShare("video"), flow.message)
    }

    /** The account a folder of this NAS signs in with is reused, never shown: no account step. */
    @Test
    fun theAccountOfTheNasIsReused() {
        val flow = flow(smbFilms)
        ok()
        compose.waitUntil { flow.step == FlowStep.SHARES }
        compose.waitForIdle()
        focusIsOn("video")
        assertEquals("", flow.password)
        assertEquals(listOf("shares $USER"), network.asked.filter { it.startsWith("shares") })
    }

    /**
     * A path typed whole opens step 5 at that folder, focus on « Choisir ce dossier »; an export typed short is said
     * under the field, and what the NAS proposes takes the focus, OK on it typing it and going on.
     */
    @Test
    fun aPathTypedOpensItsFolderAndTheNasProposesWhatItMeant() {
        val flow = flow(films)
        ok()
        compose.waitUntil { flow.step == FlowStep.SHARES }
        flow.typePath()
        compose.waitForIdle()
        flow.type(FIELD_PATH, "media")
        flow.continuePath()
        compose.waitUntil { flow.suggestion != null }
        compose.waitForIdle()
        assertEquals("/volume1/media", flow.suggestion)
        assertEquals(FieldError(FIELD_PATH, FlowMessage.NoExport("media")), flow.fieldError)
        focusIsOn(text(R.string.use_suggestion, "/volume1/media"))
        ok()
        compose.waitUntil { flow.step == FlowStep.BROWSE }
        compose.waitForIdle()
        focusIsOn(text(R.string.choose_folder))
        assertEquals("/volume1/media" to emptyList<String>(), flow.share?.path to flow.path)

        back()
        assertEquals(FlowStep.SHARES, flow.step)
        flow.typePath()
        flow.type(FIELD_PATH, "/volume1/video/Films2")
        flow.continuePath()
        compose.waitUntil { flow.step == FlowStep.BROWSE }
        compose.waitForIdle()
        assertEquals("/volume1/video" to listOf("Films2"), flow.share?.path to flow.path)
        focusIsOn(text(R.string.choose_folder))
    }

    /** A subfolder typed within a letter of one the NAS has: said under the field, the NAS proposing it. */
    @Test
    fun aSubfolderMisspelledIsProposed() {
        val flow = flow(films)
        ok()
        compose.waitUntil { flow.step == FlowStep.SHARES }
        flow.typePath()
        flow.type(FIELD_PATH, "/volume1/video/Spectcles")
        flow.continuePath()
        compose.waitUntil { flow.fieldError != null }
        assertEquals(FieldError(FIELD_PATH, FlowMessage.NoFolder("/volume1/video", smb = false, name = "Spectcles")), flow.fieldError)
        assertEquals("/volume1/video/Spectacles", flow.suggestion)
    }

    /**
     * A NAS that lists no export: the path is typed in step 4's place, « Compte NFS » under it, the longest start of the
     * path that mounts its folder; Back from it, the step before.
     */
    @Test
    fun aNasWithoutAListOfExportsAsksForThePath() {
        network.exports = null
        val flow = flow(films)
        ok()
        compose.waitUntil { flow.step == FlowStep.TYPE_PATH }
        compose.waitForIdle()
        assertTrue(flow.noList)
        compose.onNodeWithText(text(R.string.flow_path_no_list_desc), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.nfs_identity_title), useUnmergedTree = true).assertExists()
        flow.type(FIELD_PATH, "/volume1/video/Films2")
        flow.continuePath()
        compose.waitUntil { flow.step == FlowStep.BROWSE }
        assertEquals("/volume1/video/Films2" to emptyList<String>(), flow.share?.path to flow.path)
        back()
        assertEquals(FlowStep.TYPE_PATH, flow.step)
        back()
        assertEquals(FlowStep.WHERE, flow.step)
    }

    /** What was chosen survives Android stopping the app: the same level, the same focus, the password asked again. */
    @Test
    fun theFlowIsFoundAgainAfterAStoppedProcess() {
        val saved = SavedStateHandle()
        val flow = flow(films, saved = saved)
        ok()
        compose.waitUntil { flow.step == FlowStep.SHARES }
        ok()
        compose.waitUntil { flow.step == FlowStep.BROWSE }
        flow.openFolder("Films2")
        compose.waitUntil { flow.path == listOf("Films2") }
        compose.waitForIdle()

        val again = FolderFlow(network, library, scope, useFolders = { false }, saved = saved.afterProcessDeath())
        compose.waitUntil { again.ready }
        assertEquals(FlowStep.BROWSE, again.step)
        assertEquals(listOf("Films2"), again.path)
        assertEquals(flow.level, again.level)
        assertEquals("/volume1/video", again.share?.path)
        assertEquals(FlowKeys.CHOOSE, again.aim?.key)
    }
}
