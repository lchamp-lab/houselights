package app.houselights.ui.design.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Logos are set at a constant weight: their transparent margin trimmed, then sized so that each
 * carries the same optical surface in its room, never past the room, never enlarged.
 */
class EvenLogoTest {
    private val room = 600 to 120

    /** A block of ink [w] by [h] px at ([x], [y]). */
    private class Block(val x: Int, val y: Int, val w: Int, val h: Int)

    /** A logo [w] by [h] px, its [ink] a block, [coverage] of it opaque. */
    private fun logo(w: Int, h: Int, ink: Block, coverage: Double = 1.0): IntArray {
        val pixels = IntArray(w * h)
        var laid = 0.0
        for (py in ink.y until ink.y + ink.h) for (px in ink.x until ink.x + ink.w) {
            // Strokes as stripes: [coverage] of the columns are ink, the first and last always.
            val column = px - ink.x
            laid += coverage
            val stroke = column == 0 || column == ink.w - 1 || laid >= 1.0
            if (stroke) laid -= 1.0
            if (stroke) pixels[py * w + px] = 0xFF000000.toInt() or 0xFFFFFF
        }
        return pixels
    }

    private fun plan(pixels: IntArray, w: Int, h: Int) = planLogo(pixels, w, h, room.first, room.second)

    @Test
    fun itsMarginIsTrimmed() {
        val plan = plan(logo(900, 300, Block(40, 60, 800, 160)), 900, 300)!!
        assertEquals(40, plan.left)
        assertEquals(60, plan.top)
        assertEquals(840, plan.right)
        assertEquals(220, plan.bottom)
    }

    @Test
    fun aHeavyLogoAndAThinOneWeighTheSame() {
        // Both well inside the room: neither is held by its edges.
        val heavy = plan(logo(1000, 300, Block(0, 0, 900, 150)), 1000, 300)!!
        val thin = plan(logo(1000, 300, Block(0, 0, 900, 150), coverage = 0.25), 1000, 300)!!
        assertTrue("the thin one is drawn larger", thin.width > heavy.width)
        val heavyWeight = heavy.width * heavy.height * 1.0
        val thinWeight = thin.width * thin.height * thinCoverage(thin).pow(INK_WEIGHT)
        assertEquals(1.0, thinWeight / heavyWeight, 0.06)
        assertEquals("the same shape", heavy.width.toDouble() / heavy.height, thin.width.toDouble() / thin.height, 0.05)
    }

    /** The share of ink of a striped logo laid at a quarter, as [planLogo] reads it. */
    private fun thinCoverage(plan: LogoPlan) = 0.25 + 2.0 / (plan.right - plan.left)

    @Test
    fun itNeverLeavesTheRoom() {
        // Nearly all air: it would grow past the room.
        val plan = plan(logo(1800, 300, Block(0, 0, 1800, 120), coverage = 0.05), 1800, 300)!!
        assertTrue(plan.width <= room.first && plan.height <= room.second)
    }

    @Test
    fun itIsNeverEnlargedPastItsOwnPixels() {
        val plan = plan(logo(120, 40, Block(0, 0, 120, 40), coverage = 0.2), 120, 40)!!
        assertTrue("${plan.width} px from 120", plan.width <= 120 && plan.height <= 40)
    }

    @Test
    fun aLogoWithoutInkIsLeftAsItIs() {
        assertNull(plan(IntArray(100 * 50), 100, 50))
    }
}
