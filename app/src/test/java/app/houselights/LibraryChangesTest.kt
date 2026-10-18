package app.houselights

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library version as the app wires it (scan, playback and user edits, throttled), in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryChangesTest {
    @Test
    fun aChangeAloneShowsAtOnce() = runTest {
        val changes = LibraryChanges(backgroundScope, listOf(emptyFlow()))
        runCurrent()
        changes.userChanged()
        runCurrent()
        assertEquals(1L, changes.version.value)
    }

    @Test
    fun aChangeDuringAScanWaitsAPeriodAtMost() = runTest {
        val scan = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val changes = LibraryChanges(backgroundScope, listOf(scan), periodMs = 3_000)
        runCurrent()
        val versions = mutableListOf<Pair<Long, Long>>()
        backgroundScope.launch { changes.version.collect { versions += testScheduler.currentTime to it } }
        backgroundScope.launch {
            repeat(50) {
                scan.emit(Unit)
                delay(200)
            }
        }
        advanceTimeBy(1_100)
        val before = changes.version.value
        changes.userChanged()
        advanceTimeBy(3_000)
        assertTrue("The change waited more than a period", changes.version.value > before)

        advanceTimeBy(10_000)
        // Over 14 s of scan: a version at once, then one per period.
        val moves = versions.drop(1).map { it.first }
        assertTrue("Moved too often: $moves", moves.zipWithNext().all { (a, b) -> b - a >= 3_000 })
    }
}
