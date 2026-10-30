package app.houselights.ui.util

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.unit.dp
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.isOk
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

/**
 * OK's calm (`calmOk`): no OK for [HlMotion.SETTLE] from the first moment when asked, once what it follows changes, or
 * as focus arrives when asked; a press it took keeps its repeats and release; a release counts only after its own
 * press, given here, over what it was given on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class CalmOkTest {
    @get:Rule
    val compose = createComposeRule()

    private var shown by mutableStateOf("first")
    private val here = FocusRequester()
    private val elsewhere = FocusRequester()

    /** What OK did past the calm, on what it holds: `down`, `repeat` or `up`. */
    private val passed = mutableListOf<String>()

    /** What OK did on the other place, which no calm holds. */
    private val passedElsewhere = mutableListOf<String>()

    /** The calm on one of two places, focus on it ([focusHere]) or on the other, the clock in the test's hands. */
    private fun show(calmAtFirst: Boolean, onArrival: Boolean = false, focusHere: Boolean = true) {
        compose.mainClock.autoAdvance = false
        compose.setTvContent {
            Column {
                Spot(
                    Modifier
                        .onKeyEvent { event ->
                            if (event.isOk()) passedElsewhere += said(event.nativeKeyEvent)
                            false
                        }
                        .focusRequester(elsewhere),
                )
                Spot(
                    Modifier
                        .calmOk(shown, calmAtFirst, onArrival)
                        .onKeyEvent { event ->
                            if (event.isOk()) passed += said(event.nativeKeyEvent)
                            false
                        }
                        .focusRequester(here),
                )
            }
        }
        compose.runOnIdle { (if (focusHere) here else elsewhere).requestFocus() }
        compose.mainClock.advanceTimeByFrame()
    }

    @Composable
    private fun Spot(modifier: Modifier = Modifier) = Box(modifier.size(SPOT).focusable())

    private fun said(event: KeyEvent): String = when {
        event.action == KeyEvent.ACTION_UP -> "up"
        event.repeatCount > 0 -> "repeat"
        else -> "down"
    }

    private fun ok(action: Int, repeat: Int = 0) {
        val at = SystemClock.uptimeMillis()
        compose.onRoot().performKeyPress(ComposeKeyEvent(KeyEvent(at, at, action, KeyEvent.KEYCODE_DPAD_CENTER, repeat)))
    }

    private fun press() {
        ok(KeyEvent.ACTION_DOWN)
        ok(KeyEvent.ACTION_UP)
    }

    /** What the calm follows changes to [to], and the screen with it. */
    private fun change(to: String = "second") {
        shown = to
        frame()
    }

    /** A frame drawn: a write reaches the recomposer once the main looper runs, then waits for a frame. */
    private fun frame() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun settled() = compose.mainClock.advanceTimeBy(HlMotion.SETTLE + FRAME_MS)

    @Test
    fun calmFromTheFirstMomentWhenAsked() {
        show(calmAtFirst = true)
        press()
        assertEquals("too soon", emptyList<String>(), passed)
        settled()
        press()
        assertEquals(listOf("down", "up"), passed)
    }

    @Test
    fun notCalmAtFirstUnlessAsked() {
        show(calmAtFirst = false)
        press()
        assertEquals(listOf("down", "up"), passed)
    }

    @Test
    fun aChangeCalmsAgain() {
        show(calmAtFirst = false)
        change()
        press()
        assertEquals("too soon after the change", emptyList<String>(), passed)
        settled()
        press()
        assertEquals(listOf("down", "up"), passed)
    }

    @Test
    fun aPressTakenKeepsItsRepeatsAndReleasePastTheCalm() {
        show(calmAtFirst = true)
        ok(KeyEvent.ACTION_DOWN)
        settled()
        ok(KeyEvent.ACTION_DOWN, repeat = 1)
        ok(KeyEvent.ACTION_UP)
        assertEquals(emptyList<String>(), passed)
        press()
        assertEquals("the next press is OK's", listOf("down", "up"), passed)
    }

    @Test
    fun aPressGivenPastTheCalmRepeats() {
        show(calmAtFirst = false)
        ok(KeyEvent.ACTION_DOWN)
        ok(KeyEvent.ACTION_DOWN, repeat = 1)
        ok(KeyEvent.ACTION_UP)
        assertEquals(listOf("down", "repeat", "up"), passed)
    }

    @Test
    fun aReleaseOverAChangeDoesNothing() {
        show(calmAtFirst = false)
        ok(KeyEvent.ACTION_DOWN)
        change()
        settled()
        ok(KeyEvent.ACTION_UP)
        assertEquals("its press alone", listOf("down"), passed)
    }

    @Test
    fun aRepeatAndReleaseOverAChangeDoNothing() {
        show(calmAtFirst = false)
        ok(KeyEvent.ACTION_DOWN)
        change()
        ok(KeyEvent.ACTION_DOWN, repeat = 1)
        ok(KeyEvent.ACTION_UP)
        assertEquals("its press alone", listOf("down"), passed)
    }

    @Test
    fun aChangeUndoneWithinAFrameCalmsNothing() {
        show(calmAtFirst = false)
        shown = "second"
        change(to = "first")
        press()
        assertEquals(listOf("down", "up"), passed)
    }

    @Test
    fun aPressTakenThenLeftForElsewhereStaysHere() {
        show(calmAtFirst = true)
        ok(KeyEvent.ACTION_DOWN)
        compose.runOnIdle { elsewhere.requestFocus() }
        frame()
        ok(KeyEvent.ACTION_UP)
        press()
        assertEquals(emptyList<String>(), passed)
        assertEquals("its release, then a press of its own", listOf("up", "down", "up"), passedElsewhere)
    }

    @Test
    fun aReleaseWithoutItsPressDoesNothing() {
        show(calmAtFirst = false)
        ok(KeyEvent.ACTION_UP)
        assertEquals(emptyList<String>(), passed)
    }

    @Test
    fun focusArrivingCalmsAtOnceWhenAsked() {
        show(calmAtFirst = false, onArrival = true, focusHere = false)
        compose.runOnIdle { here.requestFocus() }
        press()
        assertEquals("too soon after focus came, even within the frame", emptyList<String>(), passed)
        settled()
        press()
        assertEquals(listOf("down", "up"), passed)
    }

    @Test
    fun aReleaseHereOfAPressGivenElsewhereDoesNothing() {
        show(calmAtFirst = false, onArrival = true, focusHere = false)
        ok(KeyEvent.ACTION_DOWN)
        compose.runOnIdle { here.requestFocus() }
        settled()
        ok(KeyEvent.ACTION_UP)
        assertEquals(emptyList<String>(), passed)
    }

    /** Down then OK in one go, focus back before the release: the first OK given here once settled is OK's. */
    @Test
    fun aPressTakenAsFocusArrivedIsForgottenOnceItLeaves() {
        show(calmAtFirst = false, onArrival = true, focusHere = false)
        compose.runOnIdle { here.requestFocus() }
        ok(KeyEvent.ACTION_DOWN)
        compose.runOnIdle { elsewhere.requestFocus() }
        ok(KeyEvent.ACTION_UP)
        settled()
        compose.runOnIdle { here.requestFocus() }
        settled()
        press()
        assertEquals(listOf("down", "up"), passed)
    }

    @Test
    fun focusArrivingLeavesOkAloneUnlessAsked() {
        show(calmAtFirst = false, focusHere = false)
        compose.runOnIdle { here.requestFocus() }
        compose.mainClock.advanceTimeByFrame()
        press()
        assertEquals(listOf("down", "up"), passed)
    }

    private companion object {
        /** A place focus can rest on. */
        val SPOT = 10.dp

        /** One frame, as the clock steps. */
        const val FRAME_MS = 16L
    }
}
