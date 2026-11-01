package app.houselights.ui.home

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.design.HeroDefaults
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlGap
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HouselightsType
import app.houselights.ui.design.components.HeroInfo
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Home's grid fills the safe area under its head; « À regarder » keeps its first row, a focused poster's frame included, inside it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
// Real fonts, so the hero's band is measured as on the device.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val screen = 540.dp
    private val safeBottom = screen - HlSpace.safeV
    private fun sp(value: Float): Dp = value.dp

    /** The hero's band, measured in boxes at the default text size. */
    private fun heroBand(): Dp {
        var band = 0.dp
        compose.setTvContent { band = HeroDefaults.band }
        return band
    }

    /** Where the page's rows start: its title, a gap, the hero's band, a band's gap. */
    private fun rowsTop() = HlSpace.safeV + sp(HouselightsType.headline.lineHeight.value) + HlGap.headToContent + heroBand() + HlGap.band

    /** Where a row's cards start: its header in the poster's voice and the room kept above a framed card. */
    private fun cardsTop() = rowsTop() + sp(HouselightsType.rowTitle.lineHeight.value) + HlSpace.xxs + HlFocus.room

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
        val bottom = cardsTop() + poster + HlFocus.reach
        assertTrue("a focused poster ends at $bottom", bottom <= safeBottom)
    }

    @Test
    fun theCaptionsOfAFirstLandscapeRowStayInsideTheSafeArea() {
        val still = HlSize.landscape * 9 / 16
        val caption = HlSpace.s + sp(HouselightsType.cardName.lineHeight.value) + HlSpace.xxs + sp(HouselightsType.meta.lineHeight.value)
        val bottom = cardsTop() + still + caption
        assertTrue("captions end at $bottom", bottom <= safeBottom)
    }

    @Test
    fun theHeroStandsAnEpisodesHeadingAndTwoLinesOfOverviewOnItsFoot() {
        // The tallest it holds: a title in words, an episode's heading in entry, two lines of overview.
        val episode = MediaItem(
            "e",
            MediaKind.EPISODE,
            "Le bureau",
            seriesTitle = "Le Bureau des légendes",
            seasonNumber = 2,
            episodeNumber = 4,
            overview = "Malotru revient de Syrie. ".repeat(12),
        )
        compose.setTvContent { HeroInfo(episode, Modifier.testTag(HERO)) }
        val band = compose.onNodeWithTag(HERO).getUnclippedBoundsInRoot()
        val overview = compose.onNodeWithText(episode.overview.orEmpty()).getUnclippedBoundsInRoot()
        val heading = compose.onNodeWithText("Le bureau").getUnclippedBoundsInRoot()
        assertEquals("its overview on the band's foot", band.bottom.value, overview.bottom.value, HALF)
        assertTrue("its logo's box inside the band: ${heading.top - band.top}", heading.top - band.top >= HeroDefaults.titleHeight)
        assertEquals("a box measure, 136 at 1", BAND, (band.bottom - band.top).value, 1f)
    }

    private companion object {
        const val HERO = "hero"
        const val BAND = 136f
        const val HALF = 0.5f
    }
}
