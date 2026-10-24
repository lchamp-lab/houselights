package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.IOException

/** A failure that repeats is logged whole once, then counted in one line. */
@RunWith(RobolectricTestRunner::class)
class RepeatedFailuresTest {
    private val failures = RepeatedFailures("Test")

    @Before
    fun clear() = ShadowLog.clear()

    /** Each line, its stack marked by "+". */
    private fun told() = ShadowLog.getLogsForTag("Test").map { if (it.throwable != null) "${it.msg} +" else it.msg }

    private fun fail(key: String) = failures.failed(key, "Could not read $key", IOException("Locked"))

    @Test
    fun eachKeyIsLoggedWholeOnceThenCountedInOneLine() {
        repeat(3) { fail("a.nfo") }
        fail("b.nfo")
        failures.summarize { "$it more" }
        failures.summarize { "$it more" }
        assertEquals(listOf("Could not read a.nfo +", "Could not read b.nfo +", "2 more"), told())
    }

    @Test
    fun aKeyThatWorkedAgainIsLoggedWholeAtItsNextFailure() {
        fail("a.nfo")
        failures.recovered("a.nfo")
        fail("a.nfo")
        failures.summarize { "$it more" }
        assertEquals(listOf("Could not read a.nfo +", "Could not read a.nfo +"), told())
    }

    /** The NAS away at one pass, the file malformed at the next: the new cause is not hidden in a count. */
    @Test
    fun aKeyFailingAnotherWayIsLoggedWholeAgain() {
        fail("a.nfo")
        failures.failed("a.nfo", "Could not parse a.nfo", IllegalStateException("Malformed"))
        // Back and forth between the two: each was logged whole once.
        fail("a.nfo")
        failures.failed("a.nfo", "Could not parse a.nfo", IllegalStateException("Malformed"))
        failures.summarize { "$it more" }
        assertEquals(listOf("Could not read a.nfo +", "Could not parse a.nfo +", "2 more"), told())
    }
}
