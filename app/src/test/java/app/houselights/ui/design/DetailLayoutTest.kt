package app.houselights.ui.design

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test

/** A detail page on the TV canvas, 540 dp tall: what its first view shows, and how it turns. */
class DetailLayoutTest {
    private val screen = 540.dp

    /**
     * At the viewer's text size, from the default to Android's largest (1.3): the first row's header,
     * a line of `lead`, grows with it, and the hero gives it the room.
     */
    @Test
    fun theFirstRowHeaderIsWholeInsideTheSafeAreaAtEveryTextSize() {
        listOf(1f, 1.15f, 1.3f).forEach { scale ->
            val header = HouselightsType.lead.lineHeight.value.dp * scale
            val headerTop = HlLayout.detailHeroMin(screen, header) + HlLayout.detailGap
            val headerBottom = headerTop + header
            // Dp sums in floats: a hundredth of a dp is no part of a pixel.
            assertTrue("at $scale, the header ends at $headerBottom", headerBottom <= screen - HlSpace.safeV + 0.01.dp)
            // Only pictures pass the safe area: the row's images start below it.
            assertTrue("at $scale", headerBottom + HlSpace.xxs + HlSpace.s >= screen - HlSpace.safeV)
        }
    }

    @Test
    fun theBlockAboveLeavesTheScreenWhenTheNextComesToTheKeyline() {
        assertTrue(HlLayout.detailGap >= HlFocus.edge)
        assertTrue(HlFocus.edge >= HlSpace.safeV)
    }
}
