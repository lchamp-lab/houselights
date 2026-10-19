package app.houselights.ui.design

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test

/** A detail page on the TV canvas, 540 dp tall: what its first view shows, and how it turns. */
class DetailLayoutTest {
    private val screen = 540.dp

    /** A row's header in the poster's voice, 28 at the default text size. */
    private val header = HouselightsType.rowTitle.lineHeight.value.dp

    /** Up to Android's largest text size (1.3), the first row's header grows and the hero makes room for it. */
    @Test
    fun theFirstRowHeaderIsWholeInsideTheSafeAreaAtEveryTextSize() {
        listOf(1f, 1.15f, 1.3f).forEach { scale ->
            val grown = header * scale
            val headerTop = HlLayout.detailHeroMin(screen, grown) + HlLayout.detailGap
            val headerBottom = headerTop + grown
            // Dp sums in floats: a hundredth of a dp is less than a pixel.
            assertTrue("at $scale, the header ends at $headerBottom", headerBottom <= screen - HlSpace.safeV + 0.01.dp)
            // Only pictures pass the safe area: the row's images start below it.
            assertTrue("at $scale", headerBottom + HlSpace.xxs + HlSpace.s >= screen - HlSpace.safeV)
        }
    }

    /** Come to the keyline, the first row holds a focused poster, grown and framed, inside the safe area. */
    @Test
    fun aFocusedPosterOfTheFirstRowStaysInsideTheSafeArea() {
        val poster = HlSize.poster * 3 / 2
        val reach = HlFocus.reach(poster, HlFocus.POSTER_SCALE)
        val cardsTop = HlFocus.edge + header + HlSpace.xxs + HlFocus.room
        assertTrue("a focused poster starts at ${cardsTop - reach}", cardsTop - reach >= HlSpace.safeV)
        assertTrue("a focused poster ends at ${cardsTop + poster + reach}", cardsTop + poster + reach <= screen - HlSpace.safeV)
    }

    @Test
    fun theBlockAboveLeavesTheScreenWhenTheNextComesToTheKeyline() {
        assertTrue(HlLayout.detailGap >= HlFocus.edge)
        assertTrue(HlFocus.edge >= HlSpace.safeV)
    }
}
