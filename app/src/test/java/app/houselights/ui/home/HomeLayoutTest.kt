package app.houselights.ui.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HouselightsType
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's bands on the TV canvas: the first row, a grown poster included, stays inside the safe area. */
class HomeLayoutTest {
    private val screen = 540.dp
    private val safeBottom = screen - HlSpace.safeV
    private fun sp(value: Float): Dp = value.dp

    /** Where the rows start: the bar, a gap, the hero's band, a gap. */
    private val rowsTop = HlSpace.safeV + HlSize.control + HlSpace.l + HlLayout.hero + HlSpace.l

    /** Where a row's cards start: its header and the room kept above a growing card. */
    private val cardsTop = rowsTop + sp(HouselightsType.lead.lineHeight.value) + HlSpace.xxs + HlFocus.room

    @Test
    fun aFocusedPosterOfTheFirstRowStaysInsideTheSafeArea() {
        val poster = HlSize.poster * 3 / 2
        // Its growth and its frame.
        val grown = HlFocus.reach(poster, HlFocus.POSTER_SCALE)
        val bottom = cardsTop + poster + grown
        assertTrue("a focused poster ends at $bottom", bottom <= safeBottom)
    }

    @Test
    fun theCaptionsOfAFirstLandscapeRowStayInsideTheSafeArea() {
        val still = HlSize.landscape * 9 / 16
        val caption = HlSpace.s + sp(HouselightsType.entry.lineHeight.value) + HlSpace.xxs + sp(HouselightsType.caption.lineHeight.value)
        val bottom = cardsTop + still + caption
        assertTrue("captions end at $bottom", bottom <= safeBottom)
    }

    @Test
    fun theHeroHoldsALogoAFactLineAndTwoLinesOfOverview() {
        val content = HlLayout.heroTitleHeight + HlSpace.s + sp(HouselightsType.meta.lineHeight.value) + HlSpace.s +
            sp(HouselightsType.body.lineHeight.value * 2)
        assertTrue("the hero's content takes $content", content <= HlLayout.hero)
    }
}
