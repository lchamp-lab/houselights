package app.houselights.ui.design.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HomeGridDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's grid on the TV canvas (DESIGN.md, Layout): doors that line up, focus that keeps its column, one figure size. */
class DoorGridTest {
    private val gap = HlSpace.s.value
    private val heights = listOf(136f, 148f, 136f)

    private fun widths(vararg spans: Int) = placeDoors(listOf(spans.toList()), listOf(100f), gap).first().map { it.width }

    @Test
    fun aRowSharesItsWidthByPartsOfFour() {
        assertEquals(listOf(294f, 294f), widths(2, 2))
        assertEquals(listOf(141f, 141f, 294f), widths(1, 1, 2))
        assertEquals(listOf(141f, 141f, 141f, 141f), widths(1, 1, 1, 1))
        assertEquals(listOf(192f, 192f, 192f), widths(1, 1, 1))
        assertEquals(listOf(600f), widths(4))
        assertEquals(listOf(600f), widths(1))
    }

    @Test
    fun doorsOfDifferentRowsLineUp() {
        val rows = placeDoors(listOf(listOf(2, 2), listOf(1, 1), listOf(1, 1, 2)), heights, gap)
        assertEquals("the second half starts alike", rows[0][1].left, rows[1][1].left)
        assertEquals("Search ends where Films does", rows[1][0].right, rows[2][1].right)
        assertEquals(rows[0][1].left, rows[2][2].left)
        assertEquals("rows stack with their gaps", 136f + gap, rows[1][0].top)
        assertEquals(doorWidth(2).value, rows[0][0].width)
    }

    @Test
    fun upAndDownGoToTheDoorTheyOverlap() {
        val rows = placeDoors(listOf(listOf(2, 2), listOf(1, 1), listOf(1, 1, 2)), heights, gap)
        // Nouveautés over Séries, Séries over Réglages: one door each.
        assertEquals(1, doorBelow(rows[0][1], rows[1], emptyList()))
        assertEquals(2, doorBelow(rows[1][1], rows[2], emptyList()))
        // Films over Sagas and Rechercher: Sagas, then the one last visited.
        assertEquals(0, doorBelow(rows[1][0], rows[2], emptyList()))
        assertEquals(1, doorBelow(rows[1][0], rows[2], listOf(0, 1)))
        assertEquals(0, doorBelow(rows[1][0], rows[2], listOf(1, 0)))
        // A door last visited elsewhere does not pull focus out of the column.
        assertEquals(0, doorBelow(rows[1][0], rows[2], listOf(2)))
    }

    @Test
    fun aThirdOfTheRowGoesToTheDoorItOverlapsMost() {
        val rows = placeDoors(listOf(listOf(2, 2), listOf(1, 1, 1)), heights, gap)
        assertEquals(0, doorBelow(rows[0][0], rows[1], emptyList()))
        assertEquals("the middle third leans on both halves, the first wins a tie", 0, doorBelow(rows[1][1], rows[0], emptyList()))
        assertEquals(1, doorBelow(rows[1][1], rows[0], listOf(1)))
        assertNull(doorBelow(Rect(1000f, 0f, 1100f, 10f), rows[0], emptyList()))
    }

    @Test
    fun everyLibraryDoorSharesOneFigureSize() {
        val box = Size(294f, 110f)
        // At 100 px, "412" is 120 wide and "38" 80: the height bounds both, at 120.
        assertEquals(120f, figureSize(listOf(120f, 80f), listOf(box, box), 100f, 14f, 13f), 0.01f)
        // A longer count in a narrower door decides for all.
        val narrow = Size(141f, 110f)
        val size = figureSize(listOf(120f, 200f), listOf(box, narrow), 100f, 14f, 13f)
        assertEquals((141f - 28f) / 200f * 100f, size, 0.01f)
    }

    @Test
    fun fourDigitsFitTwoDoors() {
        // "1 204" at 100 px, a condensed count with its space: about 160 wide.
        val box = Size(294f, 110f)
        val size = figureSize(listOf(160f, 120f), listOf(box, box), 100f, 14f, 13f)
        assertTrue("$size leaves air across", 160f * size / 100f <= 294f - 28f)
        assertTrue("$size leaves air down", size * 0.7f <= 110f - 26f + 0.01f)
    }

    @Test
    fun theRowsTogetherAreAsTallAsTheCase() {
        val case = caseHeight()
        listOf(true to true, false to true, true to false, false to false).forEach { (watch, libraries) ->
            val rows = gridHeights(watch, libraries)
            val total = rows.fold(0.dp) { sum, h -> sum + h } + HlSpace.s * (rows.size - 1)
            assertEquals("watch $watch, libraries $libraries", case, total)
        }
        assertEquals("the safe area under the head", 444.dp, case)
        assertEquals(540.dp - HlSpace.safeV, HlSpace.safeV + HomeGridDefaults.head + HomeGridDefaults.headGap + case)
    }

    @Test
    fun theSearchWallFillsItsDoor() {
        assertEquals(8, wallThumbs(141.dp, 98.dp))
        assertEquals(16, wallThumbs(294.dp, 98.dp))
    }

    @Test
    fun aTintAveragesItsPostersSideBySide() {
        val red = Points(IntArray(16) { RED }, 4, 4)
        val blue = Points(IntArray(16) { BLUE }, 4, 4)
        val strip = averagedStrip(listOf(red, blue), 4, 2)
        assertEquals(listOf(RED, RED, BLUE, BLUE, RED, RED, BLUE, BLUE), strip.toList())
    }

    @Test
    fun theStatesShortFactsShareALineWhileTheyFit() {
        assertEquals(listOf(listOf(0, 1), listOf(2)), factLines(listOf(false, false, true), listOf(100, 80, 200), 28, 270))
        val apart = factLines(listOf(false, false, true), listOf(150, 120, 200), 28, 270)
        assertEquals("too wide side by side", listOf(listOf(0), listOf(1), listOf(2)), apart)
        assertEquals(listOf(listOf(0), listOf(1)), factLines(listOf(false, true), listOf(100, 200), 28, 270))
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
