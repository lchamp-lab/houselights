package app.houselights.ui.onboarding

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
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

/** An edited folder the library cannot read: said in the form's place with Réessayer, nothing to save, until read. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class FolderUnreadTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val db: LibraryDatabase by lazy { testDatabase { onCallingThread() } }

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    private fun text(id: Int) = compose.activity.getString(id)

    private fun button(id: Int) = compose.onNode(hasText(text(id)) and hasClickAction())

    /** Renames the folders' table, so reading them fails as a failing database does, or back. */
    private fun folders(readable: Boolean) {
        val (from, to) = if (readable) "sources_away" to "sources" else "sources" to "sources_away"
        db.openHelper.writableDatabase.execSQL("ALTER TABLE $from RENAME TO $to")
    }

    @Test
    fun aFolderThatCannotBeReadSaysSoWithRetryInsteadOfItsForm() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        val idle = CoroutineScope(Job().apply { cancel() })
        val scanner = LibraryScanner(db.dao(), { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
        val library = LocalLibrary(context, db.dao(), scanner, artwork) {}
        val id = runBlocking {
            db.dao().insertSource(
                SourceEntity(
                    name = "films", kind = SourceKind.MOVIES, protocol = Protocol.SMB, host = "192.168.1.10", share = "media",
                    path = "films", username = "kodi", password = Secret("stored"),
                )
            )
        }
        folders(readable = false)
        val vm = AddFolderViewModel(library, id, scope, probe = { FolderLevel(emptyList(), videos = 0) })
        compose.setTvContent { AddFolderForm(vm, FolderField.FIRST) {} }
        compose.waitForIdle()

        compose.onNodeWithText(text(R.string.folder_unread)).assertExists()
        // Its line as every message has one: what failed, and what to do if Réessayer does not help.
        compose.onNodeWithText(text(R.string.error_settings)).assertExists()
        compose.onNodeWithText(text(R.string.folder_not_saved_state)).assertDoesNotExist()
        button(R.string.retry).assertIsFocused()
        button(R.string.save).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.field_host)).assertDoesNotExist()

        folders(readable = true)
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText(text(R.string.folder_unread)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.error_settings)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.field_host)).assertExists()
        assertEquals("192.168.1.10", vm.form.host)
        button(R.string.save).assertExists()
    }
}
