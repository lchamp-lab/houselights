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
    fun aChangeDuringAPeriodShowsAtItsEnd() = runTest {
        val scan = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val changes = LibraryChanges(backgroundScope, listOf(scan), periodMs = 3_000)
        runCurrent()
        scan.emit(Unit)
        runCurrent()
        assertEquals(1L, changes.version.value)
        advanceTimeBy(1_000)
        changes.userChanged()
        runCurrent()
        assertEquals("Shown before the period ended", 1L, changes.version.value)
        advanceTimeBy(2_001)
        assertEquals("Not shown at the period's end", 2L, changes.version.value)
    }

    @Test
    fun aScanMovesTheVersionOncePerPeriodAtMost() = runTest {
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
        advanceTimeBy(14_000)
        // Over 10 s of scan: a version at once, then one per period.
        val moves = versions.drop(1).map { it.first }
        assertEquals(listOf(0L, 3_000L, 6_000L, 9_000L, 12_000L), moves)
    }
}
