package app.houselights.providers.jellyfin

import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.SourceConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Collections

/** The account provider tests are signed in with. */
val account = SourceConfig.Jellyfin("http://nas:8096", "Maison", "u1", "lea", "token")

/** Heat, item "f1" on the test server. */
val film = MediaItem("f1", MediaKind.MOVIE, "Heat")

/** What a test server says to a request: a status, a body and headers. Throw an IOException for a network failure. */
data class Answer(val code: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

/** Answers every request with [answer] on OkHttp's threads, and records it with its connect timeout. */
class FakeServer(private val answer: (Request) -> Answer) {
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    val connectTimeouts: MutableMap<String, Int> = Collections.synchronizedMap(mutableMapOf())

    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            requests += request
            connectTimeouts[request.url.toString()] = chain.connectTimeoutMillis()
            val (code, body, headers) = answer(request)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("Test")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    /** The paths requested, in order, such as "/Sessions/Playing/Stopped". */
    val paths: List<String> get() = synchronized(requests) { requests.map { it.url.encodedPath } }
}
