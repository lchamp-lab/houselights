package app.houselights.ui.testing

import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeTestRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withContext
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf

/**
 * Runs the test's scheduler until [done], waiting in real time between runs for work on threads of its own
 * (Room's, `Dispatchers.IO`, DataStore's); fails, saying [what] never happened, after [timeoutMs].
 */
suspend fun TestScope.waitFor(what: String, timeoutMs: Long = 10_000, done: suspend () -> Boolean) {
    val until = System.nanoTime() + timeoutMs * NANOS_PER_MS
    while (true) {
        advanceUntilIdle()
        if (done()) return
        if (System.nanoTime() > until) fail("Not after $timeoutMs ms: $what")
        withContext(Dispatchers.Default) { delay(POLL_MS) }
    }
}

/**
 * Runs the main looper until [done], waiting in real time between runs for work on threads of its own (Coil's,
 * `Dispatchers.IO`) that comes back there; fails, saying [what] never happened, after [timeoutMs].
 */
fun waitOnMainLooper(what: String, timeoutMs: Long = LOOPER_WAIT_MS, done: () -> Boolean) {
    val until = System.nanoTime() + timeoutMs * NANOS_PER_MS
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (done()) return
        if (System.nanoTime() > until) fail("Not after $timeoutMs ms: $what")
        Thread.sleep(POLL_MS)
    }
}

/**
 * [waitOnMainLooper] with Compose idle at each run and once [done]: what a screen's work on other threads posts back
 * to the main looper is run, then drawn, before the test goes on.
 */
fun ComposeTestRule.waitWithLooper(what: String, timeoutMs: Long = LOOPER_WAIT_MS, done: () -> Boolean) {
    waitOnMainLooper(what, timeoutMs) {
        waitForIdle()
        done()
    }
    waitForIdle()
}

/** How long a screen's work on other threads may take, on a CI runner shared by three test JVMs. */
const val LOOPER_WAIT_MS = 5_000L

private const val POLL_MS = 10L
private const val NANOS_PER_MS = 1_000_000L
