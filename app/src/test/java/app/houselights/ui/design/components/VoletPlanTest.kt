package app.houselights.ui.design.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HlSize
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
        val poster = POLYPTYCH_HEIGHT * HlSize.POSTER_RATIO
        assertEquals("one film: its poster whole", poster.value, closed(1).value, 0.01f)
        assertTrue("the open panel takes the rest", plan(1).open > VoletDefaults.open)
        (2..8).forEach { films ->
            assertEquals("$films films open on a hero's title box", VoletDefaults.open, plan(films).open)
            assertTrue("$films films: a poster cut, never more", closed(films) < poster)
            assertTrue("$films films: narrower than ${films - 1}", closed(films) < closed(films - 1))
        }
        (1..8).forEach { films ->
            val plan = plan(films)
            assertEquals("$films films fill the polyptych", POLYPTYCH.value, (plan.open + (plan.closed + plan.gap) * films).value, 0.01f)
            assertFalse("$films films do not scroll", plan.scrolls)
            assertTrue("$films films: no narrower than a spine", plan.closed >= VoletDefaults.narrowest)
        }
        assertEquals("Bond: the narrowest", VoletDefaults.narrowest.value, closed(25).value, 0f)
        assertTrue("past eight films they scroll", plan(9).scrolls && plan(25).scrolls)
        assertEquals(VoletDefaults.open, plan(25).open)
    }

    @Test
    fun numbersByLength() {
        (1..3).forEach { assertEquals("$it films: the title's signature", VoletNumbers.Signature, plan(it).numbers) }
        (4..5).forEach { assertEquals("$it films: display", VoletNumbers.Display, plan(it).numbers) }
        (6..25).forEach { assertEquals("$it films: headline", VoletNumbers.Headline, plan(it).numbers) }
        assertEquals(VoletNumbers.Signature, VoletPlan.numbersAt(VoletDefaults.numberLarge))
        assertEquals(VoletNumbers.Display, VoletPlan.numbersAt(VoletDefaults.numberLarge - 0.1.dp))
        assertEquals(VoletNumbers.Display, VoletPlan.numbersAt(VoletDefaults.numberMedium))
        assertEquals(VoletNumbers.Headline, VoletPlan.numbersAt(VoletDefaults.numberMedium - 0.1.dp))
    }

    @Test
    fun theYearsStayWhileTheyStandApart() {
        fun keeps(year: Dp, films: Int = EIGHT_FILMS) = VoletPlan.of(films, POLYPTYCH, POLYPTYCH_HEIGHT, year = year).years
        // Measured on the board: a year is about 42 dp wide at text size 1.3, 11 apart under 45 dp panels.
        assertTrue(keeps(42.dp))
        val widest = plan(8).closed + plan(8).gap - VoletDefaults.yearsApart
        assertTrue("touching at the least room", keeps(widest))
        assertFalse("past it, every closed panel drops its year", keeps(widest + 0.5.dp))
        assertFalse(keeps(50.dp))
        assertTrue("a triptych keeps years far wider", keeps(120.dp, films = 4))
        assertFalse(VoletPlan.yearsFit(45.dp, 8.dp, 49.5.dp))
    }

    @Test
    fun aSagaAloneIsOnePanel() {
        val alone = VoletPlan.of(1, POLYPTYCH, POLYPTYCH_HEIGHT)
        assertEquals(POLYPTYCH, alone.open)
        assertFalse(alone.scrolls)
    }

    @Test
    fun aShortPolyptychKeepsItsLeastWidth() {
        // Shorter than a poster at the narrowest: the panels keep it, never an impossible range.
        assertEquals(VoletDefaults.narrowest, VoletPlan.of(EIGHT_FILMS, POLYPTYCH, 40.dp).closed)
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

    private fun plan(films: Int) = voletPlan(films)

    private fun closed(films: Int): Dp = plan(films).closed

    private companion object {
        /** Mission : Impossible: eight films and the saga's panel. */
        const val EIGHT_FILMS = 9

        /** The screen's height, which the top veil is drawn against (dp). */
        const val SCREEN = HlScrim.SCREEN_HEIGHT
    }
}
