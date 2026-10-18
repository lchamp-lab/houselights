package app.houselights.ui.design

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When focus runs across the cards with an arrow held, and when it stops. */
class ScrubTest {
    private var clock = 0L
    private val pending = mutableListOf<Pair<Long, () -> Unit>>()
    private val scrub = ScrubTracker(now = { clock }) { delay, block ->
        val entry = (clock + delay) to block
        pending += entry
        val cancel: () -> Unit = { pending -= entry }
        cancel
    }

    private fun press(key: Int = KeyEvent.KEYCODE_DPAD_RIGHT, repeat: Int = 0) = scrub.onKey(KeyEvent.ACTION_DOWN, key, repeat)
    private fun release(key: Int = KeyEvent.KEYCODE_DPAD_RIGHT) = scrub.onKey(KeyEvent.ACTION_UP, key, 0)
    private fun advance(ms: Long) {
        clock += ms
        pending.filter { it.first <= clock }.forEach { pending -= it; it.second() }
    }

    @Test
    fun oneStepIsNoRun() {
        press()
        assertFalse(scrub.active)
        release()
        advance(500)
        assertFalse(scrub.active)
    }

    @Test
    fun anArrowHeldRunsUntilLetGo() {
        press()
        advance(400)
        press(repeat = 1)
        assertTrue(scrub.active)
        release()
        assertTrue("A run ends a moment after the arrow is let go", scrub.active)
        advance(ScrubTracker.SETTLE_MS)
        assertFalse(scrub.active)
    }

    /** An infrared remote repeats by pressing again: quick presses run too, and no card lights up between them. */
    @Test
    fun quickPressesRunAsAHeldArrow() {
        press(); release(); advance(80)
        press()
        assertTrue(scrub.active)
        release(); advance(80)
        press()
        assertTrue("Still running between two presses", scrub.active)
        release(); advance(ScrubTracker.SETTLE_MS)
        assertFalse(scrub.active)
    }

    @Test
    fun otherKeysAreNoRun() {
        scrub.onKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 3)
        assertFalse(scrub.active)
    }
}
