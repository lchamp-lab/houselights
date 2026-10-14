package app.houselights.ui.design.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** A line of facts keeps the leading ones that fit whole, gaps included, and never a part of one. */
class OneLineTest {

    @Test
    fun keepsWhatFitsWithItsGaps() {
        val widths = listOf(40, 50, 30)
        val gaps = listOf(0, 20, 20)
        assertEquals(3, fitCount(widths, gaps, room = 160))
        assertEquals(2, fitCount(widths, gaps, room = 159))
        assertEquals(2, fitCount(widths, gaps, room = 110))
        assertEquals(1, fitCount(widths, gaps, room = 109))
    }

    @Test
    fun aFirstFactTooLongLeavesNothingWhole() {
        assertEquals(0, fitCount(listOf(200, 10), listOf(0, 20), room = 199))
        assertEquals(0, fitCount(emptyList(), emptyList(), room = 100))
    }

    @Test
    fun aGenreCarryingOnTakesNoGap() {
        // "Drame" then ", Policier" and ", Thriller": the last goes first, whole.
        val widths = listOf(40, 30, 60, 70, 80)
        val gaps = listOf(0, 20, 20, 0, 0)
        assertEquals(5, fitCount(widths, gaps, room = 320))
        assertEquals(4, fitCount(widths, gaps, room = 319))
        assertEquals(3, fitCount(widths, gaps, room = 239))
    }

    @Test
    fun anUnboundedLineKeepsEverything() {
        assertEquals(3, fitCount(listOf(1_000, 2_000, 3_000), listOf(0, 20, 20), room = Int.MAX_VALUE))
    }
}
