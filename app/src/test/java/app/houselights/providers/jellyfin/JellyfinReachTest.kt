package app.houselights.providers.jellyfin

import app.houselights.testing.certificateRefused
import app.houselights.testing.http
import app.houselights.ui.onboarding.LoginError
import app.houselights.ui.onboarding.loginError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.ConnectException
import javax.net.ssl.SSLException

/** Reaching the server typed when signing in: which address, which failure, which server. */
@RunWith(RobolectricTestRunner::class)
class JellyfinReachTest {
    private fun info(version: String, product: String? = "Jellyfin Server") =
        """{"ServerName":"Maison","Version":"$version","Id":"s1"${product?.let { ",\"ProductName\":\"$it\"" }.orEmpty()}}"""

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
    fun aJellyfinBefore109IsRefusedWithItsVersion() = runTest {
        val server = FakeServer { Answer(200, info("10.8.13")) }
        assertEquals(LoginError.Unsupported("https://nas:8920", "Jellyfin Server", "10.8.13"), signIn("nas", server))
    }

    @Test
    fun anotherProductIsRefusedByName() = runTest {
        val server = FakeServer { Answer(200, info("4.8.10.0", product = "Emby Server")) }
        assertEquals(LoginError.Unsupported("https://nas:8920", "Emby Server", "4.8.10.0"), signIn("nas", server))
    }

    @Test
    fun versions() {
        fun supported(version: String?, product: String? = "Jellyfin Server") =
            JellyfinClient.isSupported(PublicSystemInfo(version = version, productName = product))
        assertTrue(supported("10.9.0"))
        assertTrue(supported("10.10.7"))
        assertTrue(supported("11.0.0"))
        assertTrue(!supported("10.8.13"))
        assertTrue(!supported("9.11.0"))
        assertTrue(!supported("10.11.0", product = "Emby Server"))
        // An unclear answer is no reason to refuse.
        assertTrue(supported(null, product = null))
        assertTrue(supported("unstable"))
    }

    @Test
    fun signingInFailures() {
        assertEquals(LoginError.Refused, loginError(http(401), "https://nas:8920"))
        assertEquals(LoginError.Unreachable("https://nas:8920"), loginError(IOException("Reset"), "https://nas:8920"))
        assertEquals(LoginError.Failed, loginError(IllegalStateException("Odd"), "https://nas:8920"))
    }
}
