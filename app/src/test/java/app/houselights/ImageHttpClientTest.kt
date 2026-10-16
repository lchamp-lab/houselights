package app.houselights

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Pictures and a server's answers come from one host: neither waits in the other's queue. */
class ImageHttpClientTest {
    /** Answers every request at once, but holds pictures until [pictures] is let go. */
    private val pictures = CountDownLatch(1)

    private val api = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            if ("/Images/" in request.url.encodedPath) pictures.await(HOLD_S, TimeUnit.SECONDS)
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

    /** One pool of connections and threads still: only the queue is the pictures' own. */
    @Test
    fun picturesShareTheConnectionsAndThreads() {
        val images = imageHttpClient(api)
        assertSame(api.connectionPool, images.connectionPool)
        assertSame(api.dispatcher.executorService, images.dispatcher.executorService)
    }

    private companion object {
        /** More than OkHttp lets go to a host at a time. */
        const val POSTERS = 8

        /** How long the title may take; the posters are held longer, until the test ends. */
        const val WAIT_S = 5L
        const val HOLD_S = 60L
    }
}
