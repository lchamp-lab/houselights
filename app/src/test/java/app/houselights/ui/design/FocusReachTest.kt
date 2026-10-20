package app.houselights.ui.design

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focus frames and glows without growing: its frame stays inside the room its container keeps for it. */
class FocusReachTest {
    /** How far a lazy row draws past its bounds across its axis (Compose's `MaxSupportedElevation`). */
    private val rowDrawsPast = 15.dp

    @Test
    fun theFrameStandsOffWhatItFrames() {
        // The gap, then the stroke, whatever the size of what it frames.
        assertEquals((HlFocus.frameGap + HlFocus.frame).value, HlFocus.reach.value, 0f)
        assertTrue(HlFocus.frame > HlSize.focusBorder)
    }

    @Test
    fun aRowHoldsTheFrameAndTheGlow() {
        // MediaRow keeps HlFocus.room above and below its cards, on the grid.
        assertTrue(HlFocus.reach <= HlFocus.room)
        assertEquals(0f, HlFocus.room.value % 4f, 0f)
        // The glow's last ring is clear: what lights reaches one ring short of HlFocus.glow.
        val lit = HlFocus.glow * (HlScrim.GLOW_STEPS - 1) / HlScrim.GLOW_STEPS
        assertTrue("the glow lights $lit past a card", HlFocus.room + rowDrawsPast >= lit)
    }

    @Test
    fun aFrameStaysNearerItsCardThanItsNeighbour() {
        // Cards of a row are HlSpace.m apart, a wall's posters its gutter.
        assertTrue(HlFocus.reach * 2 <= HlSpace.m)
        assertTrue(HlFocus.reach * 2 <= HlLayout.wallGutter)
        // A wall keeps HlSpace.m above its first line.
        assertTrue(HlFocus.reach <= HlSpace.m)
    }

    @Test
    fun sevenPostersFillAWall() {
        val across = HlSize.wallPoster * 7 + HlLayout.wallGutter * 6
        assertEquals(HlLayout.CANVAS_WIDTH - 2 * HlSpace.safeH.value, across.value, 0.01f)
    }

    @Test
    fun focusStaysClearOfTheFades() {
        // A list keeps focus HlFocus.edge from its ends: past the fade of what scrolls.
        assertTrue(HlSpace.fadeEdge <= HlFocus.edge)
    }
}
