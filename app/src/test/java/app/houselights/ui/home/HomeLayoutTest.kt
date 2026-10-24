package app.houselights.ui.home

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.houselights.ui.design.HeroDefaults
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HouselightsType
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home's grid fills the safe area under its head; « À regarder » keeps its first row, a focused poster's frame included, inside it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class HomeLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val screen = 540.dp
    private val safeBottom = screen - HlSpace.safeV
    private fun sp(value: Float): Dp = value.dp

    /** Where the page's rows start: its title, a gap, the hero's band, a gap. */
    private val rowsTop = HlSpace.safeV + sp(HouselightsType.headline.lineHeight.value) + HlSpace.l + HlLayout.hero + HlSpace.l

    /** Where a row's cards start: its header in the poster's voice and the room kept above a framed card. */
    private val cardsTop = rowsTop + sp(HouselightsType.rowTitle.lineHeight.value) + HlSpace.xxs + HlFocus.room

    @Test
    fun theGridFillsTheSafeAreaUnderTheHead() {
        compose.setTvContent { HomeGrid(fullHome()) }
        val case = compose.onNodeWithText("Resume").getUnclippedBoundsInRoot()
        assertEquals(HlSpace.safeH, case.left)
        assertEquals(68.dp, case.top)
        assertEquals(safeBottom, case.bottom)
        assertEquals(252.dp, case.right - case.left)
        val settings = compose.onNodeWithText("Settings").getUnclippedBoundsInRoot()
        assertEquals(960.dp - HlSpace.safeH, settings.right)
        assertEquals(safeBottom, settings.bottom)
        assertEquals(294.dp, settings.right - settings.left)
        val movies = compose.onNodeWithText("Movies").getUnclippedBoundsInRoot()
        assertEquals("right of the case, a gap apart", case.right + HlSpace.s, movies.left)
        assertEquals(148.dp, movies.bottom - movies.top)
    }

    @Test
    fun aFocusedPosterOfTheFirstRowStaysInsideTheSafeArea() {
        val poster = HlSize.poster * 3 / 2
        val bottom = cardsTop + poster + HlFocus.reach
        assertTrue("a focused poster ends at $bottom", bottom <= safeBottom)
    }

    @Test
    fun theCaptionsOfAFirstLandscapeRowStayInsideTheSafeArea() {
        val still = HlSize.landscape * 9 / 16
        val caption = HlSpace.s + sp(HouselightsType.cardName.lineHeight.value) + HlSpace.xxs + sp(HouselightsType.meta.lineHeight.value)
        val bottom = cardsTop + still + caption
        assertTrue("captions end at $bottom", bottom <= safeBottom)
    }

    @Test
    fun theHeroHoldsALogoAFactLineAndTwoLinesOfOverview() {
        val content = HeroDefaults.titleHeight + HlSpace.s + sp(HouselightsType.meta.lineHeight.value) + HlSpace.s +
            sp(HouselightsType.body.lineHeight.value * 2)
        assertTrue("the hero's content takes $content", content <= HlLayout.hero)
    }
}
