package app.houselights.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What grows with focus stays inside the room its container keeps for it. */
class FocusReachTest {
    /** A poster's height from its width: 2:3. */
    private val posterHeight = 1.5f

    @Test
    fun rowsDoNotGrow() {
        assertEquals(0f, HlFocus.reach(HlLayout.readable, HlFocus.ROW_SCALE).value, 0f)
    }

    @Test
    fun aPanelStaysCloseToItsColumn() {
        // At the widest reading column, a panel that can be chosen grows less than a small gap...
        assertTrue(HlFocus.reach(HlLayout.readable, HlFocus.PANEL_SCALE) <= HlSpace.xs)
        // ...where the scale of a pill would take a row past its inset: why rows keep their size.
        assertTrue(HlFocus.reach(HlLayout.readable, HlFocus.CONTROL_SCALE) > HlSpace.xs)
    }

    @Test
    fun aRowOfCardsHoldsWhatGrows() {
        // MediaRow keeps HlSpace.s above and below its cards.
        assertTrue(HlFocus.reach(HlSize.poster * posterHeight, HlFocus.ARTWORK_SCALE) <= HlSpace.s)
        assertTrue(HlFocus.reach(HlLayout.posterChoice * posterHeight, HlFocus.CARD_SCALE) <= HlSpace.s)
    }

    @Test
    fun focusStaysClearOfTheFades() {
        // A list keeps focus HlFocus.edge from its ends: past the fade of what scrolls.
        assertTrue(HlSpace.fadeEdge <= HlFocus.edge)
    }
}
