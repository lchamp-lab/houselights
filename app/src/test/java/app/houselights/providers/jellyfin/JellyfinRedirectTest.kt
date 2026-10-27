package app.houselights.providers.jellyfin

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.InetAddress

/** A redirect from the server is followed on its host only: the token and a password never reach another. */
@RunWith(RobolectricTestRunner::class)
class JellyfinRedirectTest {
    private val server = MockWebServer()

    /** Every name leads to the test server, which answers by the host asked for, as two machines would. */
    private val http = OkHttpClient.Builder()
        .dns(
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName(server.hostName))
            },
        )
        .build()

    private val info = """{"ServerName":"Maison","Version":"10.10.3","Id":"s1"}"""

    @After
    fun stop() = server.shutdown()

    private fun at(host: String, path: String = "") = "http://$host:${server.port}$path"

    private val RecordedRequest.host get() = getHeader("Host").orEmpty().substringBefore(':')

    /** [nas]'s answers at nas, Jellyfin's public info anywhere else. */
    private fun serve(nas: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.host == "nas") nas(request) else MockResponse().setBody(info)
        }
    }

    private fun redirect(code: Int, to: String) = MockResponse().setResponseCode(code).setHeader("Location", to)

    /** The hosts asked, in order. */
    private fun asked() = List(server.requestCount) { server.takeRequest().host }

    @Test
    fun theTokenDoesNotFollowARedirectToAnotherHost() {
        serve { redirect(302, at("elsewhere", "/System/Info/Public")) }
        val client = JellyfinClient(at("nas"), "device", token = "t0k3n", http)
        assertThrows(IOException::class.java) { runBlocking { client.api.publicInfo() } }
        assertEquals(listOf("nas"), asked())
    }

    @Test
    fun thePasswordDoesNotFollowARedirectToAnotherHost() {
        serve { redirect(307, at("elsewhere", "/Users/AuthenticateByName")) }
        val client = JellyfinClient(at("nas"), "device", token = null, http)
        assertThrows(IOException::class.java) { runBlocking { client.api.authenticate(AuthRequest("lea", "motdepasse")) } }
        assertEquals(listOf("nas"), asked())
    }

    @Test
    fun aRedirectOnTheServersHostIsFollowedWithTheToken() {
        serve { request ->
            val moved = request.path == "/System/Info/Public"
            if (moved) redirect(301, at("nas", "/jellyfin/System/Info/Public")) else MockResponse().setBody(info)
        }
        val client = JellyfinClient(at("nas"), "device", token = "t0k3n", http)
        assertEquals("Maison", runBlocking { client.api.publicInfo() }.serverName)
        val requests = List(server.requestCount) { server.takeRequest() }
        assertEquals(listOf("/System/Info/Public", "/jellyfin/System/Info/Public"), requests.map { it.path })
        assertTrue(requests.all { "Token=\"t0k3n\"" in it.getHeader("X-Emby-Authorization").orEmpty() })
    }

    @Test
    fun anHttpsServerIsNotLeftForPlainHttp() {
        val https = "https://nas:8920/".toHttpUrl()
        assertTrue(JellyfinClient.staysOn(https, "https://nas:8920/Items".toHttpUrl()))
        assertTrue(JellyfinClient.staysOn(https, "https://nas/Items".toHttpUrl()))
        assertFalse(JellyfinClient.staysOn(https, "http://nas:8096/Items".toHttpUrl()))
        assertTrue(JellyfinClient.staysOn("http://nas:8096/".toHttpUrl(), "https://nas:8920/Items".toHttpUrl()))
        assertFalse(JellyfinClient.staysOn(https, "https://elsewhere/Items".toHttpUrl()))
    }
}
