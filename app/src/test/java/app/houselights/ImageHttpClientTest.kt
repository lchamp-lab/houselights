package app.houselights

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Images and API calls to the same host have separate queues: neither waits behind the other; TMDB's pictures load more
 * at once than a server's; HTTPS stays HTTPS.
 */
class ImageHttpClientTest {
    /** Holds the fake server's image requests until counted down; other requests answer at once. */
    private val pictures = CountDownLatch(1)

    /** Image requests started, each counting down one. */
    @Volatile private var started = CountDownLatch(0)

    private val api = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            if ("/Images/" in request.url.encodedPath || request.url.host == TMDB) {
                started.countDown()
                pictures.await(HOLD_S, TimeUnit.SECONDS)
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("Test")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private fun Call.enqueued(done: CountDownLatch) = enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) = done.countDown()

            override fun onResponse(call: Call, response: Response) = response.use { done.countDown() }
        }
    )

    @Test
    fun aTitleIsAskedForWhilePostersLoadFromTheSameServer() {
        val images = imageHttpClient(api)
        try {
            val posters = CountDownLatch(POSTERS)
            repeat(POSTERS) { images.newCall(Request.Builder().url("http://nas:8096/Items/f$it/Images/Primary").build()).enqueued(posters) }
            val title = CountDownLatch(1)
            api.newCall(Request.Builder().url("http://nas:8096/Items/f1?userId=u1").build()).enqueued(title)
            assertTrue("The title waited behind the posters", title.await(WAIT_S, TimeUnit.SECONDS))
        } finally {
            pictures.countDown()
        }
    }

    @Test
    fun picturesShareTheConnectionsAndThreads() {
        val calls = ImageCalls(api)
        for (url in listOf("https://$TMDB/t/p/w342/a.jpg", "http://nas:8096/Items/f1/Images/Primary")) {
            val images = calls.client(url.toHttpUrl())
            assertSame(api.connectionPool, images.connectionPool)
            assertSame(api.dispatcher.executorService, images.dispatcher.executorService)
            assertFalse(images.followSslRedirects)
        }
    }

    /** A cold home asks TMDB's CDN for some forty pictures: they load more than five at a time. */
    @Test
    fun tmdbPicturesLoadMoreAtOnce() {
        started = CountDownLatch(POSTERS)
        try {
            val calls = ImageCalls(api)
            val posters = CountDownLatch(POSTERS)
            repeat(POSTERS) { calls.newCall(Request.Builder().url("https://$TMDB/t/p/w342/p$it.jpg").build()).enqueued(posters) }
            assertTrue("TMDB's pictures waited five at a time", started.await(WAIT_S, TimeUnit.SECONDS))
        } finally {
            pictures.countDown()
        }
    }

    /** A Jellyfin server resizes each picture it serves: it is still asked five at a time. */
    @Test
    fun aServersPicturesStayFiveAtATime() {
        started = CountDownLatch(SERVER_AT_ONCE + 1)
        try {
            val calls = ImageCalls(api)
            val posters = CountDownLatch(POSTERS)
            repeat(POSTERS) { calls.newCall(Request.Builder().url("http://nas:8096/Items/f$it/Images/Primary").build()).enqueued(posters) }
            assertFalse("More than five pictures were asked of the server", started.await(QUIET_MS, TimeUnit.MILLISECONDS))
            assertEquals(1, started.count)
        } finally {
            pictures.countDown()
        }
    }

    /** An NFO's HTTPS picture redirected to http://192.168.1.1/… would reach the router in plain HTTP. */
    @Test
    fun aRedirectNeverLeavesHttps() {
        val images = imageHttpClient(OkHttpClient())
        assertFalse(images.followSslRedirects)
        assertTrue(images.followRedirects)
    }

    private companion object {
        /** More than OkHttp's per-host request limit, as many as TMDB takes at once. */
        const val POSTERS = 8

        /** OkHttp's per-host request limit. */
        const val SERVER_AT_ONCE = 5
        const val TMDB = "image.tmdb.org"

        /** How long the server's sixth picture is given to start, which it must not. */
        const val QUIET_MS = 300L

        /** Timeout for the title request; the posters stay held until the test ends. */
        const val WAIT_S = 5L
        const val HOLD_S = 60L
    }
}
