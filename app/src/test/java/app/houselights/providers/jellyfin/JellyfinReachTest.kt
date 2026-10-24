package app.houselights.providers.jellyfin

import app.houselights.testing.Answer
import app.houselights.testing.FakeServer
import app.houselights.testing.certificateRefused
import app.houselights.ui.onboarding.LoginError
import app.houselights.ui.onboarding.loginError
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ConnectException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Reaching the server typed when signing in: which address, which failure, which server. */
@RunWith(RobolectricTestRunner::class)
class JellyfinReachTest {
    private fun info(version: String, product: String? = "Jellyfin Server", id: String = "s1") =
        """{"ServerName":"Maison","Version":"$version","Id":"$id"${product?.let { ",\"ProductName\":\"$it\"" }.orEmpty()}}"""

    /** What signing in at [typed] says, against [server]. */
    private suspend fun signIn(typed: String, server: FakeServer): LoginError? = try {
        JellyfinClient.reach(typed, "device", server.http)
        null
    } catch (e: Exception) {
        loginError(e, JellyfinClient.normalizeUrl(typed))
    }

    @Test
    fun aServerOfNowIsReachedEncryptedFirst() = runTest {
        val server = FakeServer { Answer(200, info("10.10.3")) }
        val (url, found) = JellyfinClient.reach("nas", "device", server.http)
        assertEquals("https://nas:8920", url)
        assertEquals("Maison", found.serverName)
    }

    @Test
    fun anAddressIsCheckedBeforeTheNetwork() = runTest {
        val server = FakeServer { Answer(200, info("10.10.3")) }
        assertEquals(LoginError.NoAddress, signIn("  ", server))
        assertEquals(LoginError.InvalidAddress("nas salon"), signIn("nas salon", server))
        assertEquals(LoginError.InvalidAddress("nas:80960"), signIn("nas:80960", server))
        assertTrue(server.requests.isEmpty())
    }

    /** A server on HTTPS alone with an expired certificate: not "nothing answers at http://nas:8096". */
    @Test
    fun aRefusedCertificateIsNotHiddenByThePlainAddress() = runTest {
        val server = FakeServer { request ->
            if (request.url.isHttps) throw certificateRefused() else throw ConnectException("Connection refused")
        }
        assertEquals(LoginError.Certificate("https://nas:8920"), signIn("nas", server))
    }

    /** A self-signed certificate on 8920 and HTTP on 8096: the password never goes to the plain address unasked. */
    @Test
    fun aRefusedCertificateStopsBeforeThePlainAddress() = runTest {
        val server = FakeServer { request ->
            if (request.url.isHttps) throw certificateRefused() else Answer(200, info("10.10.3"))
        }
        assertEquals(LoginError.Certificate("https://nas:8920"), signIn("nas", server))
        assertTrue(server.requests.all { it.url.isHttps })
    }

    @Test
    fun withNothingAnsweringThePlainAddressIsNamed() = runTest {
        val server = FakeServer { throw ConnectException("Connection refused") }
        assertEquals(LoginError.Unreachable("http://nas:8096"), signIn("nas", server))
    }

    /** "nas:8096" tries TLS on Jellyfin's plain port: that failure says less than the answer over HTTP. */
    @Test
    fun anAnswerSaysMoreThanTlsOnAPlainPort() = runTest {
        val server = FakeServer { request ->
            if (request.url.isHttps) throw SSLException("Unrecognized SSL message, plaintext connection?") else Answer(200, "<html>")
        }
        assertEquals(LoginError.NotJellyfin("http://nas:8096"), signIn("nas:8096", server))
    }

    /** A found server announces plain HTTP unless it requires HTTPS: its HTTPS port is checked first, briefly. */
    @Test
    fun aFoundServerIsEncryptedWhenItsHttpsAddressAnswersAsItself() = runBlocking {
        val server = FakeServer { Answer(200, info("10.10.3")) }
        assertEquals(ServerCheck.Encrypted(TWIN), JellyfinClient.check(FOUND, server.http))
        assertEquals("a short wait", 3_000, server.connectTimeouts.getValue("$TWIN/System/Info/Public"))
        assertTrue(server.requests.all { it.url.isHttps })
    }

    /** Another service on 8920, or another Jellyfin: not the server found, which is then reached as announced. */
    @Test
    fun anotherServerOnItsHttpsPortLeavesItPlain() = runBlocking {
        val server = FakeServer { request -> Answer(200, if (request.url.isHttps) info("10.10.3", id = "other") else info("10.10.3")) }
        assertEquals(ServerCheck.Plain(ANNOUNCED), JellyfinClient.check(FOUND, server.http))
        assertEquals(ServerCheck.Plain(ANNOUNCED), JellyfinClient.check(FOUND.copy(id = null), server.http))
    }

    /** Its certificate names a domain, not this address, or signs itself: plain, as the connection will be. */
    @Test
    fun aFoundServerWhoseCertificateIsRefusedIsPlain() = runBlocking {
        val server = FakeServer { request -> if (request.url.isHttps) throw certificateRefused() else Answer(200, info("10.10.3")) }
        assertEquals(ServerCheck.Plain(ANNOUNCED), JellyfinClient.check(FOUND, server.http))
        assertEquals("as briefly", 3_000, server.connectTimeouts.getValue("$ANNOUNCED/System/Info/Public"))
    }

    @Test
    fun aFoundServerAnsweringNeitherIsSilent() = runBlocking {
        val server = FakeServer { request ->
            if (request.url.isHttps) throw certificateRefused() else throw ConnectException("Connection refused")
        }
        assertEquals(ServerCheck.Silent, JellyfinClient.check(FOUND, server.http))
        assertEquals(ServerCheck.Silent, JellyfinClient.check(FOUND.copy(address = "nas salon"), server.http))
    }

    /** A server announcing its HTTPS address, on its own name, is checked there alone; elsewhere, on Jellyfin's HTTPS port. */
    @Test
    fun aFoundServerIsCheckedAtTheHttpsAddressItAnnouncesElseOnPort8920() = runBlocking {
        val server = FakeServer { Answer(200, info("10.10.3")) }
        val named = "https://jellyfin.example.org"
        assertEquals(ServerCheck.Encrypted(named), JellyfinClient.check(FOUND.copy(address = named), server.http))
        val otherPort = FOUND.copy(address = "http://192.168.1.5:9000/jellyfin")
        assertEquals(ServerCheck.Encrypted("https://192.168.1.5:8920/jellyfin"), JellyfinClient.check(otherPort, server.http))
        assertTrue(server.requests.all { it.url.isHttps })
    }

    /**
     * A server that stalls, as a name looked up in vain does, is given up on once the wait is over, each address:
     * OkHttp's own call timeout cannot cut a lookup short. Checks are timed on the clock, so their tests run on it.
     */
    @Test
    fun aFoundServerThatStallsIsGivenUpOnInTime() = runBlocking {
        val server = FakeServer { Thread.sleep(STALL_MS); Answer(200, info("10.10.3")) }
        val start = System.nanoTime()
        assertEquals(ServerCheck.Silent, JellyfinClient.check(FOUND, server.http, waitMs = 100))
        val took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("$took ms for both addresses", took < STALL_MS)
    }

    /** Asked anonymously: the device's name and id go to the server signed in to, not to whatever answered. */
    @Test
    fun aFoundServerIsCheckedAnonymously() = runBlocking {
        val server = FakeServer { Answer(200, info("10.10.3")) }
        JellyfinClient.check(FOUND, server.http)
        assertTrue(server.requests.none { it.header("Authorization") != null || it.header("X-Emby-Authorization") != null })
    }

    /** What a check found is reached as written: HTTPS alone once encrypted, the announced address once plain. */
    @Test
    fun anAddressTypedWithItsSchemeIsReachedAsWritten() = runTest {
        val silent = FakeServer { throw ConnectException("Connection refused") }
        val failure = runCatching { JellyfinClient.reach(TWIN, "device", silent.http) }.exceptionOrNull()
        assertEquals(LoginError.Unreachable(TWIN), failure?.let { loginError(it, TWIN) })
        assertTrue(silent.requests.all { it.url.isHttps })
        val server = FakeServer { Answer(200, info("10.10.3")) }
        assertEquals(ANNOUNCED, JellyfinClient.reach(ANNOUNCED, "device", server.http).first)
        assertTrue(server.requests.none { it.url.isHttps })
    }

    @Test
    fun theEncryptedProbeWaitsLessThanThePlainAddress() = runTest {
        val server = FakeServer { request ->
            if (request.url.isHttps) throw ConnectException("Timed out") else Answer(200, info("10.9.11"))
        }
        JellyfinClient.reach("nas", "device", server.http)
        val timeouts = server.connectTimeouts
        assertEquals(3_000, timeouts.entries.single { it.key.startsWith("https") }.value)
        assertEquals(10_000, timeouts.entries.single { it.key.startsWith("http:") }.value)
    }

    @Test
    fun aJellyfinBefore109OrAnotherProductIsRefusedByItsNameAndVersion() = runTest {
        val old = FakeServer { Answer(200, info("10.8.13")) }
        assertEquals(LoginError.Unsupported("https://nas:8920", "Jellyfin Server", "10.8.13"), signIn("nas", old))
        val emby = FakeServer { Answer(200, info("4.8.10.0", product = "Emby Server")) }
        assertEquals(LoginError.Unsupported("https://nas:8920", "Emby Server", "4.8.10.0"), signIn("nas", emby))
    }

    @Test
    fun jellyfinFrom109OnIsSupportedAndAnUnclearAnswerIsNotRefused() {
        fun supported(version: String?, product: String? = "Jellyfin Server") =
            JellyfinClient.isSupported(PublicSystemInfo(version = version, productName = product))
        assertTrue(supported("10.9.0"))
        assertTrue(supported("10.10.7"))
        assertTrue(supported("11.0.0"))
        assertFalse(supported("10.8.13"))
        assertFalse(supported("9.11.0"))
        assertFalse(supported("10.11.0", product = "Emby Server"))
        assertTrue(supported(null, product = null))
        assertTrue(supported("unstable"))
    }

    private companion object {
        /** What a server on the network says it is at, unless it requires HTTPS. */
        const val ANNOUNCED = "http://192.168.1.5:8096"

        /** Its HTTPS address, on Jellyfin's port. */
        const val TWIN = "https://192.168.1.5:8920"

        /** Longer than a server found is waited for. */
        const val STALL_MS = 2_000L

        /** The server as discovery found it; its id is the one its public info gives. */
        val FOUND = DiscoveredServer(ANNOUNCED, "Maison", "s1")
    }
}
