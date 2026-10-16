package app.houselights.providers.jellyfin

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Collections

/** What a test server says to a request: a status and a body. Throw an IOException for a network failure. */
data class Answer(val code: Int, val body: String = "")

/**
 * Servers in a test, in place of the network: every request is answered by [answer] and kept in
 * [requests], with the connect timeout it was made with. Answers come from OkHttp's own threads, as
 * a server's would.
 */
class FakeServer(private val answer: (Request) -> Answer) {
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    /** The connect timeout of each request, by its URL. */
    val connectTimeouts: MutableMap<String, Int> = Collections.synchronizedMap(mutableMapOf())

    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            requests += request
            connectTimeouts[request.url.toString()] = chain.connectTimeoutMillis()
            val (code, body) = answer(request)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("Test")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    /** The paths asked for, in order: "/Sessions/Playing/Stopped". */
    val paths: List<String> get() = synchronized(requests) { requests.map { it.url.encodedPath } }
}
