package app.houselights.ui.design

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditoriumTest {
    @Test
    fun theRoomGoesDarkBeforeTheScreen() {
        // When black starts, the room has gone and the scrims have lifted.
        val blackStarts = 0.58f
        assertEquals(0f, HouseLightCue.room(blackStarts))
        assertEquals(0f, HouseLightCue.scrims(blackStarts))
        assertEquals(0f, HouseLightCue.darkness(0.3f))
        assertEquals(1f, HouseLightCue.darkness(1f))
        assertEquals(1f, HouseLightCue.room(0f))
    }

    /** Reading a step redraws only on frames where it moves (the scrims: a third of the cue), not every frame. */
    @Test
    fun aReaderIsToldOnlyWhileItsStepMoves() {
        val down = mutableFloatStateOf(0f)
        val steps = listOf(HouseLightCue::room, HouseLightCue::scrims, HouseLightCue::darkness)
        val reads = steps.map { step -> cueStep({ down.floatValue }, step).let { state -> { state.value } } } + { down.floatValue }
        val told = IntArray(reads.size)
        val stale = BooleanArray(reads.size) { true }
        val tell: (Int) -> Unit = { told[it]++; stale[it] = true }
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            for (frame in 0..FRAMES) {
                // As a frame draws: each notified reader draws again, reading what it reads.
                reads.forEachIndexed { i, read ->
                    if (stale[i]) {
                        stale[i] = false
                        observer.observeReads(i, tell) { read() }
                    }
                }
                down.floatValue = frame / FRAMES.toFloat()
                Snapshot.sendApplyNotifications()
            }
        } finally {
            observer.stop()
        }
        steps.forEachIndexed { i, step ->
            val moves = (1..FRAMES).count { step(it / FRAMES.toFloat()) != step((it - 1) / FRAMES.toFloat()) }
            assertEquals(moves, told[i])
            assertTrue("$moves of $FRAMES frames", moves < FRAMES / 2)
        }
        assertEquals("the lights themselves", FRAMES, told.last())
    }

    /** The mark's bars go out one after another, widest first, with an ease-out; then the black lifts off home. */
    @Test
    fun atTheLaunchTheBarsGoOutOneAfterTheOtherBeforeHomeComesUp() {
        val t = (0..FRAMES).map { it / FRAMES.toFloat() }
        val bars = 0 until LaunchCue.bars
        assertEquals("the mark's bars", Gradation.widths.size, LaunchCue.bars)
        bars.forEach { bar ->
            assertEquals(1f, LaunchCue.bar(bar, 0f), EXACT)
            assertEquals(0f, LaunchCue.bar(bar, 1f), EXACT)
        }
        assertEquals(1f, LaunchCue.darkness(0f), EXACT)
        assertEquals(0f, LaunchCue.darkness(1f), EXACT)
        val starts = bars.map { bar -> t.first { LaunchCue.bar(bar, it) < 1f } }
        val outs = bars.map { bar -> t.first { LaunchCue.bar(bar, it) <= 0f } }
        assertTrue("$starts", starts.zipWithNext().all { (a, b) -> a < b })
        assertTrue("$outs", outs.zipWithNext().all { (a, b) -> a < b })
        // When the black starts to lift, every bar is out; before that, nothing of home shows.
        val lifts = t.first { LaunchCue.darkness(it) < 1f }
        bars.forEach { bar -> assertEquals("bar $bar when home comes up", 0f, LaunchCue.bar(bar, lifts), EXACT) }
        assertTrue(lifts >= outs.last())
        // Ease-out: the first half of each step covers more than half of it.
        bars.forEach { bar ->
            val going = t.filter { LaunchCue.bar(bar, it) in 0.0001f..0.9999f }
            assertTrue("bar $bar", LaunchCue.bar(bar, going[going.size / 2]) < 0.5f)
        }
        val lifting = t.filter { LaunchCue.darkness(it) in 0.0001f..0.9999f }
        assertTrue(LaunchCue.darkness(lifting[lifting.size / 2]) < 0.5f)
        bars.forEach { bar -> assertTrue(t.zipWithNext().all { (a, b) -> LaunchCue.bar(bar, b) <= LaunchCue.bar(bar, a) }) }
        assertTrue(t.zipWithNext().all { (a, b) -> LaunchCue.darkness(b) <= LaunchCue.darkness(a) })
        assertEquals("the length of the cue the launch had", HlMotion.SLOW, LaunchCue.DURATION)
    }

    private companion object {
        /** HlMotion.DIM at 60 frames a second. */
        const val FRAMES = 60

        /** Tolerance at an easing's ends, below what a frame shows. */
        const val EXACT = 0.001f
    }
}
