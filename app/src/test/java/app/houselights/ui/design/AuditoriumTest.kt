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

    /**
     * A drawing reading its step is drawn again only on the frames where that step moves, as Compose
     * observes a drawing's reads: the scrims over a third of the cue, where reading the lights
     * themselves draws it on every frame.
     */
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
                // As a frame draws: what was told draws again, reading again what it reads.
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

    /**
     * The launch: the lights go down on the mark before home comes up, each step quick at first and
     * settling (ease-out), over a few hundred milliseconds of the system's motion.
     */
    @Test
    fun atTheLaunchTheMarkGoesOutBeforeHomeComesUp() {
        val t = (0..FRAMES).map { it / FRAMES.toFloat() }
        assertEquals(1f, LaunchCue.mark(0f), EXACT)
        assertEquals(1f, LaunchCue.darkness(0f), EXACT)
        assertEquals(0f, LaunchCue.mark(1f), EXACT)
        assertEquals(0f, LaunchCue.darkness(1f), EXACT)
        // When the black starts to lift, the mark is all but out.
        val lifts = t.first { LaunchCue.darkness(it) < 1f }
        assertTrue("the mark at ${LaunchCue.mark(lifts)} when home comes up", LaunchCue.mark(lifts) < 0.1f)
        // Ease-out: the first half of each step does more than half of it.
        val marked = t.filter { LaunchCue.mark(it) in 0.0001f..0.9999f }
        assertTrue(LaunchCue.mark(marked[marked.size / 2]) < 0.5f)
        val lifting = t.filter { LaunchCue.darkness(it) in 0.0001f..0.9999f }
        assertTrue(LaunchCue.darkness(lifting[lifting.size / 2]) < 0.5f)
        // Both only ever go down.
        assertTrue(t.zipWithNext().all { (a, b) -> LaunchCue.mark(b) <= LaunchCue.mark(a) })
        assertTrue(t.zipWithNext().all { (a, b) -> LaunchCue.darkness(b) <= LaunchCue.darkness(a) })
        assertTrue("a few hundred milliseconds", LaunchCue.DURATION in HlMotion.STANDARD..HlMotion.SLOW)
    }

    private companion object {
        /** HlMotion.DIM at 60 frames a second. */
        const val FRAMES = 60

        /** An easing's ends, within what a frame shows. */
        const val EXACT = 0.001f
    }
}
