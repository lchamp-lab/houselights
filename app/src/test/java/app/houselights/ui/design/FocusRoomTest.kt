package app.houselights.ui.design

import org.junit.Assert.assertTrue
import org.junit.Test

/** The room focus takes: a frame bolder than a border, inside what a row and a wall keep for it, clear of its neighbours and fades. */
class FocusRoomTest {
    @Test
    fun theFrameIsBolderThanABorder() {
        assertTrue(HlFocus.frame > HlStroke.ring)
    }

    @Test
    fun aRowAndAWallHoldTheFrame() {
        // MediaRow keeps HlFocus.room above and below its cards, a wall HlFocus.wallRoom above its first line.
        assertTrue(HlFocus.reach <= HlFocus.room)
        assertTrue(HlFocus.reach <= HlFocus.wallRoom)
    }

    @Test
    fun aFrameStaysNearerItsCardThanItsNeighbour() {
        // Cards of a row are HlSpace.s apart at the closest (people), a wall's posters its gutter.
        listOf(HlSpace.s, HlSpace.m, HlLayout.wallGutter).forEach { apart ->
            assertTrue("cards $apart apart", HlFocus.reach * 2 <= apart)
        }
    }

    @Test
    fun focusStaysClearOfTheFades() {
        // A list keeps focus HlFocus.edge from its ends: past the fade of what scrolls.
        assertTrue(HlSpace.fadeEdge <= HlFocus.edge)
    }
}
