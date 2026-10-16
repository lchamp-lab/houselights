package app.houselights.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutinesTest {
    @Test
    fun aFailureIsCaught() {
        val result = suspendRunCatching { throw IOException("NAS away") }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(2, suspendRunCatching { 1 + 1 }.getOrNull())
    }

    /** A load replaced by a newer one stops quietly: its cancellation is not reported as a failure. */
    @Test
    fun aCancellationIsNotAFailure() {
        try {
            suspendRunCatching { throw CancellationException("Replaced") }
            fail("The cancellation was caught")
        } catch (_: CancellationException) {
            // Rethrown, as it should be.
        }
    }

    @Test
    fun throttleLatestGivesTheFirstValueAtOnceThenTheLatestOfEachPeriod() = runTest {
        val values = flow {
            emit(1)
            delay(100)
            emit(2)
            delay(100)
            emit(3)
            delay(2_000)
            emit(4)
        }
        assertEquals(listOf(1, 3, 4), values.throttleLatest(1_000).toList())
    }
}
