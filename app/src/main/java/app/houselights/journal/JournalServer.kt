package app.houselights.journal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

/** What the journal's page shows, read again at each request. */
interface JournalContent {
    /** The page, linking to the text at [download]; its style and script carry [nonce]. */
    suspend fun page(download: String, nonce: String): String

    /** The text to download. */
    suspend fun text(): String
}

/**
 * The journal on the home network, for a phone: a small HTTP server on [host], at an address holding a random code
 * (`/journal/<code>`, and its text at `/journal/<code>.txt`), anything else unanswered. It serves one request at a
 * time, each read within [READ_TIMEOUT_MS] and [MAX_REQUEST] bytes, closes each connection, and stops when [serve] is
 * cancelled or after [LIFETIME_MS] by [clock], whichever comes first.
 */
class JournalServer(
    private val host: InetAddress,
    private val content: JournalContent,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    private val random: SecureRandom = SecureRandom(),
    private val ports: List<Int> = listOf(PORT, 0),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Volatile
    private var answering: Socket? = null

    /**
     * Opens the page and tells its address to [opened], then serves it until cancelled or its time is up.
     * Throws an IOException when no port could be had.
     */
    suspend fun serve(opened: (address: String) -> Unit) {
        val socket = withContext(io) { bind() }
        val code = code()
        val deadline = clock() + LIFETIME_MS
        try {
            coroutineScope {
                // Closing the socket ends the accepting below, at the deadline or once serve is cancelled.
                val timer = launch { closeAfter(socket, LIFETIME_MS) }
                launch(io) {
                    acceptAll(socket, code, deadline)
                    timer.cancel()
                }
                opened("http://${host.hostAddress}:${socket.localPort}$PATH$code")
            }
        } finally {
            close(socket)
        }
    }

    private suspend fun closeAfter(socket: ServerSocket, ms: Long) {
        try {
            delay(ms)
        } finally {
            close(socket)
        }
    }

    /** The first of [ports] free on [host], the system choosing one for 0. */
    private fun bind(): ServerSocket {
        var failure: IOException? = null
        for (port in ports) {
            try {
                return ServerSocket(port, BACKLOG, host)
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: IOException("No port to listen on")
    }

    /** At least 96 bits, as lower-case base32: an address no one guesses. */
    private fun code(): String = String(CharArray(CODE_LENGTH) { BASE32[random.nextInt(BASE32.length)] })

    private fun close(socket: ServerSocket) {
        closeQuietly(socket)
        answering?.let(::closeQuietly)
    }

    /** Answers each connection in turn, until the socket closes or a request comes after [deadline]. */
    private suspend fun acceptAll(socket: ServerSocket, code: String, deadline: Long) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            answering = client
            // A request after the deadline, before the timer closed the socket, gets nothing.
            if (clock() >= deadline) closeQuietly(socket) else answerQuietly(client, code)
            closeQuietly(client)
            answering = null
        }
    }

    /** Answers [client], a client gone or too slow letting the next one be served. */
    private suspend fun answerQuietly(client: Socket, code: String) {
        try {
            answer(client, code)
        } catch (_: IOException) {
            // Nothing to tell it.
        }
    }

    private suspend fun answer(client: Socket, code: String) {
        val out = client.getOutputStream()
        val parts = readHead(client)?.substringBefore('\n')?.trim()?.split(' ').orEmpty()
        // Case is folded: a QR code holds the address in capitals, its most compact form.
        val path = parts.getOrNull(1)?.substringBefore('?')?.lowercase(Locale.ROOT).orEmpty()
        val page = "$PATH$code"
        val reply = when {
            parts.size != REQUEST_LINE_PARTS -> response(BAD_REQUEST)
            parts.first() != "GET" -> response(METHOD_NOT_ALLOWED, extra = "Allow: GET\r\n")
            same(path, page) -> {
                val nonce = Base64.getEncoder().encodeToString(ByteArray(NONCE_BYTES).also(random::nextBytes))
                response(OK, content.page("$code.txt", nonce).toByteArray(), "text/html; charset=utf-8", csp(nonce))
            }
            same(path, "$page.txt") -> {
                val attachment = "Content-Disposition: attachment; filename=\"$FILE\"\r\n"
                response(OK, content.text().toByteArray(), "text/plain; charset=utf-8", attachment)
            }
            else -> response(NOT_FOUND)
        }
        out.write(reply)
        out.flush()
    }

    /** The request's line and headers, or null past [MAX_REQUEST] bytes, [READ_TIMEOUT_MS] or a closed connection. */
    private fun readHead(client: Socket): String? {
        val input: InputStream = client.getInputStream()
        val head = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK)
        val until = clock() + READ_TIMEOUT_MS
        var ended = false
        while (!ended && head.size() <= MAX_REQUEST) {
            val left = until - clock()
            if (left <= 0) return null
            client.soTimeout = left.toInt()
            val read = try {
                input.read(buffer)
            } catch (_: SocketTimeoutException) {
                -1
            }
            if (read < 0) return null
            head.write(buffer, 0, read)
            val text = head.toString(Charsets.ISO_8859_1.name())
            ended = "\r\n\r\n" in text || "\n\n" in text
        }
        return head.toString(Charsets.ISO_8859_1.name()).takeIf { ended && head.size() <= MAX_REQUEST }
    }

    /** Whether [path] is [expected], in a time that does not tell how much of it matched. */
    private fun same(path: String, expected: String) = MessageDigest.isEqual(path.toByteArray(), expected.toByteArray())

    private fun csp(nonce: String) =
        "Content-Security-Policy: default-src 'none'; style-src 'nonce-$nonce'; script-src 'nonce-$nonce'; " +
            "base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n"

    private fun response(status: String, body: ByteArray = ByteArray(0), type: String? = null, extra: String = ""): ByteArray {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            type?.let { append("Content-Type: ").append(it).append("\r\n") }
            append("Content-Length: ").append(body.size).append("\r\n")
            append(SECURITY_HEADERS)
            append(extra)
            append("Connection: close\r\n\r\n")
        }
        return head.toByteArray(Charsets.ISO_8859_1) + body
    }

    companion object {
        /** How long the page lives at most. */
        const val LIFETIME_MS = 10 * 60 * 1000L

        /** Asked first, so the address stays the same from one time to the next; the system picks one when taken. */
        const val PORT = 8642
        const val PATH = "/journal/"

        const val READ_TIMEOUT_MS = 5_000L
        const val MAX_REQUEST = 8 * 1024

        private const val BACKLOG = 4
        private const val READ_CHUNK = 1024
        private const val NANOS_PER_MS = 1_000_000L
        private const val REQUEST_LINE_PARTS = 3
        private const val NONCE_BYTES = 16
        private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"

        /** 20 base32 characters: 100 bits. */
        private const val CODE_LENGTH = 20
        private const val FILE = "houselights-journal.txt"

        private const val OK = "200 OK"
        private const val BAD_REQUEST = "400 Bad Request"
        private const val NOT_FOUND = "404 Not Found"
        private const val METHOD_NOT_ALLOWED = "405 Method Not Allowed"

        private const val SECURITY_HEADERS = "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "X-Frame-Options: DENY\r\n"

        private fun closeQuietly(closeable: java.io.Closeable) {
            try {
                closeable.close()
            } catch (_: IOException) {
                // Already closed.
            }
        }

        /**
         * The device's private IPv4 address on the home network (10/8, 172.16/12, 192.168/16), from the interfaces that
         * are up; null without one.
         */
        fun privateIpv4(addresses: List<InetAddress> = upAddresses()): Inet4Address? =
            addresses.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }

        /** The addresses of the interfaces that are up; none when the system cannot list them. */
        private fun upAddresses(): List<InetAddress> = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
        } catch (_: SocketException) {
            emptyList()
        }
    }
}
