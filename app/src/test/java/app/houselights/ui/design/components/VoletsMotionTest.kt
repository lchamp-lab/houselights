package app.houselights.ui.design.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.width
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.design.VoletDefaults
import app.houselights.ui.testing.TV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A saga's polyptych as a panel opens, frame by frame: a move cut short goes on from where every panel stood, the
 * total kept, and a long saga's scroll moves with the widths, never a panel's step at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class VoletsMotionTest {
    @get:Rule
    val compose = createComposeRule()

    private var open by mutableIntStateOf(1)

    /** Dune's polyptych, or [films] of them, film [first] open, at rest; then the clock is the test's. */
    private fun show(films: Int, first: Int) {
        open = first
        compose.setContent { HouselightsTheme { VoletsOnTheCanvas(parts(films), open) } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun panel(i: Int): DpRect = compose.onNodeWithContentDescription(panelName(i)).getUnclippedBoundsInRoot()

    private fun widths(films: Int) = (0..films).map { panel(it).width.value }

    /** Panel [i] opened, as focus reaching it opens it: the change seen before the next frame. */
    private fun opens(i: Int) {
        open = i
        compose.waitForIdle()
    }

    /** The clock moved on by [ms], a frame at a time. */
    private fun forward(ms: Long) = repeat((ms / FRAME_MS).toInt()) { compose.mainClock.advanceTimeByFrame() }

    /**
     * Turned back to the panel it is leaving (Back just after an arrow), the one closing reopens from where it stood and
     * the one opening closes again.
     */
    @Test
    fun aMoveTurnedBackGoesOnFromWhereEveryPanelStood() {
        show(DUNE, first = 1)
        opens(2)
        forward(CUT_MS)
        val before = widths(DUNE)
        assertTrue(
            "halfway: $before",
            before[1] in closed(DUNE) + 20f..VOLET_OPEN - 20f && before[2] in closed(DUNE) + 20f..VOLET_OPEN - 20f,
        )
        opens(1)
        var last = before
        repeat(FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            val now = widths(DUNE)
            // The frame the move is turned in, it goes on as it went; from the next, it turns.
            assertTrue("frame $it: film 1 never shut, at ${now[1]}", now[1] > closed(DUNE) + 20f)
            assertTrue("frame $it: film 2 never whole at once, at ${now[2]}", now[2] < VOLET_OPEN - 20f)
            if (it > 0) {
                assertTrue("frame $it: film 1 reopens from ${last[1]}, at ${now[1]}", now[1] >= last[1] - 1f)
                assertTrue("frame $it: film 2 closes from ${last[2]}, at ${now[2]}", now[2] <= last[2] + 1f)
            }
            assertEquals("frame $it: the total kept", POLYPTYCH.value, panel(DUNE).right.value - panel(0).left.value, 1f)
            last = now
        }
        forward(HlMotion.FOCUS_IN.toLong())
        assertEquals(VOLET_OPEN, panel(1).width.value, 1f)
        assertEquals(closed(DUNE), panel(2).width.value, 1f)
    }

    /** On to the next before the move ends, the panel still closing goes on closing, never shut at once. */
    @Test
    fun aMoveCutShortLetsThePanelClosingFinish() {
        show(DUNE, first = 1)
        opens(2)
        forward(CUT_MS)
        val closing = panel(1).width.value
        assertTrue("film 1 halfway closed: $closing", closing in closed(DUNE) + 20f..VOLET_OPEN - 20f)
        opens(3)
        repeat(FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            val now = panel(1).width.value
            assertTrue("frame $it: film 1 closes on from $closing, at $now", now > closed(DUNE) + 1f && now <= closing + 1f)
        }
        forward(HlMotion.FOCUS_IN.toLong())
        assertEquals(closed(DUNE), panel(1).width.value, 1f)
        assertEquals(closed(DUNE), panel(2).width.value, 1f)
        assertEquals(VOLET_OPEN, panel(3).width.value, 1f)
    }

    /** Past eight films the polyptych scrolls as the widths move, a little each frame, never a panel's step at once. */
    @Test
    fun aLongSagaScrollsWithTheWidths() {
        show(BOND, first = 10)
        val step = closed(BOND) + GAP
        var left = panel(10).left.value
        opens(11)
        repeat((HlMotion.FOCUS_IN / FRAME_MS + 2).toInt()) {
            compose.mainClock.advanceTimeByFrame()
            val now = panel(10).left.value
            assertTrue("frame $it: the row moved ${left - now}", left - now < step / 2)
            left = now
        }
        val reached = panel(11)
        assertEquals(VOLET_OPEN, reached.width.value, 1f)
        assertTrue("it scrolled: $reached", reached.right.value <= (VOLET_SIDE + POLYPTYCH).value - step * 2 + 1f)
    }

    private fun closed(films: Int) = voletPlan(films).closed.value

    private fun parts(films: Int) = voletParts(films, spine = "Dune") { VoletFilm(year = "${1990 + it}") }

    private companion object {
        val VOLET_OPEN = VoletDefaults.open.value
        val GAP = VoletDefaults.gap.value

        const val DUNE = 3
        const val BOND = 25

        /** The test clock's frame. */
        const val FRAME_MS = 16L

        /** A move cut this long after it began, as Back just after an arrow cuts it: the panels halfway. */
        const val CUT_MS = 64L

        /** Frames watched after a move is cut short. */
        const val FRAMES = 4
    }
}
