package app.houselights.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import app.houselights.core.SecretsUnavailableException
import app.houselights.core.SourceConfig
import app.houselights.providers.jellyfin.AuthRequest
import app.houselights.providers.jellyfin.AuthResult
import app.houselights.providers.jellyfin.DiscoveredServer
import app.houselights.providers.jellyfin.JellyfinNetwork
import app.houselights.providers.jellyfin.PublicSystemInfo
import app.houselights.providers.jellyfin.ServerCheck
import app.houselights.providers.jellyfin.UserDto
import app.houselights.settings.SettingsStore
import app.houselights.settings.SourceSwitcher
import app.houselights.testing.http
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.afterProcessDeath
import app.houselights.ui.testing.fullSettings
import app.houselights.ui.testing.testSettings
import app.houselights.ui.testing.waitFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.security.ProviderException

/** Signing in to Jellyfin, with a fake network in place of the server. */
@RunWith(RobolectricTestRunner::class)
class JellyfinLoginViewModelTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val saved = mutableListOf<SourceConfig>()

    private inner class FakeNetwork : JellyfinNetwork {
        var reachFailure: Exception? = null
        var authFailure: Exception? = null

        /** The server is asked, and answers once this completes. */
        var answer: CompletableDeferred<Unit>? = null
        val asked = CompletableDeferred<Unit>()

        override fun discover() = flowOf(MAISON)

        /** What checking a server found says, once it does, then checking it again. */
        val check = CompletableDeferred<ServerCheck>()
        val again = CompletableDeferred<ServerCheck>()

        /** How many times a server found was checked. */
        var checks = 0

        override suspend fun check(found: DiscoveredServer): ServerCheck = (if (++checks == 1) check else again).await()

        /** Each address asked. */
        val reached = mutableListOf<String>()

        override suspend fun reach(input: String, deviceId: String): Pair<String, PublicSystemInfo> {
            reached += input
            asked.complete(Unit)
            answer?.await()
            reachFailure?.let { throw it }
            return "https://nas:8920" to PublicSystemInfo("Maison", "10.10.3")
        }

        override suspend fun authenticate(url: String, deviceId: String, request: AuthRequest): AuthResult {
            authFailure?.let { throw it }
            return AuthResult(UserDto("u1", request.username), "token")
        }
    }

    private val network = FakeNetwork()

    private suspend fun TestScope.login(
        settings: SettingsStore? = null,
        save: suspend (SourceConfig) -> Unit = { saved += it },
        fill: LoginFill = LoginFill.Current,
        kept: SavedStateHandle = SavedStateHandle(),
    ): JellyfinLoginViewModel {
        val store = settings ?: testSettings(folder.root, backgroundScope)
        val sources = SourceSwitcher({ store.state.value }, { source, _ -> save(source) }, backgroundScope, signOut = { _, _ -> })
        return JellyfinLoginViewModel(store, sources, network, fill, kept)
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

    /** Each server found is checked as soon as it appears, and says so meanwhile. */
    @Test
    fun aServerFoundIsCheckedAsSoonAsItAppears() = runTest {
        val vm = login()
        advanceUntilIdle()
        assertEquals(ServerCheck.Checking, vm.checkOf(MAISON))
        assertEquals(MAISON.address, vm.addressOf(MAISON))
        network.check.complete(ServerCheck.Encrypted(TWIN))
        advanceUntilIdle()
        assertEquals(ServerCheck.Encrypted(TWIN), vm.checkOf(MAISON))
        assertEquals("where signing in reaches it", TWIN, vm.addressOf(MAISON))
    }

    /** Picked « Chiffré », it is reached over HTTPS alone: its address in the field, said in the panel. */
    @Test
    fun aServerPickedEncryptedIsReachedOverHttpsAlone() = runTest {
        network.check.complete(ServerCheck.Encrypted(TWIN))
        val vm = login()
        advanceUntilIdle()
        vm.choose(MAISON)
        assertEquals(TWIN, vm.server)
        assertEquals(ServerCheck.Encrypted(TWIN), vm.pickedCheck)
        vm.login {}
        settle(vm)
        assertEquals(listOf(TWIN), network.reached)
    }

    /** Picked « Non chiffré », it is reached straight at the address it announced, with no new try over HTTPS. */
    @Test
    fun aServerPickedPlainIsReachedAtTheAddressItAnnounced() = runTest {
        network.check.complete(ServerCheck.Plain(MAISON.address))
        val vm = login()
        advanceUntilIdle()
        vm.choose(MAISON)
        assertEquals(MAISON.address, vm.server)
        vm.login {}
        settle(vm)
        assertEquals(listOf(MAISON.address), network.reached)
    }

    /**
     * Picked though it did not answer, it is checked again before anything is sent, its row and the panel saying so,
     * then reached as that check says: its HTTPS address is never skipped for the plain one.
     */
    @Test
    fun aServerPickedSilentIsCheckedAgainBeforeSigningIn() = runTest {
        network.check.complete(ServerCheck.Silent)
        network.authFailure = http(401)
        val vm = login()
        advanceUntilIdle()
        vm.choose(MAISON)
        vm.login {}
        waitFor("checked again") { network.checks == 2 }
        assertEquals(ServerCheck.Checking, vm.checkOf(MAISON))
        assertEquals(ServerCheck.Checking, vm.pickedCheck)
        assertTrue(network.reached.isEmpty())
        network.again.complete(ServerCheck.Encrypted(TWIN))
        settle(vm)
        assertEquals(listOf(TWIN), network.reached)
        assertEquals(TWIN, vm.server)
        assertEquals(ServerCheck.Encrypted(TWIN), vm.pickedCheck)
        // Signing in again follows that check, without another.
        vm.login {}
        settle(vm)
        assertEquals(listOf(TWIN, TWIN), network.reached)
        assertEquals(2, network.checks)
    }

    /** Still silent once checked again, it is tried once at the address it announced, which names a failure. */
    @Test
    fun aServerStillSilentIsTriedAtTheAddressItAnnounced() = runTest {
        network.check.complete(ServerCheck.Silent)
        network.again.complete(ServerCheck.Silent)
        network.reachFailure = IOException("Connection refused")
        val vm = login()
        advanceUntilIdle()
        vm.choose(MAISON)
        assertEquals(MAISON.address, vm.server)
        vm.login {}
        settle(vm)
        assertEquals(2, network.checks)
        assertEquals(listOf(MAISON.address), network.reached)
        assertEquals(LoginError.Unreachable(MAISON.address), vm.error)
    }

    /** Picked while checked: its address without a protocol, then the one the check verified. */
    @Test
    fun aServerPickedWhileCheckedTakesTheVerifiedAddressOnceChecked() = runTest {
        val vm = login()
        advanceUntilIdle()
        vm.choose(MAISON)
        assertEquals("192.168.1.5:8096", vm.server)
        assertEquals(ServerCheck.Checking, vm.pickedCheck)
        network.check.complete(ServerCheck.Encrypted(TWIN))
        advanceUntilIdle()
        assertEquals(TWIN, vm.server)
        assertEquals(ServerCheck.Encrypted(TWIN), vm.pickedCheck)
    }

    /** An address edited meanwhile is the user's: the check leaves it, and signing in reaches it as typed. */
    @Test
    fun anAddressEditedWhileCheckedIsKept() = runTest {
        val vm = login()
        advanceUntilIdle()
        vm.choose(MAISON)
        vm.server = "http://192.168.1.5:8096"
        network.check.complete(ServerCheck.Encrypted(TWIN))
        advanceUntilIdle()
        assertEquals("http://192.168.1.5:8096", vm.server)
        assertNull(vm.pickedCheck)
        vm.login {}
        settle(vm)
        assertEquals("a typed http:// is honoured", listOf("http://192.168.1.5:8096"), network.reached)
    }

    /** Signing in while the server picked is checked waits for the check, then follows it, and says what failed. */
    @Test
    fun signingInWhileCheckedFollowsTheCheck() = runTest {
        network.reachFailure = IOException("Connection refused")
        val settings = testSettings(folder.root, backgroundScope)
        val vm = login(settings)
        advanceUntilIdle()
        vm.choose(MAISON)
        vm.login {}
        waitFor("the device's id saved") { settings.state.value.deviceId.isNotEmpty() }
        assertTrue(vm.busy)
        assertTrue(network.reached.isEmpty())
        network.check.complete(ServerCheck.Plain(MAISON.address))
        settle(vm)
        assertEquals(listOf(MAISON.address), network.reached)
        assertEquals(LoginError.Unreachable(MAISON.address), vm.error)
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

    /** A kept server that no longer takes its token: its address and account are filled in, only the password asked. */
    @Test
    fun aServerThatRefusedItsTokenFillsInItsAddressAndAccount() = runTest {
        val vm = login(fill = LoginFill.Server("https://nas:8920", "lea"))
        assertTrue(vm.again)
        assertEquals("https://nas:8920", vm.server)
        assertEquals("lea", vm.user)
    }

    @Test
    fun anotherServerStartsEmptyEvenFromAServer() = runTest {
        val settings = testSettings(folder.root, backgroundScope)
        settings.setSource(SourceConfig.Jellyfin("https://nas:8920", "Maison", "u1", "lea", "token"))
        settings.state.first { it.source != null }
        val vm = login(settings, fill = LoginFill.Blank)
        assertFalse(vm.again)
        assertEquals("", vm.server)
        assertEquals("", vm.user)
    }

    /**
     * The address and the user typed outlive a process Android stops while the password is looked up in another app,
     * and come before what the form starts with; the password never does.
     */
    @Test
    fun theAddressAndUserTypedOutliveAStoppedProcess() = runTest {
        val kept = SavedStateHandle()
        val typed = login(fill = LoginFill.Blank, kept = kept)
        typed.server = "192.168.1.20:8096"
        typed.user = "luc"
        typed.password = "secret"

        val again = login(fill = LoginFill.Blank, kept = kept.afterProcessDeath())
        assertEquals("192.168.1.20:8096" to "luc", again.server to again.user)
        assertEquals("", again.password)
        assertTrue("no password kept", kept.keys().none { kept.get<Any>(it) == "secret" })

        val known = login(fill = LoginFill.Server("https://nas:8920", "lea"), kept = kept.afterProcessDeath())
        assertEquals("192.168.1.20:8096" to "luc", known.server to known.user)
    }

    @Test
    fun aRefusedPasswordSaysSo() = runTest {
        network.authFailure = http(401)
        assertEquals(LoginError.Refused, failure())
    }

    @Test
    fun nothingAnsweringSaysWhere() = runTest {
        network.reachFailure = IOException("Connection refused")
        assertEquals(LoginError.Unreachable("http://nas:8096"), failure())
    }

    @Test
    fun whatSigningInSaidGoesWhenWhatItUsedChanges() = runTest {
        network.reachFailure = IOException("Connection refused")
        val vm = login()
        vm.server = "nas"
        val changes = listOf<JellyfinLoginViewModel.() -> Unit>({ server = "nas2" }, { user = "lea" }, { password = "other" })
        changes.forEach { change ->
            vm.login {}
            settle(vm)
            assertTrue(vm.error is LoginError.Unreachable)
            vm.change()
            assertNull(vm.error)
        }
    }

    @Test
    fun whatSigningInSaysOfFieldsChangedMeanwhileIsNotSaid() = runTest {
        network.reachFailure = IOException("Connection refused")
        network.answer = CompletableDeferred()
        val vm = login()
        vm.server = "nas"
        vm.login {}
        waitFor("the server asked") { network.asked.isCompleted }
        vm.server = "nas2"
        network.answer?.complete(Unit)
        settle(vm)
        assertNull(vm.error)
    }

    /** The server took the password, but the Keystore cannot seal the token: the device's fault. */
    @Test
    fun aKeystoreThatCannotSealTheTokenSaysSo() {
        val e = SecretsUnavailableException(ProviderException("Keystore operation failed"))
        assertEquals(LoginError.Keystore, loginError(e, "https://nas:8920"))
    }

    /** The server took the password, but the device cannot save it: the device's fault, not the server's. */
    @Test
    fun aServerThatCannotBeSavedSaysSo() = runTest {
        // One settings file: a second DataStore on it would never read.
        val settings = testSettings(folder.root, backgroundScope)
        assertEquals(LoginError.NotSaved, failure(settings) { throw IOException("No space left on device") })
        val keystore = SecretsUnavailableException(ProviderException("Keystore operation failed"))
        assertEquals(LoginError.Keystore, failure(settings) { throw keystore })
    }

    /** The device's id cannot be written before the server is asked: the device's fault too. */
    @Test
    fun aDeviceIdThatCannotBeSavedSaysSo() = runTest {
        assertEquals(LoginError.NotSaved, failure(fullSettings(backgroundScope)))
    }

    /** Lets signing in finish: the device id is written on DataStore's own threads. */
    private suspend fun TestScope.settle(vm: JellyfinLoginViewModel) = waitFor("signing in finished") { !vm.busy }

    private suspend fun TestScope.failure(
        settings: SettingsStore? = null,
        save: suspend (SourceConfig) -> Unit = { saved += it },
    ): LoginError? {
        val vm = login(settings, save)
        vm.server = "nas"
        var done = false
        vm.login { done = true }
        settle(vm)
        assertFalse(done)
        assertTrue(saved.isEmpty())
        return vm.error
    }

    private companion object {
        val MAISON = DiscoveredServer("http://192.168.1.5:8096", "Maison", "s1")

        /** Its HTTPS address, on Jellyfin's port. */
        const val TWIN = "https://192.168.1.5:8920"
    }
}
