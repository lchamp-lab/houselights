package app.houselights.ui.design.components

import android.annotation.SuppressLint
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.setObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
@OptIn(ExperimentalComposeRuntimeApi::class)
class RowFrameTest {
    @get:Rule
    val compose = createComposeRule()

    private val frames = mutableMapOf<String, RowFrame>()
    private val requesters = mutableMapOf<String, FocusRequester>()

    /** Scopes recomposed anywhere in the screen, the rows' cards and their surfaces included. */
    private var recomposed = 0
    private val counting = object : CompositionObserver {
        override fun onScopeEnter(scope: RecomposeScope) {
            recomposed++
        }

        override fun onBeginComposition(composition: ObservableComposition) = Unit

        override fun onReadInScope(scope: RecomposeScope, value: Any) = Unit

        override fun onScopeExit(scope: RecomposeScope) = Unit

        override fun onEndComposition(composition: ObservableComposition) = Unit

        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) = Unit

        override fun onScopeDisposed(scope: RecomposeScope) = Unit
    }

    /** Rows of eight posters named by [rows], focus on the first poster of the first. */
    @OptIn(InternalComposeApi::class)
    private fun show(vararg rows: String) {
        compose.setTvContent {
            // Observing the screen's composition observes the rows' items, composed apart, too.
            val composition = currentComposer.composition
            DisposableEffect(composition) {
                val handle = composition.setObserver(counting)
                onDispose { handle?.dispose() }
            }
            Column {
                rows.forEach { row ->
                    MediaRow(header = null) {
                        items((0 until 8).map { "$row$it" }) { card ->
                            frames[row] = checkNotNull(LocalRowFrame.current)
                            @SuppressLint("RememberInComposition") // The test's own map keeps it across compositions.
                            val requester = requesters.getOrPut(card) { FocusRequester() }
                            ArtworkSurface(
                                onClick = {},
                                modifier = Modifier.focusRequester(requester).testTag(card).width(HlSize.poster).aspectRatio(2f / 3f),
                            ) {}
                        }
                    }
                }
            }
        }
        focusOn("${rows.first()}0")
    }

    private fun focusOn(card: String) {
        compose.runOnIdle { requesters.getValue(card).requestFocus() }
        compose.waitForIdle()
    }

    private fun centre(card: String): Offset = compose.onNodeWithTag(card).fetchSemanticsNode().boundsInRoot.center

    private fun frame(row: String = "a") = checkNotNull(frames[row]?.boundsInRoot()) { "Row $row frames nothing" }

    /** Width of the frame around a poster: it stands off the card by its gap, to its stroke's middle. */
    private val frameWidth = HlSize.poster.value + 2 * (HlFocus.frameGap.value + HlFocus.frame.value / 2)

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
        assertEquals(frameWidth, frame().width, 0.5f)

        compose.mainClock.autoAdvance = false
        val before = recomposed
        compose.press(Key.DirectionRight)
        // Focus moving recomposes the two cards it concerns, once.
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val settled = recomposed
        assertTrue("the cards' recomposition is seen", settled > before)
        val from = centre("a0").x
        val to = centre("a1").x
        val midway = frame().center.x
        assertTrue("the frame is on its way at $midway, from $from to $to", midway > from && midway < to)
        compose.mainClock.advanceTimeBy(HlMotion.FOCUS_IN / 2L)
        assertTrue("it keeps going", frame().center.x > midway)

        // The card left falls back and its light goes out.
        compose.mainClock.advanceTimeBy(HlMotion.FOCUS_OUT.toLong())
        assertEquals(to, frame().center.x, 0.5f)
        assertEquals("it frames the reached card", frameWidth, frame().width, 0.5f)
        assertEquals("only drawing and placing follow the frame and the lights", settled, recomposed)
    }

    @Test
    fun aJourneyCutShortGoesOnFromWhereTheFrameWas() {
        show("a")
        compose.mainClock.autoAdvance = false
        compose.press(Key.DirectionRight)
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val cut = frame().center.x
        compose.press(Key.DirectionRight)
        compose.mainClock.advanceTimeByFrame()
        val next = frame().center.x
        assertTrue("from $cut, it goes on at $next", next >= cut - 0.5f && next < centre("a2").x)
        compose.mainClock.advanceTimeBy(HlMotion.FOCUS_IN.toLong() + HlMotion.FOCUS_OUT)
        assertEquals(centre("a2").x, frame().center.x, 0.5f)
    }

    @Test
    fun theFrameGoesOutInPlaceWhenFocusLeavesTheRow() {
        show("a", "b")
        compose.press(Key.DirectionDown)
        assertEquals(0f, checkNotNull(frames["a"]).shown, 0f)
        assertEquals("it stays where it went out", centre("a0").x, frame("a").center.x, 0.5f)
        assertEquals(1f, checkNotNull(frames["b"]).shown, 0f)
        assertEquals(centre("b0").y, frame("b").center.y, 0.5f)

        // Coming back on another card than the one it went out on, it lights there, from nowhere.
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { requesters.getValue("a2").requestFocus() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("without travelling", centre("a2").x, frame("a").center.x, 0.5f)
        assertTrue("it is lighting up", checkNotNull(frames["a"]).shown < 1f)
        compose.mainClock.advanceTimeBy(HlMotion.FOCUS_IN.toLong())
        assertEquals(1f, checkNotNull(frames["a"]).shown, 0f)
        assertEquals(centre("a2").x, frame("a").center.x, 0.5f)
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
        assertEquals(frameWidth, frame().width, 0.5f)
    }
}
