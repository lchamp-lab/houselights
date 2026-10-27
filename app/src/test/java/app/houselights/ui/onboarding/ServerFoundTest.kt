package app.houselights.ui.onboarding

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import app.houselights.providers.jellyfin.AuthRequest
import app.houselights.providers.jellyfin.AuthResult
import app.houselights.providers.jellyfin.DiscoveredServer
import app.houselights.providers.jellyfin.JellyfinNetwork
import app.houselights.providers.jellyfin.PublicSystemInfo
import app.houselights.providers.jellyfin.ServerCheck
import app.houselights.settings.SourceSwitcher
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.testing.testSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.asFlow
import org.junit.After
import org.junit.Assert.assertEquals
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

    /** [servers] answer discovery, each checked as [checks] says by its address, else checked for ever; none signs in. */
    private class Found(private val servers: List<DiscoveredServer>, private val checks: Map<String, ServerCheck>) : JellyfinNetwork {
        override fun discover() = servers.asFlow()

        override suspend fun check(found: DiscoveredServer): ServerCheck = checks[found.address] ?: awaitCancellation()

        override suspend fun reach(input: String, deviceId: String): Pair<String, PublicSystemInfo> = throw IOException("offline")

        override suspend fun authenticate(url: String, deviceId: String, request: AuthRequest): AuthResult = throw IOException("offline")
    }

    private fun form(network: JellyfinNetwork = Found(listOf(SALON), emptyMap())): JellyfinLoginViewModel {
        val vm = runBlocking {
            val settings = testSettings(folder.root, scope)
            JellyfinLoginViewModel(settings, SourceSwitcher({ settings.state.value }, { _, _ -> }, scope, signOut = { _, _ -> }), network)
        }
        compose.setTvContent { JellyfinLoginForm(vm) {} }
        compose.waitUntil { vm.discovered.isNotEmpty() }
        return vm
    }

    /** The four servers of the board, as their checks left them. */
    private fun fourServers() = form(
        Found(
            listOf(SALON, BUREAU, CHAMBRE, ANCIEN),
            mapOf(
                SALON.address to ServerCheck.Encrypted("https://192.168.1.20:8920"),
                BUREAU.address to ServerCheck.Plain(BUREAU.address),
                ANCIEN.address to ServerCheck.Silent,
            ),
        ),
    )

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

    /** Each row says how its server will be reached, where: encrypted on its HTTPS port, plain, checking, no answer. */
    @Test
    fun eachServerFoundSaysHowItWillBeReached() {
        val vm = fourServers()
        compose.waitUntil { vm.checkOf(SALON) != ServerCheck.Checking }
        compose.onNodeWithText("192.168.1.20:8920", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("192.168.1.20:8096", useUnmergedTree = true).assertDoesNotExist()
        val states = listOf("Encrypted", "Not encrypted", "Checking…", "Not responding")
        val starts = states.map { compose.onNodeWithText(it, useUnmergedTree = true).getUnclippedBoundsInRoot().left }
        assertEquals("one column: $starts", 1, starts.toSet().size)
    }

    /** The server picked: its verified protocol and connection in the panel, its address in the field, its row checked in place. */
    @Test
    fun theServerPickedSaysItsConnection() {
        val vm = fourServers()
        compose.waitUntil { vm.checkOf(BUREAU) != ServerCheck.Checking }
        fun start(state: String) = compose.onNodeWithText(state, useUnmergedTree = true).getUnclippedBoundsInRoot().left
        val before = start("Encrypted")
        vm.choose(SALON)
        compose.onNodeWithText("HTTPS").assertExists()
        compose.onNodeWithText("Encrypted connection").assertExists()
        assertEquals("https://192.168.1.20:8920", vm.server)
        assertEquals("the check's place is kept", before, start("Encrypted"))
        vm.choose(BUREAU)
        compose.onNodeWithText("HTTP").assertExists()
        compose.onNodeWithText("Unencrypted connection").assertExists()
        compose.onNodeWithText("Encrypted connection").assertDoesNotExist()
    }

    /** Picked while checked: no protocol printed, « Vérification… » in its place, the address without one in the field. */
    @Test
    fun aServerPickedWhileCheckedSaysSo() {
        val vm = fourServers()
        vm.choose(CHAMBRE)
        compose.waitForIdle()
        assertEquals("192.168.1.42:8096", vm.server)
        compose.onAllNodesWithText("Checking…", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("HTTP").assertDoesNotExist()
        compose.onNodeWithText("HTTPS").assertDoesNotExist()
    }

    /** One announcing its HTTPS address, picked while checked: chosen all the same, its address without a protocol. */
    @Test
    fun aServerAnnouncingHttpsPickedWhileCheckedIsChosen() {
        val named = DiscoveredServer("https://jellyfin.example.org", "Salon", "a")
        val vm = form(Found(listOf(named), emptyMap()))
        vm.choose(named)
        compose.waitForIdle()
        assertEquals("jellyfin.example.org", vm.server)
        compose.onNodeWithText("Server found").assertExists()
        compose.onAllNodesWithText("Salon").assertCountEquals(2)
        compose.onNode(hasText("Salon") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertExists()
    }

    private companion object {
        val SALON = DiscoveredServer("http://192.168.1.20:8096", "Salon", "a")
        val BUREAU = DiscoveredServer("http://192.168.1.31:8096", "NAS du bureau", "b")
        val CHAMBRE = DiscoveredServer("http://192.168.1.42:8096", "Chambre", "c")
        val ANCIEN = DiscoveredServer("http://192.168.1.57:8096", "Ancien NAS", "d")
    }
}
