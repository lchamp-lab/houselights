package app.houselights.ui.design

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test

/** A detail page on the TV canvas, 540 dp tall: what its first view shows, and how it turns. */
class DetailLayoutTest {
    private val screen = 540.dp

    @Test
    fun theFirstRowHeaderIsWholeInsideTheSafeArea() {
        val headerTop = HlLayout.detailHeroMin(screen) + HlLayout.detailGap
        val headerBottom = headerTop + 26.dp
        assertTrue("header ends at $headerBottom", headerBottom <= screen - HlSpace.safeV)
        // Only pictures pass the safe area: the row's images start below it.
        assertTrue(headerBottom + HlSpace.xxs + HlSpace.s >= screen - HlSpace.safeV)
    }

    @Test
    fun theBlockAboveLeavesTheScreenWhenTheNextComesToTheKeyline() {
        assertTrue(HlLayout.detailGap >= HlFocus.edge)
        assertTrue(HlFocus.edge >= HlSpace.safeV)
    }
}
