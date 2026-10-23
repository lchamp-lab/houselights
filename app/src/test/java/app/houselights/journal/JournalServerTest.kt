package app.houselights.journal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/**
 * The journal's server, on the loopback for the test: the page and its text at the address with its code, nothing
 * anywhere else; one method; the page gone once closed or once its time is up.
 */
class JournalServerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val now = AtomicLong(1_000_000)
    private val lines = listOf(
        "10-23 10:40:02.100  4321  4350 I Playback: Launch smb://•••@nas/series/MobLand/S01E02.mkv",
        "10-23 10:42:11.123  4321  4350 W Trakt   : <b>unreadable</b> answer & more",
    )
    private val head = JournalHead(listOf("Houselights 0.3.0", "ZIDOO Z1000 Pro"), "23/10/2026 10:42")
    private val words = JournalWords("fr", "Journal", "Les dernières lignes.", "Copier", "Copié", "Télécharger", "Aucune ligne.")
    private val content = object : JournalContent {
        override suspend fun page(download: String, nonce: String) = JournalPage.html(head, lines, words, download, nonce)

        override suspend fun text() = JournalPage.text(head, lines)
    }

    @After
    fun stop() = scope.cancel()

    /** A server serving in [scope], and its address once open. */
    private fun open(): Pair<Job, URI> {
        val address = CompletableDeferred<String>()
        val server = JournalServer(InetAddress.getLoopbackAddress(), content, clock = now::get, ports = listOf(0))
        val job = scope.launch { server.serve { address.complete(it) } }
        return job to URI(runBlocking { withTimeout(WAIT_MS) { address.await() } })
    }

    @Test
    fun theAddressHoldsACodeNoOneGuesses() {
        val (_, uri) = open()
        assertEquals("http", uri.scheme)
        val code = uri.path.removePrefix(JournalServer.PATH)
        assertTrue(uri.path, uri.path.startsWith(JournalServer.PATH))
        assertTrue(code, code.matches(Regex("[a-z2-7]{20}")))
        val (_, other) = open()
        assertTrue("each opening draws its own", other.path != uri.path)
    }

    @Test
    fun itsCodeOpensThePageWithItsLinesMaskedAndEscaped() {
        val (_, uri) = open()
        val reply = get(uri, uri.path)
        assertEquals(200, reply.status)
        assertEquals("text/html; charset=utf-8", reply.headers["content-type"])
        assertTrue(reply.body, reply.body.contains("smb://<span class=\"m\">•••</span>@nas/series/MobLand/S01E02.mkv"))
        assertTrue(reply.body, reply.body.contains("&lt;b&gt;unreadable&lt;/b&gt; answer &amp; more"))
        assertFalse(reply.body, reply.body.contains("<b>unreadable"))
        // The page lives nowhere else, keeps no copy and runs nothing it did not bring.
        assertEquals("no-store", reply.headers["cache-control"])
        assertEquals("nosniff", reply.headers["x-content-type-options"])
        assertEquals("no-referrer", reply.headers["referrer-policy"])
        assertEquals("DENY", reply.headers["x-frame-options"])
        assertEquals("close", reply.headers["connection"])
        val policy = reply.headers.getValue("content-security-policy")
        val nonce = Regex("'nonce-([^']+)'").find(policy)?.groupValues?.get(1)
        assertTrue(policy, policy.startsWith("default-src 'none'; style-src 'nonce-$nonce'; script-src 'nonce-$nonce'"))
        assertTrue(reply.body.contains("<script nonce=\"$nonce\">"))
    }

    @Test
    fun itsTextIsDownloaded() {
        val (_, uri) = open()
        val reply = get(uri, "${uri.path}.txt")
        assertEquals(200, reply.status)
        assertEquals("text/plain; charset=utf-8", reply.headers["content-type"])
        assertEquals("attachment; filename=\"houselights-journal.txt\"", reply.headers["content-disposition"])
        assertEquals(JournalPage.text(head, lines), reply.body)
    }

    @Test
    fun theAddressInCapitalsOpensItToo() {
        val (_, uri) = open()
        assertEquals(200, get(uri, uri.path.uppercase()).status)
    }

    @Test
    fun anyOtherAddressIsNotFoundAndSaysNothing() {
        val (_, uri) = open()
        val code = uri.path.removePrefix(JournalServer.PATH)
        val wrong = code.dropLast(1) + if (code.last() == 'a') 'b' else 'a'
        listOf("/", "/journal/", "${JournalServer.PATH}$wrong", "${JournalServer.PATH}$code/x", "/favicon.ico", "/$code").forEach {
            val reply = get(uri, it)
            assertEquals(it, 404, reply.status)
            assertEquals(it, "", reply.body)
        }
    }

    @Test
    fun onlyGetIsAnswered() {
        val (_, uri) = open()
        listOf("POST", "PUT", "DELETE", "HEAD").forEach {
            val reply = request(uri, "$it ${uri.path} HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n")
            assertEquals(it, 405, reply.status)
            assertEquals("GET", reply.headers["allow"])
        }
    }

    @Test
    fun aRequestTooLongIsRefused() {
        val (_, uri) = open()
        val reply = request(uri, "GET ${uri.path} HTTP/1.1\r\nHost: x\r\nX-Long: ${"a".repeat(JournalServer.MAX_REQUEST)}\r\n\r\n")
        assertEquals(400, reply.status)
        // The next one is served.
        assertEquals(200, get(uri, uri.path).status)
    }

    @Test
    fun closedThePageAnswersNoMore() {
        val (job, uri) = open()
        assertEquals(200, get(uri, uri.path).status)
        runBlocking { withTimeout(WAIT_MS) { job.cancelAndJoinQuietly() } }
        assertRefused(uri)
    }

    @Test
    fun afterTenMinutesThePageAnswersNoMore() {
        val (job, uri) = open()
        now.addAndGet(JournalServer.LIFETIME_MS - 1)
        assertEquals(200, get(uri, uri.path).status)
        now.addAndGet(1)
        // The request that finds its time up gets nothing, and the server stops.
        assertNull(runCatching { get(uri, uri.path) }.getOrNull()?.status)
        runBlocking { withTimeout(WAIT_MS) { job.join() } }
        assertRefused(uri)
    }

    @Test
    fun theHomeNetworksAddressIsAPrivateIpv4One() {
        fun address(text: String) = InetAddress.getByName(text)
        assertEquals(
            address("192.168.1.53"),
            JournalServer.privateIpv4(listOf(address("::1"), address("127.0.0.1"), address("fe80::1"), address("192.168.1.53"))),
        )
        assertEquals(address("10.0.0.7"), JournalServer.privateIpv4(listOf(address("2a01:e0a::1"), address("10.0.0.7"))))
        assertEquals(address("172.20.1.2"), JournalServer.privateIpv4(listOf(address("172.20.1.2"))))
        // A public address, the loopback or a link-local one is no home network.
        val none = listOf(address("82.64.1.2"), address("127.0.0.1"), address("169.254.3.4"), address("2a01:e0a::1"))
        assertNull(JournalServer.privateIpv4(none))
    }

    private suspend fun Job.cancelAndJoinQuietly() {
        cancel()
        join()
    }

    private fun assertRefused(uri: URI) {
        try {
            Socket(uri.host, uri.port).use { fail("Still listening on ${uri.port}") }
        } catch (_: ConnectException) {
            // Closed.
        }
    }

    /** What the server answered: its status, its headers by lower-case name, its body. */
    private class Reply(val status: Int, val headers: Map<String, String>, val body: String)

    private fun get(uri: URI, path: String) = request(uri, "GET $path HTTP/1.1\r\nHost: ${uri.host}\r\n\r\n")

    private fun request(uri: URI, raw: String): Reply = Socket(uri.host, uri.port).use { socket ->
        socket.soTimeout = WAIT_MS.toInt()
        socket.getOutputStream().apply {
            write(raw.toByteArray())
            flush()
        }
        val answer = socket.getInputStream().readBytes()
        if (answer.isEmpty()) throw IOException("No answer")
        val text = String(answer, Charsets.UTF_8)
        val head = text.substringBefore("\r\n\r\n")
        val headers = head.lines().drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val body = text.substringAfter("\r\n\r\n")
        assertEquals(headers["content-length"]?.toInt(), body.toByteArray().size)
        Reply(head.substringAfter(' ').substringBefore(' ').toInt(), headers, body)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
