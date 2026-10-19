package app.houselights.ui.design.components

import android.os.Looper
import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.Scrub
import app.houselights.ui.design.ScrubTracker
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** DESIGN.md, Focus: a row draws one frame, which travels from the card left to the card reached. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class RowFrameTest {
    @get:Rule
    val compose = createComposeRule()

    private val frames = mutableMapOf<String, RowFrame>()
    private var compositions = 0
    private val start = FocusRequester()

    /** Rows of eight posters named by [rows], focus on the first poster of the first. */
    private fun show(vararg rows: String) {
        compose.setTvContent {
            Column {
                rows.forEach { row ->
                    MediaRow(header = null) {
                        items((0 until 8).map { "$row$it" }) { card ->
                            frames[row] = checkNotNull(LocalRowFrame.current)
                            SideEffect { compositions++ }
                            val first = if (card == "${rows.first()}0") Modifier.focusRequester(start) else Modifier
                            ArtworkSurface(
                                onClick = {},
                                modifier = first.testTag(card).width(HlSize.poster).aspectRatio(2f / 3f),
                            ) { SideEffect { compositions++ } }
                        }
                    }
                }
            }
        }
        compose.runOnIdle { start.requestFocus() }
        compose.waitForIdle()
    }

    private fun centre(card: String): Offset = compose.onNodeWithTag(card).fetchSemanticsNode().boundsInRoot.center

    private fun frame(row: String = "a") = checkNotNull(frames[row]?.boundsInRoot()) { "Row $row frames nothing" }

    /** Width of the frame around a poster grown [risen] of the way. */
    private fun frameWidth(risen: Float): Float {
        val grown = 1f + (HlFocus.POSTER_SCALE - 1f) * risen
        return HlSize.poster.value * grown + 2 * (HlFocus.frameGap.value + HlFocus.frame.value / 2)
    }

    @After
    fun letGoOfTheArrow() {
        Scrub.onKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ScrubTracker.SETTLE_MS * 2))
        assertFalse(Scrub.active)
    }

    @Test
    fun theFrameTravelsToTheCardReachedWithoutRecomposing() {
        show("a")
        assertEquals(centre("a0").x, frame().center.x, 0.5f)
        assertEquals(frameWidth(1f), frame().width, 0.5f)

        compose.mainClock.autoAdvance = false
        compose.press(Key.DirectionRight)
        // Focus moving recomposes the two cards it concerns, once.
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val settled = compositions
        val from = centre("a0").x
        val to = centre("a1").x
        val midway = frame().center.x
        assertTrue("the frame is on its way at $midway, from $from to $to", midway > from && midway < to)
        compose.mainClock.advanceTimeBy(HlMotion.FOCUS_IN / 2L)
        assertTrue("it keeps going", frame().center.x > midway)
        assertEquals("only drawing follows the frame", settled, compositions)

        compose.mainClock.advanceTimeBy(HlMotion.FOCUS_OUT.toLong())
        assertEquals(to, frame().center.x, 0.5f)
        assertEquals("it frames the reached card grown", frameWidth(1f), frame().width, 0.5f)
    }

    @Test
    fun theFrameGoesOutInPlaceWhenFocusLeavesTheRow() {
        show("a", "b")
        compose.press(Key.DirectionDown)
        assertEquals(0f, checkNotNull(frames["a"]).shown, 0f)
        assertEquals("it stays where it went out", centre("a0").x, frame("a").center.x, 0.5f)
        assertEquals(1f, checkNotNull(frames["b"]).shown, 0f)
        assertEquals(centre("b0").y, frame("b").center.y, 0.5f)

        // Coming back, it lights on the card focus returns to, from nowhere.
        compose.mainClock.autoAdvance = false
        compose.press(Key.DirectionUp)
        compose.mainClock.advanceTimeByFrame()
        assertEquals(centre("a0").x, frame("a").center.x, 0.5f)
    }

    @Test
    fun aHeldArrowMovesTheFrameAtOnce() {
        show("a")
        // A held arrow repeats.
        Scrub.onKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 1)
        compose.mainClock.autoAdvance = false
        compose.press(Key.DirectionRight)
        compose.mainClock.advanceTimeByFrame()
        assertEquals(centre("a1").x, frame().center.x, 0.5f)
        // Crossed cards do not grow: the frame stands off the card as it is.
        assertEquals(frameWidth(0f), frame().width, 0.5f)
    }
}
