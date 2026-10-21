package app.houselights.ui.onboarding

import app.houselights.ui.design.TwoPaneDefaults
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
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
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.settings.SourceSwitcher
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.testing.testSettings
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
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

/**
 * The forms on the TV canvas, in French and German (DESIGN.md, Secondary screens): a folder's and Jellyfin's, two
 * servers found, hold whole in the safe area with their actions' focus, their fields [HlSize.field] tall, the form
 * from the top of the safe area beside its column.
 */
@RunWith(RobolectricTestRunner::class)
// Real fonts, so lines break as on the device.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FormLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val db: LibraryDatabase by lazy { testDatabase { onCallingThread() } }

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    @Test
    @Config(qualifiers = "fr-$TV")
    fun aFolderInFrench() = aFolderHolds()

    @Test
    @Config(qualifiers = "de-$TV")
    fun aFolderInGerman() = aFolderHolds()

    @Test
    @Config(qualifiers = "fr-$TV")
    fun jellyfinInFrench() = jellyfinHolds()

    @Test
    @Config(qualifiers = "de-$TV")
    fun jellyfinInGerman() = jellyfinHolds()

    private fun aFolderHolds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        val idle = CoroutineScope(Job().apply { cancel() })
        val scanner = LibraryScanner(db.dao(), { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
        val library = LocalLibrary(context, db.dao(), scanner, artwork) {}
        val vm = AddFolderViewModel(library, null, scope, useFolders = { false }, probe = { 0 })
        compose.setTvContent { AddFolderForm(vm, FolderField.FIRST) {} }
        compose.waitForIdle()
        holds(first = R.string.folder_kind, field = R.string.field_host, action = R.string.add)
    }

    private fun jellyfinHolds() {
        val vm = runBlocking {
            val settings = testSettings(folder.root, scope)
            JellyfinLoginViewModel(settings, SourceSwitcher({ settings.state.value }, {}, scope, signOut = { _, _ -> }), TwoServers)
        }
        compose.setTvContent { JellyfinLoginForm(vm) {} }
        compose.waitForIdle()
        compose.onNodeWithText("Bureau").assertExists()
        holds(first = R.string.servers_found, field = R.string.field_server, action = R.string.sign_in)
    }

    /**
     * The form from the top of the safe area, its [first] words there; its [field] where the grid puts it, [HlSize.field]
     * tall, and its [action] framed inside the safe area.
     */
    private fun holds(first: Int, field: Int, action: Int) {
        fun text(id: Int) = compose.activity.getString(id)
        val top = compose.onNodeWithText(text(first)).getUnclippedBoundsInRoot().top
        assertEquals("the form from the safe area's top: $top", HlSpace.safeV.value, top.value, TOP)
        val box = compose.onNodeWithContentDescription(text(field)).getUnclippedBoundsInRoot()
        assertEquals("$box", HlSize.field.value, box.height.value, PX)
        assertEquals("the form beside its column: $box", (HlSpace.safeH + TwoPaneDefaults.sideWide + HlSpace.xxl).value, box.left.value, PX)
        assertEquals("the form to the safe area: $box", (SCREEN_W - HlSpace.safeH).value, box.right.value, PX)
        val button = compose.onNodeWithText(text(action)).getUnclippedBoundsInRoot()
        val reach = HlFocus.reach
        val framed = DpRect(button.left - reach, button.top - reach, button.right + reach, button.bottom + reach)
        assertTrue("$framed inside the safe area", framed.bottom <= SCREEN_H - HlSpace.safeV && framed.right <= SCREEN_W - HlSpace.safeH)
    }

    /** Two servers answer discovery; none signs in. */
    private object TwoServers : JellyfinNetwork {
        override suspend fun discover() = listOf(
            DiscoveredServer("http://192.168.1.20:8096", "Jellyfin du salon"),
            DiscoveredServer("http://192.168.1.31:8096", "Bureau"),
        )

        override suspend fun reach(input: String, deviceId: String): Pair<String, PublicSystemInfo> = throw IOException("offline")

        override suspend fun authenticate(url: String, deviceId: String, request: AuthRequest): AuthResult = throw IOException("offline")
    }

    private companion object {
        val SCREEN_W = 960.dp
        val SCREEN_H = 540.dp

        /** Bounds are whole test pixels. */
        const val PX = 1f

        /** Words whose box starts at their letters, a group's title set from its baseline. */
        const val TOP = 2f
    }
}
