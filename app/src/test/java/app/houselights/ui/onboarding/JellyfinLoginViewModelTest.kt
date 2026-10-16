package app.houselights.ui.onboarding

import app.houselights.core.SourceConfig
import app.houselights.providers.jellyfin.AuthRequest
import app.houselights.providers.jellyfin.AuthResult
import app.houselights.providers.jellyfin.DiscoveredServer
import app.houselights.providers.jellyfin.JellyfinNetwork
import app.houselights.providers.jellyfin.PublicSystemInfo
import app.houselights.providers.jellyfin.UserDto
import app.houselights.settings.SettingsStore
import app.houselights.settings.SourceSwitcher
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.testSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** Signing in to Jellyfin, with a fake network in place of the server. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class JellyfinLoginViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val saved = mutableListOf<SourceConfig>()

    private inner class FakeNetwork : JellyfinNetwork {
        var reachFailure: Exception? = null
        var authFailure: Exception? = null

        override suspend fun discover() = listOf(DiscoveredServer("http://192.168.1.5:8096", "Maison"))

        override suspend fun reach(input: String, deviceId: String): Pair<String, PublicSystemInfo> {
            reachFailure?.let { throw it }
            return "https://nas:8920" to PublicSystemInfo("Maison", "10.10.3")
        }

        override suspend fun authenticate(url: String, deviceId: String, request: AuthRequest): AuthResult {
            authFailure?.let { throw it }
            return AuthResult(UserDto("u1", request.username), "token")
        }
    }

    private val network = FakeNetwork()

    private suspend fun TestScope.login(settings: SettingsStore? = null): JellyfinLoginViewModel {
        val store = settings ?: testSettings(folder.root, backgroundScope)
        val sources = SourceSwitcher({ store.state.value }, { saved += it }, backgroundScope, signOut = { _, _ -> })
        return JellyfinLoginViewModel(store, sources, network)
    }

    @Test
    fun signingInSwitchesToTheServer() = runTest {
        val vm = login()
        advanceUntilIdle()
        assertEquals("Maison", vm.discovered.single().name)
        vm.server = "nas"
        vm.user = " lea "
        vm.password = "secret"
        var done = false
        vm.login { done = true }
        assertTrue(vm.busy)
        settle(vm)
        assertTrue(done)
        assertFalse(vm.busy)
        assertEquals(listOf(SourceConfig.Jellyfin("https://nas:8920", "Maison", "u1", "lea", "token")), saved)
    }

    @Test
    fun signingInAgainFillsInTheServerAndTheUser() = runTest {
        val settings = testSettings(folder.root, backgroundScope)
        settings.setSource(SourceConfig.Jellyfin("https://nas:8920", "Maison", "u1", "lea", "token"))
        settings.state.first { it.source != null }
        val vm = login(settings)
        assertTrue(vm.again)
        assertEquals("https://nas:8920", vm.server)
        assertEquals("lea", vm.user)
    }

    @Test
    fun aRefusedPasswordSaysSo() = runTest {
        network.authFailure = HttpException(Response.error<Unit>(401, "".toResponseBody()))
        assertEquals(LoginError.Refused, failure())
    }

    @Test
    fun nothingAnsweringSaysWhere() = runTest {
        network.reachFailure = IOException("Connection refused")
        assertEquals(LoginError.Unreachable("http://nas:8096"), failure())
    }

    /** Lets signing in finish: the device id is written on DataStore's own threads. */
    private suspend fun TestScope.settle(vm: JellyfinLoginViewModel) {
        advanceUntilIdle()
        while (vm.busy) {
            withContext(Dispatchers.Default) { delay(10) }
            advanceUntilIdle()
        }
    }

    private suspend fun TestScope.failure(): LoginError? {
        val vm = login()
        vm.server = "nas"
        var done = false
        vm.login { done = true }
        settle(vm)
        assertFalse(done)
        assertTrue(saved.isEmpty())
        return vm.error
    }
}
