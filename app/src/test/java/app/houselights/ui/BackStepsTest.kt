package app.houselights.ui

import app.houselights.ui.detail.backReturnsToActions
import app.houselights.ui.home.backGoesUp
import app.houselights.ui.library.backReturnsToFirst
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where Back takes focus before it leaves a screen: from deep in it, back to where it starts. */
class BackStepsTest {

    @Test
    fun homeGoesUpToTheBarFromBelowTheFirstRow() {
        assertTrue(backGoesUp(focusedRow = 2))
        assertFalse("the first row: Back asks before leaving (QuitAgainTest)", backGoesUp(focusedRow = 0))
        assertFalse("the bar: Back asks before leaving", backGoesUp(focusedRow = null))
    }

    /** A wall opened filtered, without the bar; under the bar, Back goes to it (BarFoldTest). */
    @Test
    fun theWallReturnsToItsFirstPosterFromPastTheFirstLine() {
        assertFalse(backReturnsToFirst(focusedIndex = 0))
        assertFalse("still the first line", backReturnsToFirst(focusedIndex = 6))
        assertTrue(backReturnsToFirst(focusedIndex = 7))
        assertFalse("the filter or sort button: Back goes home", backReturnsToFirst(focusedIndex = null))
    }

    @Test
    fun theDetailPageReturnsToItsActionsOnceScrolled() {
        assertFalse(backReturnsToActions(firstVisibleItem = 0, offset = 0))
        assertTrue(backReturnsToActions(firstVisibleItem = 0, offset = 120))
        assertTrue(backReturnsToActions(firstVisibleItem = 2, offset = 0))
    }
}
