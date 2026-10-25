package app.houselights.ui

import app.houselights.ui.detail.backReturnsToActions
import app.houselights.ui.library.backReturnsToFirst
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where Back takes focus before it leaves a screen: from deep in it, back to where it starts; home's grid asks at once (QuitAgainTest). */
class BackStepsTest {

    /** A wall: past its first line, Back returns to its first poster; from it, or the menus, it leaves (PagesNavigationTest). */
    @Test
    fun theWallReturnsToItsFirstPosterFromPastTheFirstLine() {
        assertFalse(backReturnsToFirst(focusedIndex = 0))
        assertFalse("still the first line", backReturnsToFirst(focusedIndex = 6))
        assertTrue(backReturnsToFirst(focusedIndex = 7))
        assertFalse("the filter or sort button: Back leaves", backReturnsToFirst(focusedIndex = null))
    }

    @Test
    fun theDetailPageReturnsToItsActionsOnceScrolled() {
        assertFalse(backReturnsToActions(firstVisibleItem = 0, offset = 0))
        assertTrue(backReturnsToActions(firstVisibleItem = 0, offset = 120))
        assertTrue(backReturnsToActions(firstVisibleItem = 2, offset = 0))
    }
}
