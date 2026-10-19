package app.houselights.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What focus grows and frames stays inside the room its container keeps for it. */
class FocusReachTest {
    /** A poster's height from its width: 2:3. */
    private val posterHeight = 1.5f

    @Test
    fun controlsAndRowsDoNotGrow() {
        // Their white plate shows focus: nothing moves.
        assertEquals(1f, HlFocus.CONTROL_SCALE, 0f)
    }

    @Test
    fun theFrameStandsOffWhatItFrames() {
        // Still, a framed element reaches as far as its frame: the gap, then the stroke.
        assertEquals((HlFocus.frameGap + HlFocus.frame).value, HlFocus.reach(HlLayout.readable, HlFocus.CONTROL_SCALE).value, 0f)
        assertTrue(HlFocus.frame > HlSize.focusBorder)
    }

    @Test
    fun picturesGrowByTheirShape() {
        // A poster most, a wide image less, a panel slightly.
        assertTrue(HlFocus.POSTER_SCALE > HlFocus.WIDE_SCALE)
        assertTrue(HlFocus.WIDE_SCALE > HlFocus.PANEL_SCALE)
        assertTrue(HlFocus.PANEL_SCALE > HlFocus.CONTROL_SCALE)
    }

    @Test
    fun aPanelStaysCloseToItsColumn() {
        // At the widest reading column, a panel that can be chosen reaches less than a row's gap past it.
        assertTrue(HlFocus.reach(HlLayout.readable, HlFocus.PANEL_SCALE) <= HlSpace.s)
    }

    @Test
    fun aRowOfCardsHoldsWhatGrows() {
        // MediaRow keeps HlFocus.room above and below its cards.
        assertTrue(HlFocus.reach(HlSize.poster * posterHeight, HlFocus.POSTER_SCALE) <= HlFocus.room)
        assertTrue(HlFocus.reach(HlSize.landscape * 9 / 16, HlFocus.WIDE_SCALE) <= HlFocus.room)
        assertTrue(HlFocus.reach(HlSize.portrait / HlSize.PORTRAIT_RATIO, HlFocus.POSTER_SCALE) <= HlFocus.room)
        // Artwork choices grow as wide images do, whatever their shape.
        assertTrue(HlFocus.reach(HlLayout.posterChoice * posterHeight, HlFocus.WIDE_SCALE) <= HlFocus.room)
    }

    @Test
    fun aWallHoldsWhatGrows() {
        // A wall keeps HlSpace.m above its first line, and its gutter between two posters.
        assertTrue(HlFocus.reach(HlSize.wallPoster * posterHeight, HlFocus.POSTER_SCALE) <= HlSpace.m)
        assertTrue(HlFocus.reach(HlSize.wallPoster, HlFocus.POSTER_SCALE) <= HlLayout.wallGutter)
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
