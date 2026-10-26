package app.houselights.ui.onboarding

import android.content.Context
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.FolderLevel
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the form fills in from a stored folder is erased with the keyboard's erase key and typed over, from the remote. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class SecondFolderTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val db: LibraryDatabase by lazy { testDatabase { onCallingThread() } }

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    /** Over a library holding an SMB folder, the form adding another or, if [edit], editing it; focus on [focusOn]. */
    private fun form(focusOn: FolderField, edit: Boolean): AddFolderViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        // The scan asked after a save never runs: it would go to the network.
        val idle = CoroutineScope(Job().apply { cancel() })
        val scanner = LibraryScanner(db.dao(), { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
        val library = LocalLibrary(context, db.dao(), scanner, artwork) {}
        val id = runBlocking {
            db.dao().insertSource(
                SourceEntity(
                    name = "films", kind = SourceKind.MOVIES, protocol = Protocol.SMB, host = "192.168.1.10", share = "media",
                    path = "films", username = "kodi", password = Secret(""),
                )
            )
        }
        val vm = AddFolderViewModel(
            library, id.takeIf { edit }, scope, useFolders = { false }, probe = { FolderLevel(emptyList(), videos = 0) },
        )
        compose.setTvContent { AddFolderForm(vm, focusOn) {} }
        compose.waitForIdle()
        return vm
    }

    /** Where the keyboard types. */
    private fun typing() = compose.onNode(isFocused() and hasSetTextAction())

    @Test
    fun aSecondFoldersShareIsErasedAndTyped() {
        val vm = form(FolderField.FIRST, edit = false)
        // The share the last folder is inside, read after the form showed.
        assertEquals("media", vm.form.share)

        compose.onNodeWithContentDescription("Shared folder").requestFocus()
        compose.press(Key.DirectionCenter)
        compose.press(Key.Backspace, 5)
        assertEquals("", vm.form.share)

        typing().performTextInput("films")
        assertEquals("films", vm.form.share)
    }

    /** Where a folder the NAS no longer answers at opens, its address read after the form showed. */
    @Test
    fun anEditedFoldersAddressIsErasedAndTyped() {
        val vm = form(FolderField.HOST, edit = true)
        compose.onNode(isFocused()).assert(hasContentDescription("NAS address"))

        compose.press(Key.DirectionCenter)
        compose.press(Key.Backspace, "192.168.1.10".length)
        typing().performTextInput("nas.local")
        assertEquals("nas.local", vm.form.host)
    }
}
