package app.houselights.ui.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withContext
import org.junit.Assert.fail

/**
 * Runs the test's scheduler until [done], waiting in real time between runs for work on threads of its own
 * (Room's, `Dispatchers.IO`, DataStore's); fails, saying [what] never happened, after [timeoutMs].
 */
suspend fun TestScope.waitFor(what: String, timeoutMs: Long = 10_000, done: suspend () -> Boolean) {
    val until = System.nanoTime() + timeoutMs * 1_000_000
    while (true) {
        advanceUntilIdle()
        if (done()) return
        if (System.nanoTime() > until) fail("Not after $timeoutMs ms: $what")
        withContext(Dispatchers.Default) { delay(POLL_MS) }
    }
}

private const val POLL_MS = 10L
