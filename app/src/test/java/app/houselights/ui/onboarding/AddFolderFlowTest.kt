package app.houselights.ui.onboarding

import android.os.Looper
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import app.houselights.R
import app.houselights.providers.local.DiskAccess
import app.houselights.providers.local.FoundNas
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.Standing
import app.houselights.providers.local.Volume
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.DS920
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.PLAYER
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.SILENT_NAS
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.T7
import app.houselights.ui.onboarding.FlowKeys.FIELD_HOST
import app.houselights.ui.testing.TV
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.UiError
import app.houselights.ui.util.wholePath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * « Ajouter un dossier » driven from the remote over the board's network (FakeFlowNetwork): the first focus of each step,
 * the search row that becomes the first NAS found and each NAS that takes its place, Back at each level, each row's
 * state and each message where it goes, how each row stands to the library, the add, and the player's disks. The
 * accounts, the paths typed and a stopped process: [FlowAccountPathTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class AddFolderFlowTest : FlowHarness() {
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
        focusIsOn("video")
        assertEquals("1026" to "100", flow.uid to flow.gid)
        ok()
        until { flow.step == FlowStep.BROWSE }
        focusIsOn("Séries")
        ok()
        until { flow.path == listOf("Séries") }
        focusIsOn(text(R.string.choose_folder))
        ok()
        until { flow.step == FlowStep.KIND }
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
        flow.openFolder("Films2")
        until { flow.path == listOf("Films2") }
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
        compose.onNodeWithText("/volume1/video" + FACT_GAP + text(R.string.share_holds_one, "Films"), useUnmergedTree = true).assertExists()
        ok()
        until { flow.step == FlowStep.BROWSE }
        compose.onNode(hasText(text(R.string.folder_holds_added, "Films"))).assertIsNotEnabled()
        compose.onNode(hasText("Films") and hasText(text(R.string.folder_overlaps_state))).assertIsNotEnabled()
        flow.openFolder("Archives 2019")
        until { flow.path == listOf("Archives 2019") }
        focusIsOn(text(R.string.folder_empty_short))
        compose.onNode(hasText(text(R.string.folder_empty_short))).assertIsNotEnabled()
        // OK on it does nothing.
        ok()
        assertEquals(FlowStep.BROWSE, flow.step)
    }

    /** A level holding several folders of the library counts them, on its share's row as on « Choisir ce dossier ». */
    @Test
    fun aLevelHoldingSeveralFoldersOfTheLibraryCountsThem() {
        val flow = flow(films, films.copy(name = "Séries", kind = SourceKind.SHOWS, path = "Séries"))
        val two = context.resources.getQuantityString(R.plurals.share_holds_many, 2, 2)
        ok()
        until { flow.step == FlowStep.SHARES }
        compose.onNodeWithText("/volume1/video" + FACT_GAP + two, useUnmergedTree = true).assertExists()
        ok()
        until { flow.step == FlowStep.BROWSE }
        compose.onNode(hasText(two)).assertIsNotEnabled()
        compose.onNode(hasText("Séries") and hasText(text(R.string.folder_overlaps_state))).assertIsNotEnabled()
    }

    /** A level holding Films and Séries is said, still choosable, focus on its first subfolder; a level's content is counted. */
    @Test
    fun aLevelSaysWhatItHolds() {
        val flow = flow()
        flow.chooseFound(ds920)
        until { flow.step == FlowStep.PROTOCOLS }
        focusIsOn("SMB")
        flow.chooseProtocol(Protocol.NFS)
        until { flow.step == FlowStep.SHARES }
        flow.openShare(FlowShare("/volume1/media"))
        until { flow.step == FlowStep.BROWSE }
        // At an export's root, an empty library: the first subfolder named for either kind.
        focusIsOn("Films")
        compose.onNodeWithText(text(R.string.folder_holds_both), useUnmergedTree = true).assertExists()
        back()
        flow.openShare(FlowShare("/volume1/video"))
        until { flow.step == FlowStep.BROWSE }
        flow.openFolder("Films2")
        until { flow.path == listOf("Films2") }
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
        compose.onNodeWithText(text(R.string.not_allowed), useUnmergedTree = true).assertExists()
        down()
        focusIsOn("photo")
        ok()
        until { flow.message != null }
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
        compose.onNodeWithText(text(R.string.server_silent), useUnmergedTree = true).assertExists()
        fun asked() = network.asked.count { it == "protocols $SILENT_NAS" }
        val probed = asked()
        ok()
        until { flow.message != null }
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
        compose.onNodeWithText(text(R.string.error_nas_unreachable, SILENT_NAS), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.connection_failed), useUnmergedTree = true).assertExists()
        assertNull("one mark: none in the panel", flow.message)
        back()
        assertEquals(FlowStep.WHERE, flow.step)
        focusIsOn(text(R.string.type_address))
        assertEquals("what was typed is kept", SILENT_NAS, flow.typedHost)
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
        ok()
        until { flow.path == listOf("Séries") }
        ok()
        until { flow.step == FlowStep.KIND }
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
        ok()
        until { flow.path == listOf("Séries") }
        ok()
        until { flow.step == FlowStep.KIND }
        runBlocking { db.dao().insertSource(films.copy(name = "video", path = "")) }
        ok()
        until { flow.message != null }
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
        focusIsOn("Films")
        compose.onNodeWithText(text(R.string.on_this_player)).assertExists()
        compose.onNodeWithText("Samsung T7").assertExists()

        back()
        until { flow.step == FlowStep.WHERE }
        focusIsOn("Samsung T7")
        ok()
        until { flow.step == FlowStep.BROWSE }
        ok()
        until { flow.path == listOf("Films") }
        focusIsOn(text(R.string.choose_folder))
        ok()
        until { flow.step == FlowStep.KIND }
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

    /**
     * « Saisir une adresse » while a NAS signs in stops it, as OK on another row does: its answer, coming after, neither
     * takes the flow to its shared folders nor says a failure under the address.
     */
    @Test
    fun typingAnAddressStopsTheNasUnderWay() {
        val flow = flow(films)
        val exports = network.hold("exports")
        ok()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.signing_in), useUnmergedTree = true).assertExists()
        flow.typeAddress()
        compose.waitForIdle()
        assertNull(flow.pressed)
        exports.complete(Unit)
        // What was under way had its chance to go on: it did not.
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        assertEquals(FlowStep.ADDRESS, flow.step)
        assertNull(flow.fieldError)
        assertNull(flow.message)
    }

    /** « Saisir un chemin » while a shared folder opens stops it: the flow stays on the path, which nothing comes to fill. */
    @Test
    fun typingAPathStopsTheSharedFolderOpening() {
        val flow = flow(films)
        network.announce(ds920)
        compose.waitForIdle()
        ok()
        until { flow.step == FlowStep.SHARES }
        val level = network.hold("level")
        ok()
        compose.waitForIdle()
        assertTrue(flow.pressed != null)
        flow.typePath()
        compose.waitForIdle()
        assertNull(flow.pressed)
        level.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        assertEquals(FlowStep.TYPE_PATH, flow.step)
        assertNull(flow.fieldError)
    }
}
