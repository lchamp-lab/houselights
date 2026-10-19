package app.houselights.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Catching failures but not cancellation, and throttling a flow to one value a period. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutinesTest {
    @Test
    fun aFailureIsCaught() {
        val result = suspendRunCatching { throw IOException("NAS away") }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(2, suspendRunCatching { 1 + 1 }.getOrNull())
    }

    @Test
    fun aCancellationIsNotAFailure() {
        assertThrows(CancellationException::class.java) { suspendRunCatching { throw CancellationException("Replaced") } }
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
