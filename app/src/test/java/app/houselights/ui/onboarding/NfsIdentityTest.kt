package app.houselights.ui.onboarding

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlSpace
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Who an NFS folder presents itself as, in its form: a folded row under the actions saying its value, OK opening what
 * it changes and its two fields and folding them, the panel saying it when it is not root.
 */
@RunWith(RobolectricTestRunner::class)
// Real fonts, so the form takes its height on the device.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "fr-$TV")
class NfsIdentityTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val db: LibraryDatabase by lazy { testDatabase { onCallingThread() } }

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    /** The form adding a folder or, given [folder], editing it once stored. */
    private fun form(folder: SourceEntity? = null): AddFolderViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        // The scan asked after a save never runs: it would go to the network.
        val idle = CoroutineScope(Job().apply { cancel() })
        val scanner = LibraryScanner(db.dao(), { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
        val library = LocalLibrary(context, db.dao(), scanner, artwork) {}
        val id = folder?.let { runBlocking { db.dao().insertSource(it) } }
        val vm = AddFolderViewModel(library, id, scope, useFolders = { false }, probe = { 0 })
        compose.setTvContent { AddFolderForm(vm, FolderField.FIRST) {} }
        compose.waitForIdle()
        return vm
    }

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun row() = compose.onNode(hasText(text(R.string.nfs_identity_title)) and hasClickAction())

    /** Where the keyboard types. */
    private fun typing() = compose.onNode(isFocused() and hasSetTextAction())

    /** Types [value] into the field named [label], from the remote. */
    private fun type(label: Int, value: String) {
        compose.onNodeWithContentDescription(text(label)).requestFocus()
        compose.press(Key.DirectionCenter)
        typing().performTextInput(value)
    }

    @Test
    fun aNewNfsFolderPresentsItselfAsRootOnAFoldedRowUnderTheActions() {
        form()
        val row = row().getUnclippedBoundsInRoot()
        val actions = compose.onNode(hasText(text(R.string.add)) and hasClickAction()).getUnclippedBoundsInRoot()
        assertTrue("$row under $actions", row.top >= actions.bottom)
        assertTrue("$row framed inside the safe area", row.bottom + HlFocus.reach <= SCREEN_H - HlSpace.safeV)
        compose.onNodeWithText(text(R.string.nfs_identity_root)).assertExists()
        compose.onNodeWithText(text(R.string.nfs_identity_hint)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.field_uid)).assertDoesNotExist()
    }

    /** OK opens the sentence and the fields in view, focus staying on the row; OK again folds them, the values kept. */
    @Test
    fun okOpensTheIdentityAndFoldsItKeepingWhatItHolds() {
        val vm = form()
        row().requestFocus()
        compose.press(Key.DirectionCenter)
        row().assertIsFocused()
        val hint = compose.onNodeWithText(text(R.string.nfs_identity_hint)).getUnclippedBoundsInRoot()
        val gid = compose.onNodeWithContentDescription(text(R.string.field_gid)).getUnclippedBoundsInRoot()
        assertTrue("$hint in view", hint.top >= 0.dp)
        assertTrue("$gid in view", gid.bottom <= SCREEN_H)

        type(R.string.field_uid, "1026")
        typing().performTextInput("a")
        type(R.string.field_gid, "100")
        assertEquals("whole numbers alone", "1026" to "100", vm.form.uid to vm.form.gid)

        row().requestFocus()
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText(text(R.string.nfs_identity_hint)).assertDoesNotExist()
        assertEquals("1026" to "100", vm.form.uid to vm.form.gid)
        // On the row, and among the panel's facts.
        compose.onAllNodesWithText(text(R.string.nfs_identity, "1026", "100")).assertCountEquals(2)
    }

    @Test
    fun anEditedFolderSaysTheIdentityItKeeps() {
        form(FOLDER.copy(uid = 1026, gid = 100))
        compose.onAllNodesWithText(text(R.string.nfs_identity, "1026", "100")).assertCountEquals(2)
        compose.onNodeWithText(text(R.string.nfs_identity_hint)).assertDoesNotExist()
    }

    /** SMB says who reads by its account. */
    @Test
    fun anSmbFolderHasNoIdentity() {
        form(FOLDER.copy(protocol = Protocol.SMB, share = "media", username = "kodi"))
        row().assertDoesNotExist()
    }

    private companion object {
        val SCREEN_H = 540.dp

        val FOLDER = SourceEntity(
            name = "films", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = "192.168.1.20", share = "volume1/media",
            path = "films", username = "", password = Secret(""),
        )
    }
}
