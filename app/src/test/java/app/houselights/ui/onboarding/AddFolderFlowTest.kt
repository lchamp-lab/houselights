package app.houselights.ui.onboarding

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.DiskAccess
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.FoundNas
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.Standing
import app.houselights.providers.local.Volume
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.DS920
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.PASSWORD
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.PLAYER
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.SILENT_NAS
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.T7
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
import app.houselights.ui.util.UiError
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

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

    /**
     * The flow over a library holding [folders], drawn and settled; [saved] as a stopped process left it, [useFolders]
     * making the folders the source, [askDisk] asking Android to read the player's disks.
     */
    private fun flow(
        vararg folders: SourceEntity,
        saved: SavedStateHandle = SavedStateHandle(),
        useFolders: suspend () -> Boolean = { false },
        askDisk: DiskAsk = DiskAsk.NONE,
    ): FolderFlow {
        val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        // The scan asked after an add never runs: it would go to the network.
        val idle = CoroutineScope(Job().apply { cancel() })
        val scanner = LibraryScanner(db.dao(), { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
        runBlocking { folders.forEach { db.dao().insertSource(it) } }
        library = LocalLibrary(context, db.dao(), scanner, artwork) {}
        val flow = FolderFlow(network, library, scope, useFolders = useFolders, saved = saved)
        compose.setTvContent { AddFolderFlow(flow, onLeave = { left = true }, onDone = { done = it }, askDisk = askDisk) }
        until { flow.ready }
        compose.waitForIdle()
        return flow
    }

    /**
     * Waits for [done], running the main looper as it goes: the flow's work that went to another thread (a save, this
     * player's address) comes back there, which compose's own waitUntil does not run under Robolectric.
     */
    private fun until(done: () -> Boolean) {
        val end = System.currentTimeMillis() + WAIT_MS
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            if (done()) return
            check(System.currentTimeMillis() < end) { "Still waiting after $WAIT_MS ms" }
            Thread.sleep(POLL_MS)
        }
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

    /**
     * With a library, the search row ends the network's group: a NAS found while focus is on it takes its place, focus
     * staying on it, the search row under it, never a row arriving above focus; the search over with focus on that new
     * row, focus goes to the row above it, the NAS found, rather than nowhere.
     */
    @Test
    fun aNasFoundWhileFocusIsOnTheSearchRowTakesItsPlace() {
        flow(films)
        // DS920, the NAS used, is among « Déjà utilisés »: the network's group has the search row alone.
        network.announce(ds920)
        compose.waitForIdle()
        down()
        focusIsOn(text(R.string.nas_searching))
        network.searchEnds()
        compose.waitForIdle()
        focusIsOn(text(R.string.nas_search_again))
        ok()
        focusIsOn(text(R.string.nas_searching))

        network.announce(FoundNas("NAS du bureau", "192.168.1.31"))
        compose.waitForIdle()
        focusIsOn("NAS du bureau")
        compose.onNodeWithText(text(R.string.nas_searching), useUnmergedTree = true).assertExists()
        down()
        focusIsOn(text(R.string.nas_searching))
        network.searchEnds()
        until { compose.onAllNodes(hasText(text(R.string.nas_searching), substring = true)).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        focusIsOn("NAS du bureau")
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
        until { flow.step == FlowStep.SHARES }
        compose.waitForIdle()
        focusIsOn("video")
        assertEquals("1026" to "100", flow.uid to flow.gid)
        ok()
        until { flow.step == FlowStep.BROWSE }
        compose.waitForIdle()
        focusIsOn("Séries")
        ok()
        until { flow.path == listOf("Séries") }
        compose.waitForIdle()
        focusIsOn(text(R.string.choose_folder))
        ok()
        until { flow.step == FlowStep.KIND }
        compose.waitForIdle()
        focusIsOn(text(R.string.library_shows))
        ok()
        until { done != null }
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
        until { flow.step == FlowStep.SHARES }
        ok()
        until { flow.step == FlowStep.BROWSE }
        compose.waitForIdle()
        flow.openFolder("Films2")
        until { flow.path == listOf("Films2") }
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
        until { flow.step == FlowStep.SHARES }
        compose.waitForIdle()
        compose.onNodeWithText("/volume1/video" + FACT_GAP + text(R.string.share_holds_one, "Films"), useUnmergedTree = true).assertExists()
        ok()
        until { flow.step == FlowStep.BROWSE }
        compose.waitForIdle()
        compose.onNode(hasText(text(R.string.folder_holds_added, "Films"))).assertIsNotEnabled()
        compose.onNode(hasText("Films") and hasText(text(R.string.folder_overlaps_state))).assertIsNotEnabled()
        flow.openFolder("Archives 2019")
        until { flow.path == listOf("Archives 2019") }
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
        until { flow.step == FlowStep.PROTOCOLS }
        compose.waitForIdle()
        focusIsOn("SMB")
        flow.chooseProtocol(Protocol.NFS)
        until { flow.step == FlowStep.SHARES }
        flow.openShare(FlowShare("/volume1/media"))
        until { flow.step == FlowStep.BROWSE }
        compose.waitForIdle()
        // At an export's root, an empty library: the first subfolder named for either kind.
        focusIsOn("Films")
        compose.onNodeWithText(text(R.string.folder_holds_both), useUnmergedTree = true).assertExists()
        back()
        flow.openShare(FlowShare("/volume1/video"))
        until { flow.step == FlowStep.BROWSE }
        flow.openFolder("Films2")
        until { flow.path == listOf("Films2") }
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
        until { flow.step == FlowStep.SHARES }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.not_allowed), useUnmergedTree = true).assertExists()
        down()
        focusIsOn("photo")
        ok()
        until { flow.message != null }
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
        until { flow.step == FlowStep.SHARES }
        assertEquals(1, network.asked.count { it.startsWith("exports") })
    }

    /**
     * A NAS already used whose ports do not answer: its row « Ne répond pas » before anything is chosen; OK asks it again,
     * the row and the panel saying it is silent; once it answers, OK opens it.
     */
    @Test
    fun aNasUsedThatIsSilentSaysSoAndOkAsksItAgain() {
        val flow = flow(films.copy(host = SILENT_NAS))
        until { flow.answers[SILENT_NAS] == NasAnswer.Silent }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.server_silent), useUnmergedTree = true).assertExists()
        fun asked() = network.asked.count { it == "protocols $SILENT_NAS" }
        val probed = asked()
        ok()
        until { flow.message != null }
        compose.waitForIdle()
        assertEquals(probed + 1, asked())
        assertEquals(FlowMessage.Silent(SILENT_NAS), flow.message)
        assertEquals(RowFailure.SILENT, flow.rowFailures[FlowKeys.nasKey(SILENT_NAS)])
        compose.onNodeWithText(text(R.string.error_nas_unreachable, SILENT_NAS), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.connection_failed), useUnmergedTree = true).assertExists()

        network.answering[SILENT_NAS] = setOf(Protocol.NFS)
        ok()
        until { flow.step == FlowStep.SHARES }
        assertEquals(probed + 2, asked())
    }

    /** An address typed that does not answer: said under its field, the panel's ring alone; Back keeps it typed. */
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
        until { flow.fieldError != null }
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
        until { flow.step == FlowStep.PROTOCOLS }
        ok()
        until { flow.step == FlowStep.ACCOUNT }
        compose.waitForIdle()
        compose.onNode(isFocused() and hasContentDescription(text(R.string.field_user))).assertExists()
        flow.type(FIELD_USER, USER)
        flow.type(FIELD_PASSWORD, "wrong")
        flow.signIn()
        until { flow.fieldError != null }
        compose.waitForIdle()
        assertEquals(FieldError(FIELD_PASSWORD, FlowMessage.LoginRefused), flow.fieldError)
        compose.onNodeWithText(text(R.string.error_login_failed), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.connection_ok), useUnmergedTree = true).assertExists()
        flow.type(FIELD_PASSWORD, PASSWORD)
        flow.signIn()
        until { flow.step == FlowStep.SHARES }
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
        until { flow.step == FlowStep.PROTOCOLS }
        flow.chooseProtocol(Protocol.SMB)
        until { flow.step == FlowStep.ACCOUNT }
        network.guests = false
        flow.signIn(asGuest = true)
        until { flow.fieldError != null }
        assertEquals(FieldError(FIELD_USER, FlowMessage.GuestRefused), flow.fieldError)
        network.guests = true
        flow.signIn(asGuest = true)
        until { flow.step == FlowStep.SHARES }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.change_account_guest_desc), useUnmergedTree = true).assertExists()
        // A share not open to guests says so, and where to go.
        flow.openShare(FlowShare("video"))
        until { flow.message != null }
        assertEquals(FlowMessage.GuestShare("video"), flow.message)
    }

    /**
     * « Changer de compte » then Back: the shared folders again with the account they were listed with, whatever was
     * typed or tried meanwhile, a user or a guest the NAS refused; the panel and the row never name another.
     */
    @Test
    fun backFromChangingTheAccountGivesBackTheAccountTheSharesWereListedWith() {
        val flow = flow(smbFilms)
        ok()
        until { flow.step == FlowStep.SHARES }
        flow.changeAccount()
        compose.waitForIdle()
        flow.type(FIELD_USER, "admin")
        compose.waitForIdle()
        val panel = compose.onAllNodesWithText(USER, substring = true, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("the panel names the account the shares were listed as", panel.isNotEmpty())
        back()
        assertEquals(FlowStep.SHARES, flow.step)
        assertEquals(USER, flow.user)
        compose.onNodeWithText(text(R.string.change_account_desc, USER), useUnmergedTree = true).assertExists()
        flow.openShare(FlowShare("video"))
        until { flow.step == FlowStep.BROWSE }
        assertNull(flow.message)

        back()
        network.guests = false
        flow.changeAccount()
        flow.signIn(asGuest = true)
        until { flow.fieldError != null }
        back()
        assertEquals(FlowStep.SHARES, flow.step)
        assertEquals(USER to false, flow.user to flow.guest)
        flow.openShare(FlowShare("video"))
        until { flow.step == FlowStep.BROWSE }
        assertNull(flow.message)
    }

    /** The account a folder of this NAS signs in with is reused, never shown: no account step. */
    @Test
    fun theAccountOfTheNasIsReused() {
        val flow = flow(smbFilms)
        ok()
        until { flow.step == FlowStep.SHARES }
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
        until { flow.step == FlowStep.SHARES }
        flow.typePath()
        compose.waitForIdle()
        flow.type(FIELD_PATH, "media")
        flow.continuePath()
        until { flow.suggestion != null }
        compose.waitForIdle()
        assertEquals("/volume1/media", flow.suggestion)
        assertEquals(FieldError(FIELD_PATH, FlowMessage.NoExport("media")), flow.fieldError)
        focusIsOn(text(R.string.use_suggestion, "/volume1/media"))
        ok()
        until { flow.step == FlowStep.BROWSE }
        compose.waitForIdle()
        focusIsOn(text(R.string.choose_folder))
        assertEquals("/volume1/media" to emptyList<String>(), flow.share?.path to flow.path)

        back()
        assertEquals(FlowStep.SHARES, flow.step)
        flow.typePath()
        flow.type(FIELD_PATH, "")
        flow.continuePath()
        assertEquals(FieldError(FIELD_PATH, FlowMessage.Missing(R.string.error_full_path_missing_nfs)), flow.fieldError)
        flow.type(FIELD_PATH, "/volume1/video/Films2")
        flow.continuePath()
        until { flow.step == FlowStep.BROWSE }
        compose.waitForIdle()
        assertEquals("/volume1/video" to listOf("Films2"), flow.share?.path to flow.path)
        focusIsOn(text(R.string.choose_folder))

        // The level above, never read, is read on Back, focus on the folder left.
        back()
        until { flow.path.isEmpty() }
        compose.waitForIdle()
        assertTrue(network.asked.contains("level volume1/video"))
        focusIsOn("Films2")

        // Read again and refused, Back goes to the shared folders.
        flow.openFolder("Films2")
        until { flow.path == listOf("Films2") }
        flow.typePath()
        flow.continuePath()
        until { flow.step == FlowStep.BROWSE }
        network.folders.remove("volume1/video")
        back()
        until { flow.step == FlowStep.SHARES }
        compose.waitForIdle()
        focusIsOn("video")
    }

    /**
     * SMB: a path typed starts with its share, listed or not (« video$ », hidden, never is), as the NAS spells it when
     * listed; a share it lacks is said under the field with the one within a letter; an empty path asks for the path.
     */
    @Test
    fun aPathTypedOverSmbStartsWithItsShareListedOrNot() {
        val flow = flow(smbFilms)
        ok()
        until { flow.step == FlowStep.SHARES }
        flow.typePath()
        compose.waitForIdle()
        flow.continuePath()
        assertEquals(FieldError(FIELD_PATH, FlowMessage.Missing(R.string.error_full_path_missing_smb)), flow.fieldError)
        compose.onNodeWithText(text(R.string.error_full_path_missing_smb), useUnmergedTree = true).assertExists()

        flow.type(FIELD_PATH, "video$/Films")
        flow.continuePath()
        until { flow.step == FlowStep.BROWSE }
        assertEquals("video$" to listOf("Films"), flow.share?.path to flow.path)

        flow.typePath()
        flow.type(FIELD_PATH, "Video/Films2")
        flow.continuePath()
        until { flow.step == FlowStep.BROWSE && flow.path == listOf("Films2") }
        assertEquals("the share as the NAS spells it", "video" to listOf("Films2"), flow.share?.path to flow.path)

        flow.typePath()
        flow.type(FIELD_PATH, "vide/Films2")
        flow.continuePath()
        until { flow.fieldError != null }
        assertEquals(FieldError(FIELD_PATH, FlowMessage.NoShare(DS920, "vide")), flow.fieldError)
        assertEquals("video/Films2", flow.suggestion)
    }

    /** A subfolder typed within a letter of one the NAS has: said under the field, the NAS proposing it. */
    @Test
    fun aSubfolderMisspelledIsProposed() {
        val flow = flow(films)
        ok()
        until { flow.step == FlowStep.SHARES }
        flow.typePath()
        flow.type(FIELD_PATH, "/volume1/video/Spectcles")
        flow.continuePath()
        until { flow.fieldError != null }
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
        until { flow.step == FlowStep.TYPE_PATH }
        compose.waitForIdle()
        assertTrue(flow.noList)
        compose.onNodeWithText(text(R.string.flow_path_no_list_desc), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.nfs_identity_title), useUnmergedTree = true).assertExists()
        flow.type(FIELD_PATH, "/volume1/video/Films2")
        flow.continuePath()
        until { flow.step == FlowStep.BROWSE }
        assertEquals("/volume1/video/Films2" to emptyList<String>(), flow.share?.path to flow.path)
        back()
        assertEquals(FlowStep.TYPE_PATH, flow.step)
        back()
        assertEquals(FlowStep.WHERE, flow.step)
    }

    /**
     * Back from « Compte NFS » with a user typed and no group: the pair it was opened with comes back, as Continuer
     * would never leave one alone; from root, root again. Both typed, Back keeps them.
     */
    @Test
    fun backFromTheNfsAccountNeverLeavesHalfAPair() {
        val flow = flow(films)
        ok()
        until { flow.step == FlowStep.SHARES }
        flow.openNfsAccount()
        flow.type(FlowKeys.FIELD_GID, "")
        back()
        assertEquals(FlowStep.SHARES, flow.step)
        assertEquals("1026" to "100", flow.uid to flow.gid)
        val identity = compose.onAllNodesWithText(text(R.string.nfs_identity, "1026", "100"), substring = true, useUnmergedTree = true)
        assertTrue("« Compte NFS » says the pair given back", identity.fetchSemanticsNodes().isNotEmpty())

        flow.openNfsAccount()
        flow.type(FlowKeys.FIELD_UID, "")
        flow.type(FlowKeys.FIELD_GID, "")
        flow.continueIdentity()
        assertEquals("" to "", flow.uid to flow.gid)
        flow.openNfsAccount()
        flow.type(FlowKeys.FIELD_UID, "1026")
        back()
        compose.waitForIdle()
        assertEquals("" to "", flow.uid to flow.gid)
        compose.onNodeWithText(text(R.string.nfs_identity, "1026", ""), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.nfs_identity_root), useUnmergedTree = true).assertExists()

        flow.openNfsAccount()
        flow.type(FlowKeys.FIELD_UID, "1027")
        flow.type(FlowKeys.FIELD_GID, "101")
        back()
        assertEquals("1027" to "101", flow.uid to flow.gid)
    }

    /**
     * A folder the NFS user presented may not read: said of that user, « Compte NFS » carrying it over, never of root;
     * root presented, it opens.
     */
    @Test
    fun aReadRefusedNamesTheNfsUserPresented() {
        network.rootOnly = setOf("volume1/musique")
        val flow = flow(films)
        ok()
        until { flow.step == FlowStep.SHARES }
        flow.openShare(FlowShare("/volume1/musique"))
        until { flow.message != null }
        compose.waitForIdle()
        assertEquals(FlowMessage.AccountRefused("1026", "/volume1/musique"), flow.message)
        val said = text(R.string.error_nfs_account_refused, "1026", wholePath("/volume1/musique"))
        compose.onNodeWithText(said, useUnmergedTree = true).assertExists()

        flow.typePath()
        flow.type(FIELD_PATH, "/volume1/musique")
        flow.continuePath()
        until { flow.fieldError != null }
        assertEquals(FieldError(FIELD_PATH, FlowMessage.AccountRefused("1026", "/volume1/musique")), flow.fieldError)

        back()
        flow.openNfsAccount()
        flow.type(FlowKeys.FIELD_UID, "")
        flow.type(FlowKeys.FIELD_GID, "")
        flow.continueIdentity()
        flow.openShare(FlowShare("/volume1/musique"))
        until { flow.step == FlowStep.BROWSE }
    }

    /** What was chosen survives Android stopping the app: the same level, the same focus. */
    @Test
    fun theFlowIsFoundAgainAfterAStoppedProcess() {
        val saved = SavedStateHandle()
        val flow = flow(films, saved = saved)
        ok()
        until { flow.step == FlowStep.SHARES }
        ok()
        until { flow.step == FlowStep.BROWSE }
        flow.openFolder("Films2")
        until { flow.path == listOf("Films2") }
        compose.waitForIdle()

        val again = FolderFlow(network, library, scope, useFolders = { false }, saved = saved.afterProcessDeath())
        until { again.ready }
        assertEquals(FlowStep.BROWSE, again.step)
        assertEquals(listOf("Films2"), again.path)
        assertEquals(flow.level, again.level)
        assertEquals("/volume1/video", again.share?.path)
        assertEquals(FlowKeys.CHOOSE, again.aim?.key)
    }

    /**
     * SMB after a stopped process: an account typed comes back to its password, never kept, its user filled; the account
     * of the NAS's folders is read from the library again, and the level above opens with it.
     */
    @Test
    fun anSmbFlowFoundAgainAsksForThePasswordTypedNotTheOneKept() {
        val saved = SavedStateHandle()
        val typed = flow(saved = saved)
        typed.chooseFound(ds920)
        until { typed.step == FlowStep.PROTOCOLS }
        typed.chooseProtocol(Protocol.SMB)
        until { typed.step == FlowStep.ACCOUNT }
        typed.type(FIELD_USER, USER)
        typed.type(FIELD_PASSWORD, PASSWORD)
        typed.signIn()
        until { typed.step == FlowStep.SHARES }
        typed.openShare(FlowShare("video"))
        until { typed.step == FlowStep.BROWSE }
        typed.openFolder("Films2")
        until { typed.path == listOf("Films2") }

        val again = FolderFlow(network, library, scope, useFolders = { false }, saved = saved.afterProcessDeath())
        until { again.ready }
        assertEquals(FlowStep.ACCOUNT, again.step)
        assertEquals(FIELD_PASSWORD, again.aim?.key)
        assertEquals(USER to "", again.user to again.password)
    }

    @Test
    fun anSmbFlowFoundAgainReadsTheAccountKeptFromTheLibrary() {
        val saved = SavedStateHandle()
        val kept = flow(smbFilms, saved = saved)
        ok()
        until { kept.step == FlowStep.SHARES }
        kept.openShare(FlowShare("video"))
        until { kept.step == FlowStep.BROWSE }
        kept.openFolder("Films2")
        until { kept.path == listOf("Films2") }

        val again = FolderFlow(network, library, scope, useFolders = { false }, saved = saved.afterProcessDeath())
        until { again.ready }
        assertEquals(FlowStep.BROWSE, again.step)
        assertEquals(listOf("Films2"), again.path)
        again.back {}
        until { again.path.isEmpty() && again.step == FlowStep.BROWSE && again.level?.folders?.contains("Films2") == true }
        assertNull(again.message)
    }

    /**
     * The folder added, but making the folders the source failed, the storage full: the flow says why and stays, focus
     * on the kind pressed; OK again only makes them the source, the folder never added twice.
     */
    @Test
    fun aFolderAddedWhoseSourceDidNotChangeIsDoneOnTheNextOk() {
        var full = true
        var switched = true
        val flow = flow(
            films,
            useFolders = {
                if (full) throw IOException("No space left on device")
                switched.also { switched = false }
            },
        )
        ok()
        until { flow.step == FlowStep.SHARES }
        ok()
        until { flow.step == FlowStep.BROWSE }
        compose.waitForIdle()
        ok()
        until { flow.path == listOf("Séries") }
        compose.waitForIdle()
        ok()
        until { flow.step == FlowStep.KIND }
        compose.waitForIdle()
        focusIsOn(text(R.string.library_shows))
        ok()
        until { flow.notSaved != null }
        assertEquals(UiError.NOT_SAVED, flow.notSaved)
        assertNull(done)
        focusIsOn(text(R.string.library_shows))

        full = false
        ok()
        until { done != null }
        assertEquals(true, done?.switched)
        assertEquals(1, runBlocking { db.dao().sources() }.count { it.path == "Séries" })

        // Done and switched, OK again finds the folder in the library: said, never added again nor finished twice.
        done = null
        ok()
        until { flow.message != null }
        val overlaps = flow.message as FlowMessage.Overlaps
        assertEquals("Séries", overlaps.existing)
        assertTrue("${overlaps.standing}", overlaps.standing is Standing.Added)
        assertNull(done)
        assertEquals(1, runBlocking { db.dao().sources() }.count { it.path == "Séries" })
    }

    /** A folder holding the one chosen, added meanwhile: OK on the kind says it is inside it, focus kept, nothing added. */
    @Test
    fun aFolderAddedMeanwhileThatHoldsTheOneChosenIsSaid() {
        val flow = flow(films)
        ok()
        until { flow.step == FlowStep.SHARES }
        ok()
        until { flow.step == FlowStep.BROWSE }
        compose.waitForIdle()
        ok()
        until { flow.path == listOf("Séries") }
        compose.waitForIdle()
        ok()
        until { flow.step == FlowStep.KIND }
        compose.waitForIdle()
        runBlocking { db.dao().insertSource(films.copy(name = "video", path = "")) }
        ok()
        until { flow.message != null }
        compose.waitForIdle()
        val overlaps = flow.message as FlowMessage.Overlaps
        assertEquals("Video", overlaps.existing)
        assertTrue("${overlaps.standing}", overlaps.standing is Standing.Inside)
        compose.onNodeWithText(text(R.string.folder_inside, "Video"), useUnmergedTree = true).assertExists()
        focusIsOn(text(R.string.library_shows))
        assertNull(done)
        assertEquals(0, runBlocking { db.dao().sources() }.count { it.path == "Séries" })
    }

    /**
     * « Sur ce lecteur »: the player's storage and each USB disk by its name, before « Saisir une adresse »; OK on one
     * opens its root at step 5, the panel naming it; Back returns to its row, and its folder is added as the player's own.
     */
    @Test
    fun aDiskOfThisPlayerOpensAtItsRoot() {
        network.mounted = FakeFlowNetwork.DISKS
        val flow = flow()
        compose.onNodeWithText(text(R.string.on_this_player)).assertExists()
        compose.onNode(hasText(text(R.string.disk_internal)) and hasText(text(R.string.disk_internal_desc))).assertExists()
        compose.onNode(hasText("Samsung T7") and hasText(text(R.string.disk_usb)) and hasClickAction()).requestFocus()
        ok()
        until { flow.step == FlowStep.BROWSE }
        compose.waitForIdle()
        focusIsOn("Films")
        compose.onNodeWithText(text(R.string.on_this_player)).assertExists()
        compose.onNodeWithText("Samsung T7").assertExists()

        back()
        until { flow.step == FlowStep.WHERE }
        compose.waitForIdle()
        focusIsOn("Samsung T7")
        ok()
        until { flow.step == FlowStep.BROWSE }
        compose.waitForIdle()
        ok()
        until { flow.path == listOf("Films") }
        compose.waitForIdle()
        focusIsOn(text(R.string.choose_folder))
        ok()
        until { flow.step == FlowStep.KIND }
        compose.waitForIdle()
        focusIsOn(text(R.string.library_movies))
        ok()
        until { done != null }
        val added = runBlocking { db.dao().sources() }.single()
        assertEquals(listOf(Protocol.LOCAL, T7, "", "Films"), listOf(added.protocol, added.host, added.share, added.path))
    }

    /** The player's storage chosen at its root is « Disque interne », as its row says, in step 6 and in the library. */
    @Test
    fun theInternalDiskAtItsRootIsNamedAsItsRow() {
        network.mounted = FakeFlowNetwork.DISKS
        val flow = flow()
        compose.onNode(hasText(text(R.string.disk_internal)) and hasClickAction()).requestFocus()
        ok()
        until { flow.step == FlowStep.BROWSE }
        flow.chooseFolder()
        until { flow.step == FlowStep.KIND }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.flow_kind_desc, text(R.string.disk_internal)), useUnmergedTree = true).assertExists()
        ok()
        until { done != null }
        assertEquals(text(R.string.disk_internal), runBlocking { db.dao().sources() }.single().name)
    }

    /**
     * A disk the app may not read yet: OK asks Android, the row saying « Non autorisé » and the panel what to allow,
     * Android's settings once its window was refused for good, which answer nothing; allowed there, OK on the disk
     * again opens it.
     */
    @Test
    fun aDiskTheAppMayNotReadAsksAndroidFirst() {
        network.mounted = FakeFlowNetwork.DISKS
        network.access = DiskAccess.TO_ASK
        val asked = mutableListOf<DiskAccess>()
        val flow = flow(
            // As rememberDiskAsk: Android's window answers, refused for good here; its settings only open.
            askDisk = { access, answered ->
                asked += access
                if (access == DiskAccess.TO_ASK) answered(true)
            },
        )
        compose.onNode(hasText(text(R.string.disk_internal)) and hasClickAction()).requestFocus()
        ok()
        compose.waitForIdle()
        assertEquals(listOf(DiskAccess.TO_ASK), asked)
        assertEquals(FlowStep.WHERE, flow.step)
        compose.onNodeWithText(text(R.string.disk_access_settings)).assertExists()
        focusIsOn(text(R.string.not_allowed))

        ok()
        compose.waitForIdle()
        assertEquals(listOf(DiskAccess.TO_ASK, DiskAccess.IN_SETTINGS), asked)
        assertEquals(FlowStep.WHERE, flow.step)
        focusIsOn(text(R.string.not_allowed))

        // Allowed in Android's settings, then chosen again.
        network.access = DiskAccess.GRANTED
        ok()
        until { flow.step == FlowStep.BROWSE }
        compose.waitForIdle()
        assertEquals(listOf(DiskAccess.TO_ASK, DiskAccess.IN_SETTINGS), asked)
        focusIsOn("Movies")
    }

    /**
     * OK on a disk the app may not read while a NAS is under way: the NAS stops, as OK on another row stops it, so
     * nothing moves the flow behind Android's window; allowed, the disk opens from the first step.
     */
    @Test
    fun aDiskAskedForStopsTheNasUnderWay() {
        network.mounted = FakeFlowNetwork.DISKS
        network.access = DiskAccess.TO_ASK
        var answer: ((Boolean) -> Unit)? = null
        val flow = flow(films, askDisk = { _, answered -> answer = answered })
        val exports = network.hold("exports")
        ok()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.signing_in), useUnmergedTree = true).assertExists()
        compose.onNode(hasText(text(R.string.disk_internal)) and hasClickAction()).requestFocus()
        ok()
        compose.waitForIdle()
        assertTrue("Android asked", answer != null)
        assertNull(flow.pressed)
        exports.complete(Unit)
        // What was under way had its chance to go on: it did not.
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        assertTrue(network.asked.contains("exports $DS920"))
        assertEquals(FlowStep.WHERE, flow.step)
        assertEquals(FlowMessage.DiskRefused(settings = false), flow.message)

        network.access = DiskAccess.GRANTED
        compose.runOnUiThread { answer?.invoke(false) }
        until { flow.step == FlowStep.BROWSE }
        assertEquals(Volume.INTERNAL, flow.nas?.host)
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
