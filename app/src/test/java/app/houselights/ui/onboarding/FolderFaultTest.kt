package app.houselights.ui.onboarding

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.FolderLevel
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.testing.folderNames
import app.houselights.testing.mountFailure
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.afterProcessDeath
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.UiError
import app.houselights.ui.util.wholePath
import com.emc.ecs.nfsclient.mount.MountStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.IOException

/**
 * A folder's form says each failure once, under the field to change, which takes focus, the panel's state saying it by
 * the ring alone; what the test found and what the NAS must allow in the panel; « Terminé » on the last field saves, and
 * Back first returns to the form's start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class FolderFaultTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val db: LibraryDatabase by lazy { testDatabase { onCallingThread() } }

    /** What the form asked of the NAS. */
    private val tested = mutableListOf<String>()

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    /**
     * The form editing [folder] once stored, its test answering [probe], this player at [player], the NAS proposing
     * [nearby] for a path it lacks.
     */
    private fun form(
        folder: SourceEntity,
        player: String? = null,
        probe: suspend (SourceEntity) -> FolderLevel = { FolderLevel(folderNames(12), videos = 3) },
        nearby: suspend (SourceEntity, FolderTrouble) -> FolderSuggestion? = { _, _ -> null },
        onDone: () -> Unit = {},
    ): AddFolderViewModel {
        val vm = model(stored(folder), player, probe, nearby)
        compose.setTvContent { AddFolderForm(vm, FolderField.FIRST) { onDone() } }
        compose.waitForIdle()
        return vm
    }

    /** [folder] in the library: its id. */
    private fun stored(folder: SourceEntity): Long = runBlocking { db.dao().insertSource(folder) }

    /** The form's model for folder [id], as [form] has it, keeping what it keeps in [saved]. */
    private fun model(
        id: Long,
        player: String? = null,
        probe: suspend (SourceEntity) -> FolderLevel = { FolderLevel(folderNames(12), videos = 3) },
        nearby: suspend (SourceEntity, FolderTrouble) -> FolderSuggestion? = { _, _ -> null },
        saved: SavedStateHandle = SavedStateHandle(),
    ): AddFolderViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        // The scan asked after a save never runs: it would go to the network.
        val idle = CoroutineScope(Job().apply { cancel() })
        val scanner = LibraryScanner(db.dao(), { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
        val library = LocalLibrary(context, db.dao(), scanner, artwork) {}
        return AddFolderViewModel(
            library,
            id,
            scope,
            probe = { tried ->
                tested += "${tried.share} ${tried.path}"
                probe(tried)
            },
            saved = saved,
            playerAddress = { player },
            nearby = nearby,
        )
    }

    private val films = SourceEntity(
        name = "Films",
        kind = SourceKind.MOVIES,
        protocol = Protocol.NFS,
        host = "192.168.1.20",
        share = "volume1/video",
        path = "Films",
        username = "",
        password = Secret(""),
    )

    private val series = SourceEntity(
        name = "Séries",
        kind = SourceKind.SHOWS,
        protocol = Protocol.SMB,
        host = "192.168.1.20",
        share = "video",
        path = "Séries",
        username = "houselights",
        password = Secret("stored"),
    )

    private fun saysUnder(field: String, message: String) = compose.onNodeWithContentDescription(field)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, message))

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun whatIsMissingIsSaidUnderItsFieldWhichTakesFocus() {
        val vm = form(films)
        vm.edit { copy(share = "") }
        vm.submit { throw AssertionError("saved without its mount path") }
        compose.waitForIdle()
        val message = "Enter the mount path, for example /volume1/video."
        saysUnder("Mount path", message).assertIsFocused()
        compose.onAllNodesWithText(message).assertCountEquals(1)
        compose.onNodeWithText("Connection not tested").assertExists()
        // The path still to type, as the address is.
        compose.onNodeWithText("…/Films").assertExists()
        assertEquals(emptyList<String>(), tested)
    }

    /** A folder missing in an export that answers: said under « Sous-dossier », once, the state by its ring. */
    @Test
    fun aSubfolderNotFoundIsSaidUnderIt() {
        val vm = form(films, probe = { throw FileNotFoundException("lookup /volume1/video/Films") })
        vm.test()
        compose.waitUntil { vm.check != null }
        compose.waitForIdle()
        // Its path whole on its line, as every path in a sentence ([wholePath]).
        val message = "${wholePath("/volume1/video")} answers, but “Films” is not in it. Check its name, capitals included."
        saysUnder("Subfolder (optional)", message).assertIsFocused()
        compose.onAllNodesWithText(message).assertCountEquals(1)
        compose.onNodeWithText("Cannot connect").assertExists()
    }

    /**
     * A process stopped on the form after a failure: the first failure said once it is back still sends focus under it,
     * the form's count of answers kept as the last one focus went for is.
     */
    @Test
    fun aFailureAfterAStoppedProcessStillTakesFocus() {
        val id = stored(films)
        val missing: suspend (SourceEntity) -> FolderLevel = { throw FileNotFoundException("lookup /volume1/video/Films") }
        val saved = SavedStateHandle()
        var vm = model(id, probe = missing, saved = saved)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { HouselightsTheme { AddFolderForm(vm, FolderField.FIRST) {} } }
        compose.waitUntil { vm.ready }
        vm.test()
        compose.waitUntil(WAIT_MS) { vm.check != null }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(SUBFOLDER).assertIsFocused()

        // A new process: a new model from what the old one kept, the form from what it kept.
        vm = model(id, probe = missing, saved = saved.afterProcessDeath())
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil { vm.ready }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("NAS address").requestFocus()
        compose.waitForIdle()
        vm.test()
        compose.waitUntil(WAIT_MS) { vm.check != null }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(SUBFOLDER).assertIsFocused()
    }

    /**
     * A folder the export lacks comes with what the NAS has within a letter of it, under the actions, named alone, which
     * takes focus; OK writes it in « Sous-dossier » and tests again, focus on Test.
     */
    @Test
    fun whatTheNasProposesTakesFocusAndOkUsesIt() {
        var missing = true
        val vm = form(
            films.copy(path = "Film"),
            probe = {
                if (missing) throw FileNotFoundException("lookup /volume1/video/Film")
                FolderLevel(folderNames(12), videos = 3)
            },
            nearby = { _, _ -> FolderSuggestion("volume1/video", "Films", named = "Films") },
        )
        vm.test()
        compose.waitUntil(WAIT_MS) { vm.check != null }
        compose.waitForIdle()
        compose.onNode(hasText("Use “Films”") and hasClickAction()).assertIsFocused()
        compose.onNodeWithText("Suggested by the NAS").assertExists()

        missing = false
        compose.press(Key.DirectionCenter)
        compose.waitUntil(WAIT_MS) { vm.check is FolderCheck.Reachable }
        compose.waitForIdle()
        assertEquals("Films", vm.form.path)
        assertEquals(listOf("volume1/video Film", "volume1/video Films"), tested)
        compose.onNodeWithText("Suggested by the NAS").assertDoesNotExist()
        compose.onNode(isFocused()).assert(hasText("Test") or hasAnyDescendant(hasText("Test")))
    }

    /** SMB's password refused: said under it, which takes focus, the help kept since the panel has no result. */
    @Test
    fun aPasswordRefusedIsSaidUnderIt() {
        val vm = form(series, probe = { throw IOException("STATUS_LOGON_FAILURE (0xc000006d): Authentication failed") })
        vm.test()
        compose.waitUntil { vm.check != null }
        compose.waitForIdle()
        saysUnder("Password", "The NAS rejected the user name or password.").assertIsFocused()
        val help = "The password is stored on this device and passed to the Zidoo player. Prefer a dedicated read-only account."
        compose.onNodeWithText(help).assertExists()
    }

    /** At rest, the help names this player; a test that answered says what it found, folders and videos apart, and the help goes. */
    @Test
    fun theTestSaysWhatItFoundAndTheHelpGoes() {
        val vm = form(films, player = "192.168.1.42", probe = { FolderLevel(folderNames(412), videos = 3) })
        compose.waitUntil { vm.player != null }
        val hint = "No password: on the NAS, allow this player (192.168.1.42) read-only, and on a Synology non-privileged ports."
        compose.onNodeWithText(hint).assertExists()
        compose.onNodeWithText("/volume1/video/Films").assertExists()
        vm.test()
        compose.waitUntil { vm.check != null }
        compose.waitForIdle()
        compose.onNodeWithText("412 folders and 3 videos").assertExists()
        compose.onNodeWithText("Connected").assertExists()
        compose.onNodeWithText(hint).assertDoesNotExist()
    }

    /** The NAS refusing this player: what to allow, where and for which address, in the panel, focus left on Test. */
    @Test
    fun aRefusalOfThePlayerIsThePanelsAndNamesIt() {
        val vm = form(films, player = "192.168.1.42", probe = { throw mountFailure(MountStatus.MNT3ERR_ACCES) })
        compose.waitUntil { vm.player != null }
        compose.onNode(hasText("Test") and hasClickAction()).requestFocus()
        vm.test()
        compose.waitUntil { vm.check != null }
        compose.waitForIdle()
        val message = "The NAS refuses this player. Allow 192.168.1.42 on ${wholePath("/volume1/video")}, read-only, and on a Synology " +
            "non-privileged ports."
        compose.onAllNodesWithText(message).assertCountEquals(1)
        compose.onNode(isFocused()).assert(hasText("Test") or hasAnyDescendant(hasText("Test")))
    }

    /** One mark a failure: the panel's state says every failure by the ring, the message carrying the mark. */
    @Test
    fun everyFailureIsTheStatesRing() {
        val failures = listOf(
            FolderCheck.Failed(ScanError(ScanErrorKind.UNREACHABLE), "192.168.1.20", FolderTrouble.Unreachable),
            FolderCheck.Overlaps(films, same = true),
            FolderCheck.Overlaps(films, same = false, holds = true),
            FolderCheck.Empty,
            FolderCheck.SaveFailed(UiError.NOT_SAVED),
        )
        failures.forEach { assertEquals("$it", StatusState.Off, connectionState(it, testing = false, gone = false).first) }
        assertEquals(StatusState.Off, connectionState(null, testing = false, gone = true).first)
    }

    /** « Terminé » on the last field does what Save does: SMB's password, NFS's subfolder. */
    @Test
    fun doneOnTheLastFieldSaves() {
        var done = false
        val vm = form(series) { done = true }
        compose.onNodeWithContentDescription("Password").requestFocus()
        compose.press(Key.DirectionCenter)
        compose.onNode(isFocused() and hasSetTextAction()).performImeAction()
        compose.waitUntil(WAIT_MS) { tested.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(listOf("video Séries"), tested)
        // Tested, then saving in the app's scope, as Save does; a test alone never saves.
        assertTrue("saving", vm.saving || done)
    }

    @Test
    fun doneOnAnNfsSubfolderSaves() {
        var done = false
        val vm = form(films) { done = true }
        compose.onNodeWithContentDescription("Subfolder (optional)").requestFocus()
        compose.press(Key.DirectionCenter)
        compose.onNode(isFocused() and hasSetTextAction()).performImeAction()
        compose.waitUntil(WAIT_MS) { tested.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(listOf("volume1/video Films"), tested)
        assertTrue("saving", vm.saving || done)
    }

    /** Back goes in two steps: from a field, to « Protocole » at the form's top; from there, out of the form. */
    @Test
    fun backFirstReturnsToTheStart() {
        form(films)
        compose.onNodeWithContentDescription("NAS address").requestFocus()
        compose.waitForIdle()
        back()
        assertFalse("one Back never leaves from a field", compose.activity.isFinishing)
        compose.onNode(isFocused()).assert(hasText("NFS") or hasAnyDescendant(hasText("NFS")))
        back()
        assertTrue(compose.activity.isFinishing)
    }

    private companion object {
        /** Testing reads the library first, from another thread. */
        const val WAIT_MS = 5_000L

        const val SUBFOLDER = "Subfolder (optional)"
    }
}
