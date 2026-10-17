package app.houselights.ui.onboarding

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.Secrets
import app.houselights.core.SourceConfig
import app.houselights.providers.jellyfin.AuthRequest
import app.houselights.providers.jellyfin.AuthResult
import app.houselights.providers.jellyfin.DiscoveredServer
import app.houselights.providers.jellyfin.JellyfinNetwork
import app.houselights.providers.jellyfin.PublicSystemInfo
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SecretConverter
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.settings.SourceSwitcher
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.testing.testSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import javax.crypto.KeyGenerator

/**
 * Signing in again, to a server or to a folder's NAS, focus starts on what is left to type: the
 * password, once the user is filled in; the user first, when there is none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = TV)
class SignInFocusTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val db: LibraryDatabase by lazy {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java)
            .addTypeConverter(SecretConverter(Secrets { key }))
            .allowMainThreadQueries()
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
    }

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    /** No server answers: nothing here signs in. */
    private object Offline : JellyfinNetwork {
        override suspend fun discover() = emptyList<DiscoveredServer>()

        override suspend fun reach(input: String, deviceId: String): Pair<String, PublicSystemInfo> = throw IOException("offline")

        override suspend fun authenticate(url: String, deviceId: String, request: AuthRequest): AuthResult = throw IOException("offline")
    }

    /** The sign-in form, signing in again to a server whose user is [userName]. */
    private fun signInAgain(userName: String?) {
        val vm = runBlocking {
            val settings = testSettings(folder.root, scope)
            settings.setSource(SourceConfig.Jellyfin("https://nas:8920", "Maison", "u1", userName, "token"))
            settings.state.first { it.source != null }
            JellyfinLoginViewModel(settings, SourceSwitcher({ settings.state.value }, {}, scope, signOut = { _, _ -> }), Offline)
        }
        compose.setTvContent { JellyfinLoginForm(vm) {} }
        compose.waitForIdle()
    }

    /** The form of a folder on a NAS that refused to sign [user] in, opened to type its password again. */
    private fun folderRefused(user: String) {
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
                    path = "films", username = user, password = Secret(""),
                )
            )
        }
        val vm = AddFolderViewModel(library, id, scope, useFolders = { false }, probe = { 0 })
        compose.setTvContent { AddFolderForm(vm, FolderField.after(ScanErrorKind.LOGIN_FAILED)) {} }
        compose.waitForIdle()
    }

    @Test
    fun aFolderRefusedAsksForItsPassword() {
        folderRefused("kodi")
        compose.onNode(isFocused()).assert(hasContentDescription("Password"))
    }

    /** A guest the NAS refused: a user is to be typed before a password. */
    @Test
    fun aFolderRefusedToAGuestAsksForTheUserFirst() {
        folderRefused("")
        compose.onNode(isFocused()).assert(hasContentDescription("User"))
    }

    @Test
    fun signingInAgainAsksForThePassword() {
        signInAgain("lea")
        compose.onNode(isFocused()).assert(hasContentDescription("Password"))
    }

    @Test
    fun signingInAgainWithoutAUserAsksForTheUserFirst() {
        signInAgain(null)
        compose.onNode(isFocused()).assert(hasContentDescription("User"))
    }
}
