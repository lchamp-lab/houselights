package app.houselights.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.design.HouselightsType
import app.houselights.ui.testing.TV
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home's bands on the TV canvas: the first row, a focused poster's frame included, stays inside the safe area. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class HomeLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val screen = 540.dp
    private val safeBottom = screen - HlSpace.safeV
    private fun sp(value: Float): Dp = value.dp

    /** Home's bar, as tall as a compact tile ([theBarIsAsTallAsACompactTile]). */
    private val bar = HlSize.tileCompact

    /** Where the rows start: the bar, a gap, the hero's band, a gap. */
    private val rowsTop = HlSpace.safeV + bar + HlSpace.l + HlLayout.hero + HlSpace.l

    /** Where a row's cards start: its header in the poster's voice and the room kept above a framed card. */
    private val cardsTop = rowsTop + sp(HouselightsType.rowTitle.lineHeight.value) + HlSpace.xxs + HlFocus.room

    /** The bar as composed, Gradation, words and tiles, at the default text size and at the largest. */
    @Test
    fun theBarIsAsTallAsACompactTile() {
        val libraries = listOf(Library("1", LibraryKind.MOVIES, "Films"), Library("2", LibraryKind.SHOWS, "Séries"))
        compose.setContent {
            Column {
                listOf(1f, LARGEST).forEach { scale ->
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                        HouselightsTheme {
                            Box(Modifier.testTag("bar at $scale")) {
                                TopBar(libraries, FocusMemory(), null, {}, {}, onSettings = {}, intoRows = { null }, onSearch = {})
                            }
                        }
                    }
                }
            }
        }
        listOf(1f, LARGEST).forEach { scale ->
            compose.onNodeWithTag("bar at $scale").assertHeightIsEqualTo(HlSpace.safeV + bar)
        }
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
        val caption = HlSpace.s + sp(HouselightsType.cardName.lineHeight.value) + HlSpace.xxs + sp(HouselightsType.caption.lineHeight.value)
        val bottom = cardsTop + still + caption
        assertTrue("captions end at $bottom", bottom <= safeBottom)
    }

    @Test
    fun theHeroHoldsALogoAFactLineAndTwoLinesOfOverview() {
        val content = HlLayout.heroTitleHeight + HlSpace.s + sp(HouselightsType.meta.lineHeight.value) + HlSpace.s +
            sp(HouselightsType.body.lineHeight.value * 2)
        assertTrue("the hero's content takes $content", content <= HlLayout.hero)
    }

    private companion object {
        /** Android's largest text size. */
        const val LARGEST = 1.3f
    }
}
