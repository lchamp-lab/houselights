package app.houselights.ui.design.components

import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.VoletDefaults
import app.houselights.ui.design.alphaAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A saga's polyptych planned on the canvas (« Les volets »): its closed panels a portrait wide (96) whatever its length,
 * the open one taking what they leave and never under a hero's title box with its insets (428), past which the films
 * scroll by whole panels, the open one two closed panels from either end where it can, the saga's spine pinned at the
 * start once a film opens.
 */
class VoletPlanTest {
    @Test
    fun theClosedPanelsKeepOneWidthWhateverTheLength() {
        assertEquals("a portrait's width", HlSize.portrait, VoletDefaults.closed)
        listOf(1, 2, 3, 4, 5, 9, 25, 50).forEach { films -> assertEquals("$films films", 96f, plan(films).closed.value, 0f) }
    }

    @Test
    fun theOpenPanelTakesWhatTheyLeaveDownTo428() {
        assertEquals("three films", 541f, plan(3).open.value, 0.01f)
        assertEquals("four films", 437f, plan(4).open.value, 0.01f)
        listOf(5, 9, 25, 50).forEach { assertEquals("$it films at its narrowest", VoletDefaults.open, plan(it).open) }
        assertEquals(428f, VoletDefaults.open.value, 0f)
        (1..4).forEach { films ->
            val plan = plan(films)
            assertEquals("$films films fill the polyptych", POLYPTYCH.value, (plan.open + (plan.closed + plan.gap) * films).value, 0.01f)
            assertFalse("$films films do not scroll", plan.scrolls)
        }
        listOf(5, 9, 25, 50).forEach { assertTrue("from five films they scroll: $it", plan(it).scrolls) }
    }

    /** Board A2: film 5 of nine open, films 3 and 4 between it and the spine pinned at the start, 6 whole, 7 at the edge. */
    @Test
    fun theFilmsScrollPastTheSpinePinnedAtTheStart() {
        val plan = plan(9)
        val by = plan.scrollTo(5, from = 0f, room = ROOM)
        assertEquals("two panels scrolled away", 2 * STEP, by, 0.01f)
        assertEquals("the spine at the start", 0f, plan.left(0, 5, by), 0f)
        assertEquals("film 3 past the spine", STEP, plan.left(3, 5, by), 0.01f)
        assertEquals("film 5 open after 3 and 4", 3 * STEP, plan.left(5, 5, by), 0.01f)
        assertTrue("film 6 whole", plan.left(6, 5, by) + CLOSED <= ROOM)
        assertTrue("film 7 past the edge", plan.left(7, 5, by) < ROOM && plan.left(7, 5, by) + CLOSED > ROOM)
        assertEquals("the saga open: nothing scrolled", 0f, plan.scrollTo(0, from = by, room = ROOM), 0f)
        assertEquals("the saga's own panel open on 428, film 1 after it", 428f + 8f, plan.left(1, 0), 0.01f)
    }

    /**
     * Along fifty films and back, each step scrolls by whole panels, the open film two closed panels after the spine
     * where it can, a closed panel whole after it, the last whole within a panel of the end; a saga that fits never scrolls.
     */
    @Test
    fun aLongSagaScrollsByWholePanelsTheOpenOneKeptInView() {
        val plan = plan(FIFTY)
        var by = 0f
        ((1..FIFTY) + (FIFTY - 1 downTo 1)).forEach { at ->
            by = plan.scrollTo(at, by, ROOM)
            assertEquals("film $at: whole panels ($by)", 0f, by % STEP, 0.01f)
            val left = plan.left(at, at, by)
            if (at >= 3) assertTrue("film $at: two closed panels after the spine ($left)", left >= 3 * STEP - 0.01f)
            if (at < FIFTY) assertTrue("film $at: a closed panel whole after it", left + 428f + STEP <= ROOM + 0.01f)
        }
        val last = plan.scrollTo(FIFTY, by, ROOM)
        val end = plan.left(FIFTY, FIFTY, last) + 428f
        assertTrue("the last film whole, within a panel of the polyptych's end: $end", end <= ROOM && end > ROOM - STEP)
        (1..4).forEach { films -> (0..films).forEach { assertEquals(0f, plan(films).scrollTo(it, 0f, ROOM), 0f) } }
    }

    /** A film past the polyptych's end, or under the pinned spine, shows its picture alone: no text cut by the edge. */
    @Test
    fun aPanelStandsWholeOnlyInsideThePolyptych() {
        assertTrue(standsWhole(x = 104f, width = 96f, start = 104f, room = 853f))
        assertTrue("within half a pixel", standsWhole(x = 757.4f, width = 96f, start = 104f, room = 853f))
        assertFalse("past the end", standsWhole(x = 760f, width = 96f, start = 104f, room = 853f))
        assertFalse("under the spine", standsWhole(x = 50f, width = 96f, start = 104f, room = 853f))
    }

    @Test
    fun aSagaAloneIsOnePanel() {
        val alone = VoletPlan.of(1, POLYPTYCH, POLYPTYCH_HEIGHT)
        assertEquals(POLYPTYCH, alone.open)
        assertFalse(alone.scrolls)
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

    private companion object {
        const val FIFTY = 50

        /** The polyptych's width, a closed panel's and a step from one to the next, on the canvas (dp). */
        val ROOM = POLYPTYCH.value
        const val CLOSED = 96f
        const val STEP = CLOSED + 8f

        /** The screen's height, which the top veil is drawn against (dp). */
        const val SCREEN = HlScrim.SCREEN_HEIGHT
    }
}
