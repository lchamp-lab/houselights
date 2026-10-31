package app.houselights

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Which keys reach the screen under the launch screen or while the lights go down; on Robolectric for KeyEvent. */
@RunWith(RobolectricTestRunner::class)
class DispatchUnseenTest {
    /** How a key pressed then released ends: passed on (once per action), and the cue cancelled. */
    private data class Outcome(val passed: Int, val cancelled: Int)

    private fun press(keyCode: Int, covered: Boolean = false, dimming: Boolean = false): Outcome {
        var passed = 0
        var cancelled = 0
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            dispatchUnseen(KeyEvent(action, keyCode), covered, dimming, cancel = { cancelled++ }) {
                passed++
                true
            }
        }
        return Outcome(passed, cancelled)
    }

    @Test
    fun withTheLightsUpEveryKeyPasses() {
        for (key in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_F12, KeyEvent.KEYCODE_BACK)) {
            assertEquals("$key", Outcome(passed = 2, cancelled = 0), press(key))
        }
    }

    /** Only system keys pass under the launch screen, Back and the volume included; Menu and Pop Menu open nothing. */
    @Test
    fun underTheLaunchScreenOnlySystemKeysPass() {
        val expected = mapOf(
            KeyEvent.KEYCODE_DPAD_CENTER to 0,
            KeyEvent.KEYCODE_MENU to 0,
            KeyEvent.KEYCODE_F12 to 0,
            KeyEvent.KEYCODE_BACK to 2,
            KeyEvent.KEYCODE_VOLUME_UP to 2,
        )
        expected.forEach { (key, passed) -> assertEquals("$key", Outcome(passed, cancelled = 0), press(key, covered = true)) }
    }

    /** While the lights go down Back cancels the cue once, on its release; OK and Menu are swallowed; the volume passes. */
    @Test
    fun whileTheLightsGoDownBackCancelsAndOnlyTheVolumePasses() {
        val expected = mapOf(
            KeyEvent.KEYCODE_BACK to Outcome(passed = 0, cancelled = 1),
            KeyEvent.KEYCODE_DPAD_CENTER to Outcome(passed = 0, cancelled = 0),
            KeyEvent.KEYCODE_MENU to Outcome(passed = 0, cancelled = 0),
            KeyEvent.KEYCODE_F12 to Outcome(passed = 0, cancelled = 0),
            KeyEvent.KEYCODE_VOLUME_UP to Outcome(passed = 2, cancelled = 0),
        )
        expected.forEach { (key, outcome) -> assertEquals("$key", outcome, press(key, dimming = true)) }
        val coveredToo = press(KeyEvent.KEYCODE_BACK, covered = true, dimming = true)
        assertEquals("under the launch screen too", Outcome(passed = 0, cancelled = 1), coveredToo)
    }
}
