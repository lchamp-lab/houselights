package app.houselights.ui.design.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.setObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.houselights.R
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.Scrub
import app.houselights.ui.design.ScrubTracker
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.drawWindow
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
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
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import kotlin.math.abs
import kotlin.math.roundToInt

/** DESIGN.md, Focus: a wall draws one frame, which travels from the card left to the card reached, across lines too. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
@OptIn(ExperimentalComposeRuntimeApi::class)
class GridFrameTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var wall: GroupFrame
    private lateinit var row: GroupFrame
    private val state = LazyGridState()
    private val requesters = mutableMapOf<String, FocusRequester>()

    /** Scopes recomposed anywhere in the screen, the wall's cards and their surfaces included. */
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

    /** A sheet over the wall, which OK on a card opens. */
    private var sheetOpen by mutableStateOf(false)

    /**
     * A wall of eight lines of seven cells, "w0" to "w55", [below] a gap from the screen's top, with a row "b" under it
     * when [withRow]; focus on the first cell. OK on a cell opens a sheet.
     */
    @OptIn(InternalComposeApi::class)
    private fun show(below: Dp = 0.dp, withRow: Boolean = false) {
        // The app's window is the room's black (its theme's windowBackground) under every screen.
        compose.runOnUiThread { compose.activity.window.setBackgroundDrawableResource(R.color.room) }
        compose.setTvContent {
            val composition = currentComposer.composition
            DisposableEffect(composition) {
                val handle = composition.setObserver(counting)
                onDispose { handle?.dispose() }
            }
            Column {
                Spacer(Modifier.height(below))
                Box(Modifier.weight(1f).testTag(WALL)) {
                    PosterWallGrid(state) {
                        items((0 until LINES * COLUMNS).map { "w$it" }) { cell ->
                            wall = checkNotNull(LocalGroupFrame.current)
                            ArtworkSurface(
                                onClick = { sheetOpen = true },
                                modifier = Modifier.focusRequester(requester(cell)).testTag(cell).fillMaxWidth().aspectRatio(2f / 3f),
                            ) {}
                        }
                    }
                }
                if (withRow) {
                    MediaRow(header = null) {
                        items((0 until COLUMNS).map { "b$it" }) { card ->
                            row = checkNotNull(LocalGroupFrame.current)
                            ArtworkSurface(
                                onClick = {},
                                modifier = Modifier.focusRequester(requester(card)).testTag(card).width(HlSize.poster).aspectRatio(2f / 3f),
                            ) {}
                        }
                    }
                }
            }
            if (sheetOpen) HlSideSheet("Options", onDismiss = { sheetOpen = false }) {}
        }
        focusOn("w0")
    }

    @SuppressLint("RememberInComposition") // The test's own map keeps it across compositions.
    private fun requester(card: String) = requesters.getOrPut(card) { FocusRequester() }

    private fun focusOn(card: String) {
        compose.runOnIdle { requesters.getValue(card).requestFocus() }
        compose.waitForIdle()
    }

    /** [card]'s centre, where the wall cuts it too. */
    private fun centre(card: String): Offset {
        val node = compose.onNodeWithTag(card).fetchSemanticsNode()
        return node.positionInRoot + Offset(node.size.width / 2f, node.size.height / 2f)
    }

    private fun frame(of: GroupFrame = wall) = checkNotNull(of.boundsInRoot()) { "The group frames nothing" }

    /** How far [at] lies off the straight line from [from] to [to]. */
    private fun offLine(at: Offset, from: Offset, to: Offset): Float {
        val way = to - from
        val cross = (at.x - from.x) * way.y - (at.y - from.y) * way.x
        return abs(cross) / way.getDistance()
    }

    private fun strictlyBetween(value: Float, a: Float, b: Float) = value > minOf(a, b) && value < maxOf(a, b)

    @After
    fun letGoOfTheArrow() {
        Scrub.onKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ScrubTracker.SETTLE_MS * 2))
        assertFalse(Scrub.active)
    }

    @Test
    fun theFrameTravelsToTheCardReachedWithoutRecomposing() {
        show()
        assertEquals(centre("w0").x, frame().center.x, 0.5f)

        compose.mainClock.autoAdvance = false
        val before = recomposed
        compose.press(Key.DirectionRight)
        // Focus moving recomposes the two cards it concerns, once.
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val settled = recomposed
        assertTrue("the cards' recomposition is seen", settled > before)
        val from = centre("w0").x
        val to = centre("w1").x
        val midway = frame().center.x
        assertTrue("the frame is on its way at $midway, from $from to $to", midway > from && midway < to)
        compose.mainClock.advanceTimeBy(HlMotion.FOCUS_IN / 2L)
        assertTrue("it keeps going", frame().center.x > midway)

        compose.mainClock.advanceTimeBy(HlMotion.FOCUS_OUT.toLong())
        assertEquals(to, frame().center.x, 0.5f)
        assertEquals("it frames the reached card", centre("w1").y, frame().center.y, 0.5f)
        assertEquals("only drawing and placing follow the frame and the lights", settled, recomposed)
    }

    @Test
    fun aJourneyCutShortGoesOnFromWhereTheFrameWas() {
        show()
        compose.mainClock.autoAdvance = false
        compose.press(Key.DirectionRight)
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val cut = frame().center.x
        compose.press(Key.DirectionRight)
        compose.mainClock.advanceTimeByFrame()
        val next = frame().center.x
        assertTrue("from $cut, it goes on at $next", next >= cut - 0.5f && next < centre("w2").x)
        compose.mainClock.advanceTimeBy(HlMotion.FOCUS_IN.toLong() + HlMotion.FOCUS_OUT)
        assertEquals(centre("w2").x, frame().center.x, 0.5f)
    }

    /** From a line's last cell to the next line's first, the frame goes straight across, never along the lines. */
    @Test
    fun theFrameCrossesALinesEndInAStraightLine() {
        show()
        val last = "w${COLUMNS - 1}"
        val first = "w$COLUMNS"
        focusOn(last)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { requesters.getValue(first).requestFocus() }
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
        val from = centre(last)
        val to = centre(first)
        val at = frame().center
        assertTrue("on its way at $at, from $from to $to", strictlyBetween(at.x, from.x, to.x) && strictlyBetween(at.y, from.y, to.y))
        assertEquals("on the straight line between them", 0f, offLine(at, from, to), 0.5f)
        compose.mainClock.advanceTimeBy(HlMotion.FOCUS_IN.toLong() + HlMotion.FOCUS_OUT)
        assertEquals(to.x, frame().center.x, 0.5f)
        assertEquals(to.y, frame().center.y, 0.5f)
    }

    /** The wall scrolls under the frame on its way, the card it left gone from the composition: it goes on. */
    @Test
    fun theFrameFollowsTheScrollAndGoesOnOnceTheCardLeftIsGone() {
        show()
        val reached = "w${COLUMNS + 3}"
        val way = centre(reached) - centre("w0")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { requesters.getValue(reached).requestFocus() }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        // The second line to the wall's top, a little past it: the first line leaves the composition.
        compose.runOnIdle { state.requestScrollToItem(COLUMNS, SCROLLED) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("w0").assertDoesNotExist()

        val to = centre(reached)
        val from = to - way
        val at = frame().center
        assertTrue("on its way at $at, from $from to $to", strictlyBetween(at.x, from.x, to.x) && strictlyBetween(at.y, from.y, to.y))
        compose.mainClock.advanceTimeBy(HlMotion.FOCUS_IN / 4L)
        assertTrue("it keeps going", (frame().center - to).getDistance() < (at - to).getDistance())

        compose.mainClock.advanceTimeBy(HlMotion.FOCUS_IN.toLong() + HlMotion.FOCUS_OUT)
        assertEquals(to.x, frame().center.x, 0.5f)
        assertEquals(to.y, frame().center.y, 0.5f)
    }

    @Test
    fun theFrameGoesOutInPlaceWhenFocusLeavesTheWall() {
        show(withRow = true)
        focusOn("w2")
        focusOn("b0")
        assertEquals(0f, wall.shown, 0f)
        assertEquals("it stays where it went out", centre("w2").x, frame().center.x, 0.5f)
        assertEquals(1f, row.shown, 0f)
        assertEquals(centre("b0").x, frame(row).center.x, 0.5f)
    }

    /** DESIGN.md, Focus: under a sheet, focus is out. The card that opened it keeps focus, unframed, then lights again. */
    @Test
    fun theFrameGoesOutUnderASheetAndLightsAgainAsItGoes() {
        show()
        compose.press(Key.DirectionDown)
        compose.press(Key.DirectionCenter)
        val card = "w$COLUMNS"
        compose.onNodeWithTag(card).assertIsFocused()
        assertEquals("no frame under the sheet", 0f, wall.shown, 0f)

        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        assertFalse(sheetOpen)
        compose.onNodeWithTag(card).assertIsFocused()
        assertEquals(1f, wall.shown, 0f)
        assertEquals("on the card that opened it", centre(card).y, frame().center.y, 0.5f)
    }

    @Test
    fun aHeldArrowMovesTheFrameAtOnce() {
        show()
        // A held arrow repeats.
        Scrub.onKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 1)
        compose.mainClock.autoAdvance = false
        compose.press(Key.DirectionRight)
        compose.mainClock.advanceTimeByFrame()
        assertEquals(centre("w1").x, frame().center.x, 0.5f)
    }

    /** A card half scrolled out of the wall: its frame is cut where the wall cuts the card, never drawn past it. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theFrameIsCutAtTheWallsEdge() {
        show(below = ABOVE)
        val reached = "w${COLUMNS + 3}"
        focusOn(reached)
        compose.runOnIdle { state.requestScrollToItem(COLUMNS, SCROLLED) }
        compose.waitForIdle()
        val node = compose.onNodeWithTag(WALL).fetchSemanticsNode()
        val window = node.positionInWindow - node.boundsInRoot.topLeft
        val bounds = frame().translate(window)
        val top = node.positionInWindow.y
        assertTrue("the frame reaches past the wall's top: ${bounds.top} above $top", bounds.top < top - PAST)

        val drawn = compose.drawWindow()
        val x = bounds.left.roundToInt()
        val inside = drawn.getPixel(x, (top + PAST).roundToInt())
        val outside = drawn.getPixel(x, ((bounds.top + top) / 2).roundToInt())
        assertTrue("framed inside the wall: ${Integer.toHexString(inside)}", Color.red(inside) > FRAMED)
        assertTrue("cut past it: ${Integer.toHexString(outside)}", Color.red(outside) < UNFRAMED)
    }

    private companion object {
        const val COLUMNS = HlLayout.WALL_COLUMNS
        const val LINES = 8
        const val WALL = "wall"

        /** How far past its start the second line is scrolled, in px. */
        const val SCROLLED = 60

        /** Room over the wall, where a frame cut by it would show. */
        val ABOVE = 100.dp

        /** Pixels from the wall's top edge where the frame is read. */
        const val PAST = 12f

        /** A frame's white over the room's black. */
        const val FRAMED = 192

        /** The room's black, nothing drawn on it. */
        const val UNFRAMED = 32
    }
}
