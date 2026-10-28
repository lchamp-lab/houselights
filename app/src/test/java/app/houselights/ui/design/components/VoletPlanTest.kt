package app.houselights.ui.design.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.VoletDefaults
import app.houselights.ui.design.alphaAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A saga's polyptych planned on the canvas (« Les volets »): the open panel and the closed ones by the saga's length,
 * filling the polyptych's width to eight films, the numbers' one size by the closed panels' width, and the years kept
 * while they stand apart, else dropped for every closed panel at once (« greffe 2 »).
 */
class VoletPlanTest {
    @Test
    fun widthsByLength() {
        assertEquals("one film: its poster whole", 247f, closed(1).value, 0.01f)
        assertEquals("the open panel takes the rest", 598f, plan(1).open.value, 0.01f)
        assertEquals(204.5f, closed(2).value, 0.01f)
        assertEquals("Dune, a triptych", 133.67f, closed(3).value, 0.01f)
        assertEquals("John Wick", 98.25f, closed(4).value, 0.01f)
        assertEquals("Avatar", 77f, closed(5).value, 0.01f)
        assertEquals("Mission : Impossible, a shelf of spines", 45.125f, closed(8).value, 0.01f)
        assertEquals("Bond: the narrowest", VoletDefaults.narrowest.value, closed(25).value, 0f)
        (1..8).forEach { films ->
            val plan = plan(films)
            assertEquals(
                "$films films open on a hero's title box",
                if (films == 1) 598f else VoletDefaults.open.value,
                plan.open.value,
                0.01f,
            )
            assertEquals("$films films fill the polyptych", WIDTH.value, (plan.open + (plan.closed + plan.gap) * films).value, 0.01f)
            assertFalse("$films films do not scroll", plan.scrolls)
        }
        assertTrue("past eight films they scroll", plan(9).scrolls && plan(25).scrolls)
        assertEquals(VoletDefaults.open, plan(25).open)
    }

    @Test
    fun theOpenPanelHoldsAHerosWords() {
        assertEquals(428f, VoletDefaults.open.value, 0f)
        assertEquals(8f, VoletDefaults.gap.value, 0f)
    }

    @Test
    fun numbersByLength() {
        (1..3).forEach { assertEquals("$it films: 72", VoletNumbers.Signature, plan(it).numbers) }
        (4..5).forEach { assertEquals("$it films: 48", VoletNumbers.Display, plan(it).numbers) }
        (6..25).forEach { assertEquals("$it films: 36", VoletNumbers.Headline, plan(it).numbers) }
        assertEquals(VoletNumbers.Signature, VoletPlan.numbersAt(VoletDefaults.numberLarge))
        assertEquals(VoletNumbers.Display, VoletPlan.numbersAt(VoletDefaults.numberLarge - 0.1.dp))
        assertEquals(VoletNumbers.Display, VoletPlan.numbersAt(VoletDefaults.numberMedium))
        assertEquals(VoletNumbers.Headline, VoletPlan.numbersAt(VoletDefaults.numberMedium - 0.1.dp))
    }

    @Test
    fun theYearsStayWhileTheyStandApart() {
        // Measured on the board: a year is about 42 dp wide at text size 1.3, 11 apart under 45 dp panels.
        assertTrue(VoletPlan.of(EIGHT_FILMS, WIDTH, HEIGHT, year = 42.dp).years)
        val widest = plan(8).closed + plan(8).gap - VoletDefaults.yearsApart
        assertTrue("touching at the least room", VoletPlan.of(EIGHT_FILMS, WIDTH, HEIGHT, year = widest).years)
        assertFalse("past it, every closed panel drops its year", VoletPlan.of(EIGHT_FILMS, WIDTH, HEIGHT, year = widest + 0.5.dp).years)
        assertFalse(VoletPlan.of(EIGHT_FILMS, WIDTH, HEIGHT, year = 50.dp).years)
        assertTrue("a triptych keeps years far wider", VoletPlan.of(4, WIDTH, HEIGHT, year = 120.dp).years)
        assertFalse(VoletPlan.yearsFit(45.dp, 8.dp, 49.5.dp))
    }

    @Test
    fun aSagaAloneIsOnePanel() {
        val alone = VoletPlan.of(1, WIDTH, HEIGHT)
        assertEquals(WIDTH, alone.open)
        assertFalse(alone.scrolls)
    }

    @Test
    fun aShortPolyptychKeepsItsLeastWidth() {
        // Shorter than a poster at the narrowest: the panels keep it, never an impossible range.
        assertEquals(VoletDefaults.narrowest, VoletPlan.of(EIGHT_FILMS, WIDTH, 40.dp).closed)
    }

    /**
     * A number's floor over its band melts back onto the top veil over `VoletDefaults.melt` under it, never in a few dp
     * (DESIGN.md, Veils: no veil draws a line); a band the veil already covers leaves it as it is.
     */
    @Test
    fun aNumbersFloorMeltsBackOntoTheTopVeil() {
        val melt = VoletDefaults.melt.value
        listOf(56f, 64f, 88f).forEach { band ->
            listOf(0.3f, 0.6f, 0.9f).forEach { floor ->
                val veil = HlScrim.TOP.raisedOver(band / SCREEN, floor, melt / SCREEN)
                fun at(dp: Float) = veil.alphaAt(dp / SCREEN)
                (0..band.toInt()).forEach { assertTrue("$floor over the band of $band at $it", at(it.toFloat()) >= floor - 0.001f) }
                (0..SCREEN.toInt()).filter { it >= band + melt }.forEach {
                    assertEquals("on the top veil again at $it", HlScrim.TOP.alphaAt(it / SCREEN), at(it.toFloat()), 0.005f)
                }
                // Never faster than the floor gone in half the melt.
                (band.toInt() until (band + melt).toInt()).forEach {
                    val fall = at(it.toFloat()) - at(it + 1f)
                    assertTrue("$floor under $band falls $fall at $it", fall <= floor / (melt / 2) + 0.001f)
                }
            }
        }
        assertEquals("a floor the veil holds", HlScrim.TOP, HlScrim.TOP.raisedOver(56f / SCREEN, 0.2f, melt / SCREEN))
    }

    private fun plan(films: Int) = VoletPlan.of(films + 1, WIDTH, HEIGHT)

    private fun closed(films: Int): Dp = plan(films).closed

    private companion object {
        /** The polyptych on the canvas: the safe area less a focused frame's reach each side, above the predella. */
        val WIDTH = 853.dp
        val HEIGHT = 370.5.dp

        /** Mission : Impossible: eight films and the saga's panel. */
        const val EIGHT_FILMS = 9

        /** The screen's height, which the top veil is drawn against (dp). */
        const val SCREEN = HlScrim.SCREEN_HEIGHT
    }
}
