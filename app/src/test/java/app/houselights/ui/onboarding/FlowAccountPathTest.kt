package app.houselights.ui.onboarding

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import app.houselights.R
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.Secret
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.DS920
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.PASSWORD
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.USER
import app.houselights.ui.onboarding.FlowKeys.FIELD_PASSWORD
import app.houselights.ui.onboarding.FlowKeys.FIELD_PATH
import app.houselights.ui.onboarding.FlowKeys.FIELD_USER
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.afterProcessDeath
import app.houselights.ui.util.wholePath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * « Ajouter un dossier »'s accounts and paths over the board's network (FakeFlowNetwork): SMB's account refused, a
 * guest, the account reused and changed, « Compte NFS » and what it presents, a path typed whole over NFS and SMB and
 * what the NAS proposes for it, and the flow found again after Android stopped the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class FlowAccountPathTest : FlowHarness() {
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
     * The account kept, its password not opened for now by the device's Keystore: never tried, so never said refused;
     * the account step, its user filled, says under the password, which takes focus, that it is kept. Back, then OK on
     * the NAS once the Keystore answers, signs in with the password kept.
     */
    @Test
    fun aPasswordKeptButNotReadForNowIsSaidKeptNotRefused() {
        keystoreDown = true
        val flow = flow(sealedBefore(smbFilms))
        ok()
        until { flow.step == FlowStep.ACCOUNT }
        compose.waitForIdle()
        assertEquals(FieldError(FIELD_PASSWORD, FlowMessage.PasswordUnread), flow.fieldError)
        compose.onNodeWithText(text(R.string.error_password_unread), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.error_login_failed), useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(isFocused() and hasContentDescription(text(R.string.field_password))).assertExists()
        assertEquals(USER to "", flow.user to flow.password)

        keystoreDown = false
        back()
        assertEquals(FlowStep.WHERE, flow.step)
        flow.chooseUsed(flow.used.single())
        until { flow.step == FlowStep.SHARES }
        assertTrue("signed in with the password kept", flow.signedIn && flow.accountKept)
    }

    /**
     * The protocol asked, the account kept not opened for now: Back goes to the protocols, and SMB chosen again once the
     * Keystore answers reads the password kept again and signs in with it.
     */
    @Test
    fun aPasswordKeptButNotReadForNowIsReadAgainFromTheProtocols() {
        keystoreDown = true
        val flow = flow(sealedBefore(smbFilms))
        flow.chooseFound(ds920)
        until { flow.step == FlowStep.PROTOCOLS }
        flow.chooseProtocol(Protocol.SMB)
        until { flow.step == FlowStep.ACCOUNT }
        assertEquals(FieldError(FIELD_PASSWORD, FlowMessage.PasswordUnread), flow.fieldError)

        keystoreDown = false
        back()
        assertEquals(FlowStep.PROTOCOLS, flow.step)
        flow.chooseProtocol(Protocol.SMB)
        until { flow.step == FlowStep.SHARES }
        assertTrue("signed in with the password kept", flow.signedIn && flow.accountKept)
    }

    /** The account kept, its password read but no longer the NAS's: said refused under it, as ever. */
    @Test
    fun aPasswordKeptThatTheNasRefusesIsStillSaidRefused() {
        val flow = flow(smbFilms.copy(password = Secret("changed since on the NAS")))
        ok()
        until { flow.step == FlowStep.ACCOUNT }
        compose.waitForIdle()
        assertEquals(FieldError(FIELD_PASSWORD, FlowMessage.LoginRefused), flow.fieldError)
        compose.onNodeWithText(text(R.string.error_login_failed), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.error_password_unread), useUnmergedTree = true).assertDoesNotExist()
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
        assertEquals(FlowStep.SHARES, flow.step)
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
     * « Compte NFS » found again after a stopped process: Back with a user erased gives back the pair it was opened
     * with, never root.
     */
    @Test
    fun backFromTheNfsAccountFoundAgainGivesBackThePairItWasOpenedWith() {
        val saved = SavedStateHandle()
        val flow = flow(films, saved = saved)
        ok()
        until { flow.step == FlowStep.SHARES }
        flow.openNfsAccount()
        assertEquals("1026" to "100", flow.uid to flow.gid)

        val again = FolderFlow(network, library, scope, useFolders = { false }, saved = saved.afterProcessDeath())
        until { again.ready }
        assertEquals(FlowStep.NFS_ACCOUNT, again.step)
        again.type(FlowKeys.FIELD_UID, "")
        again.back {}
        assertEquals(FlowStep.SHARES, again.step)
        assertEquals("1026" to "100", again.uid to again.gid)
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
     * The level shown, which may hold thousands of folders, is never in what Android keeps: the flow found again reads
     * it again from the NAS; a level the NAS no longer has, the flow is back at the shared folders.
     */
    @Test
    fun theLevelShownIsReadAgainAfterAStoppedProcess() {
        val saved = SavedStateHandle()
        val flow = flow(films, saved = saved)
        ok()
        until { flow.step == FlowStep.SHARES }
        ok()
        until { flow.step == FlowStep.BROWSE }
        flow.openFolder("Films2")
        until { flow.path == listOf("Films2") }
        val shown = checkNotNull(flow.level)
        val kept = saved.keys().map { saved.get<Any>(it) }
        assertTrue("its folders kept", kept.none { value -> value is List<*> && value.any { it == shown.folders.first() } })

        network.asked.clear()
        val again = FolderFlow(network, library, scope, useFolders = { false }, saved = saved.afterProcessDeath())
        until { again.ready }
        assertEquals(FlowStep.BROWSE, again.step)
        assertEquals(shown, again.level)
        assertTrue("read again: ${network.asked}", "level volume1/video/Films2" in network.asked)

        network.folders.remove("volume1/video/Films2")
        val gone = FolderFlow(network, library, scope, useFolders = { false }, saved = saved.afterProcessDeath())
        until { gone.ready }
        assertEquals(FlowStep.SHARES, gone.step)
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
     * SMB after a stopped process, the account kept whose password the Keystore does not open for now: the account step
     * again, its user filled, the sentence under the password, never step 5 whose rows could only fail on it; Back, then
     * OK on the NAS once the Keystore answers, signs in with the password kept.
     */
    @Test
    fun anSmbFlowFoundAgainWithThePasswordKeptNotReadForNowSaysItUnderIt() {
        val saved = SavedStateHandle()
        val kept = flow(smbFilms, saved = saved)
        ok()
        until { kept.step == FlowStep.SHARES }
        kept.openShare(FlowShare("video"))
        until { kept.step == FlowStep.BROWSE }
        // The next launch: the Keystore down, the password kept is not opened.
        runBlocking {
            val folder = db.dao().sources().single()
            db.dao().updateSource(folder.copy(password = sealedForTheNextLaunch(smbFilms).password))
        }
        keystoreDown = true

        val again = FolderFlow(network, library, scope, useFolders = { false }, saved = saved.afterProcessDeath())
        until { again.ready }
        assertEquals(FlowStep.ACCOUNT, again.step)
        assertEquals(FieldError(FIELD_PASSWORD, FlowMessage.PasswordUnread), again.fieldError)
        assertEquals(FIELD_PASSWORD, again.aim?.key)
        assertEquals(USER to "", again.user to again.password)

        keystoreDown = false
        again.back {}
        assertEquals(FlowStep.WHERE, again.step)
        again.chooseUsed(again.used.single())
        until { again.step == FlowStep.SHARES }
        assertTrue("signed in with the password kept", again.signedIn && again.accountKept)
    }
}
