package app.houselights.update

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** A call to GitHub awaited by the updater ([await]): what it answers is never left open. */
class CallAwaitTest {
    /** A call whose answer comes when the test gives it ([answer]). */
    private class HeldCall : Call {
        private val request = Request.Builder().url("https://api.github.com/repos/$REPO/releases").build()
        private var callback: Callback? = null

        override fun request() = request

        override fun execute(): Response = error("Only enqueued")

        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
        }

        override fun cancel() = Unit

        override fun isExecuted() = callback != null

        override fun isCanceled() = false

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = HeldCall()

        /** Answers the call, [closed] set once its body is closed. */
        fun answer(closed: AtomicBoolean) {
            val body = object : ForwardingSource(Buffer().writeUtf8("[]")) {
                override fun close() {
                    closed.set(true)
                    super.close()
                }
            }
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.buffer().asResponseBody("application/json".toMediaType())).build()
            checkNotNull(callback).onResponse(this, response)
        }
    }

    /**
     * The answer come, then the check cancelled before its coroutine takes it (« Annuler », the app leaving the screen):
     * the answer is closed, its connection given back rather than leaked.
     */
    @Test
    fun anAnswerComeAsTheCheckIsCancelledIsClosed() = runTest {
        val call = HeldCall()
        val asking = launch { call.await() }
        runCurrent()
        val closed = AtomicBoolean()
        call.answer(closed)
        asking.cancel()
        runCurrent()
        assertTrue(closed.get())
    }
}
