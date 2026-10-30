package app.houselights.ui.design.components

import androidx.compose.ui.graphics.Color
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.VeilStop
import app.houselights.ui.design.colorStops
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DESIGN.md, Performance: a saga's panel draws nothing it hides. Its veils are drawn over the band they are not clear in
 * alone, every pixel they ink kept; its dark is drawn while nothing covers it whole, and its poster while its backdrop
 * does not, the poster's own fade over.
 */
class VoletGroundTest {
    /** The top veil is drawn down to where it clears, raised over the numbers or not; above, from the panel's edge. */
    @Test
    fun theTopVeilIsDrawnDownToWhereItClears() {
        val band = HlScrim.TOP.inkedBand(0f, SCREEN)
        assertEquals(Float.NEGATIVE_INFINITY, band.start, 0f)
        assertEquals("gone at 164 dp", 164f, band.endInclusive, 0.01f)
        val raised = HlScrim.TOP.raisedOver(NUMBERS / SCREEN, 0.8f, MELT / SCREEN).inkedBand(0f, SCREEN)
        assertEquals("melted back onto the top veil, gone where it goes", 164f, raised.endInclusive, 0.01f)
    }

    /** A closed panel's foot is drawn from where its melt begins, 72 % down, to its foot; open, its words' from their clear stop. */
    @Test
    fun theFeetAreDrawnFromWhereTheyBegin() {
        val foot = (HlScrim.VOLET_FOOT + VeilStop(0.9f, HlScrim.VOLET_YEAR) + VeilStop(1f, HlScrim.VOLET_YEAR_FLOOR)).inkedBand(0f, PANEL)
        assertEquals(0.72f * PANEL, foot.start, 0.01f)
        assertEquals(Float.POSITIVE_INFINITY, foot.endInclusive, 0f)
        val words = HlScrim.CASE_FOOT.inkedBand(100f, 300f)
        assertEquals("a third of the way, clear", 172f, words.start, 0.01f)
        assertEquals(Float.POSITIVE_INFINITY, words.endInclusive, 0f)
    }

    /** Past its band a veil inks nothing: its stops there are clear, and so is the colour its gradient holds past its ends. */
    @Test
    fun aVeilInksNothingPastItsBand() {
        val veils = listOf(
            HlScrim.TOP,
            HlScrim.TOP.raisedOver(NUMBERS / SCREEN, 0.8f, MELT / SCREEN),
            HlScrim.TOP.raisedOver(0.5f, 0.9f, MELT / SCREEN),
            HlScrim.VOLET_FOOT,
            HlScrim.CASE_FOOT,
        )
        veils.forEach { veil ->
            val band = veil.inkedBand(0f, 1f)
            val stops = veil.colorStops(Color.Black)
            stops.filter { (at, _) -> at !in band }.forEach { (at, color) -> assertEquals("$veil at $at", 0f, color.alpha, CLEAR) }
            if (band.start.isFinite()) assertEquals("$veil before its band", 0f, stops.first().second.alpha, CLEAR)
            if (band.endInclusive.isFinite()) assertEquals("$veil after its band", 0f, stops.last().second.alpha, CLEAR)
        }
    }

    /**
     * A closed panel as wide as its poster at most is covered by it once whole; opening wider, its dark shows at its
     * sides again; its backdrop covers it once risen whole and wider than it, never the polyptych of a saga alone.
     */
    @Test
    fun aPanelIsCoveredByWhatIsWiderThanIt() {
        assertEquals("loading", VoletCover.None, voletCover(CLOSED, poster = null, backdrop = null))
        assertEquals("its poster whole", VoletCover.Poster, voletCover(CLOSED, poster = POSTER, backdrop = null))
        assertEquals("as wide as its poster", VoletCover.Poster, voletCover(POSTER.toFloat(), poster = POSTER, backdrop = null))
        assertEquals("opening wider", VoletCover.None, voletCover(POSTER + 1f, poster = POSTER, backdrop = null))
        assertEquals("its backdrop risen", VoletCover.Backdrop, voletCover(OPEN, poster = POSTER, backdrop = BACKDROP))
        assertEquals("its backdrop risen, no poster", VoletCover.Backdrop, voletCover(OPEN, poster = null, backdrop = BACKDROP))
        assertEquals("a saga alone", VoletCover.None, voletCover(BACKDROP + 1f, poster = null, backdrop = BACKDROP))
    }

    private companion object {
        /** The screen the top veils are drawn against, a panel's height, and its numbers' band, in dp. */
        const val SCREEN = HlScrim.SCREEN_HEIGHT
        const val PANEL = 370.5f
        const val NUMBERS = 96f
        const val MELT = 64f

        /** Clear: under a level of 8 bits. */
        const val CLEAR = 1f / 512

        /** A closed panel, its poster and its backdrop at its height, and the open panel, in px. */
        const val CLOSED = 180f
        const val POSTER = 247
        const val OPEN = 428f
        const val BACKDROP = 659
    }
}
