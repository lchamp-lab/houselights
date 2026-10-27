package app.houselights

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The library version as the app wires it (playback, user edits, a scan's progress and end, throttled), in virtual time. */
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
        val played = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val changes = LibraryChanges(backgroundScope, listOf(played), periodMs = 3_000)
        runCurrent()
        played.emit(Unit)
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
    fun changesMoveTheVersionOncePerPeriodAtMost() = runTest {
        val played = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val changes = LibraryChanges(backgroundScope, listOf(played), periodMs = 3_000)
        runCurrent()
        val moves = movesOf(changes)
        backgroundScope.launch { every(200, 50, played) }
        advanceTimeBy(14_000)
        // Over 10 s of changes: a version at once, then one per period.
        assertEquals(listOf(0L, 3_000L, 6_000L, 9_000L, 12_000L), moves)
    }

    /** A first scan writing titles for a minute: screens reload three times, not twenty. */
    @Test
    fun aPassShowsItsProgressOncePerLongerPeriod() = runTest {
        val progress = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val changes = LibraryChanges(backgroundScope, listOf(emptyFlow()), progress, periodMs = 3_000, progressMs = 20_000)
        runCurrent()
        val moves = movesOf(changes)
        backgroundScope.launch { every(200, 300, progress) }
        advanceTimeBy(65_000)
        assertEquals(listOf(0L, 20_000L, 40_000L, 60_000L), moves)
        assertEquals("progress is no settled change", 0L, changes.settled.value)
    }

    /** A pass's end shows at once, and with it the titles its progress had left waiting: no reload after it. */
    @Test
    fun aPassEndShowsAtOnceAndWhatItsProgressLeft() = runTest {
        val progress = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val ended = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val changes = LibraryChanges(backgroundScope, listOf(ended), progress, periodMs = 3_000, progressMs = 20_000)
        runCurrent()
        val moves = movesOf(changes)
        progress.emit(Unit)
        advanceTimeBy(5_000)
        progress.emit(Unit)
        advanceTimeBy(5_000)
        ended.emit(Unit)
        advanceTimeBy(30_000)
        assertEquals(listOf(0L, 10_000L), moves)
        assertEquals(1L, changes.settled.value)
    }

    @Test
    fun anEditMovesTheSettledVersionBeforeTheVersion() = runTest {
        val changes = LibraryChanges(backgroundScope, listOf(emptyFlow()))
        runCurrent()
        val seen = mutableListOf<Long>()
        backgroundScope.launch { changes.version.collect { seen += changes.settled.value } }
        runCurrent()
        changes.userChanged()
        runCurrent()
        assertEquals(listOf(0L, 1L), seen)
    }

    /** When the version moved, from the first move on. */
    private fun TestScope.movesOf(changes: LibraryChanges): List<Long> = mutableListOf<Long>().also { moves ->
        backgroundScope.launch { changes.version.drop(1).collect { moves += testScheduler.currentTime } }
    }

    private suspend fun every(ms: Long, times: Int, flow: MutableSharedFlow<Unit>) = repeat(times) {
        flow.emit(Unit)
        delay(ms)
    }
}
