package app.houselights.ui.onboarding

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import app.houselights.providers.jellyfin.AuthRequest
import app.houselights.providers.jellyfin.AuthResult
import app.houselights.providers.jellyfin.DiscoveredServer
import app.houselights.providers.jellyfin.JellyfinNetwork
import app.houselights.providers.jellyfin.PublicSystemInfo
import app.houselights.settings.SourceSwitcher
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.testing.testSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * A server found on the network is the one chosen once the address names it, typed in full or as its host and port:
 * its row is selected and the panel beside names it, found; another address names none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class ServerFoundTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun close() = scope.cancel()

    private object Salon : JellyfinNetwork {
        override suspend fun discover() = listOf(DiscoveredServer("http://192.168.1.20:8096", "Salon"))

        override suspend fun reach(input: String, deviceId: String, announced: Boolean): Pair<String, PublicSystemInfo> =
            throw IOException("offline")

        override suspend fun authenticate(url: String, deviceId: String, request: AuthRequest): AuthResult = throw IOException("offline")
    }

    private fun form(): JellyfinLoginViewModel {
        val vm = runBlocking {
            val settings = testSettings(folder.root, scope)
            JellyfinLoginViewModel(settings, SourceSwitcher({ settings.state.value }, { _, _ -> }, scope, signOut = { _, _ -> }), Salon)
        }
        compose.setTvContent { JellyfinLoginForm(vm) {} }
        compose.waitUntil { vm.discovered.isNotEmpty() }
        return vm
    }

    @Test
    fun theAddressOfAServerFoundChoosesIt() {
        val vm = form()
        compose.onNodeWithText("Server found").assertDoesNotExist()
        vm.server = "192.168.1.20:8096"
        compose.onNodeWithText("Server found").assertExists()
        // Its row, selected, and its name in the panel.
        compose.onAllNodesWithText("Salon").assertCountEquals(2)
        compose.onNode(hasText("Salon") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertExists()
        vm.server = "http://192.168.1.21:8096"
        compose.onNodeWithText("Server found").assertDoesNotExist()
        compose.onNodeWithText("Connection not tested").assertExists()
    }
}
