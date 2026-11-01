package app.houselights.ui.design

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
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

/**
 * Heights holding a line of text grow with the text size as much as the line (non-linearly from Android 14); a hero's
 * band, measured in boxes, holds its tallest words at the largest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class TextRoomTest {
    @get:Rule
    val compose = createComposeRule()

    private class Rooms(val heroMin: Dp, val infoLine: Dp, val labelRoom: Dp, val hintRoom: Dp)

    @Test
    fun theRoomOfALineGrowsWithTheTextSize() {
        val rooms = mutableMapOf<Float, Rooms>()
        compose.setContent {
            listOf(1f, TEXT_SCALE_MAX).forEach { scale ->
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    HouselightsTheme {
                        val tile = HlIconButtonDefaults
                        rooms[scale] = Rooms(HlLayout.detailHeroMin(540.dp), HlLayout.infoLine, tile.labelRoom, tile.hintRoom)
                    }
                }
            }
        }
        compose.waitForIdle()
        val default = rooms.getValue(1f)
        val large = rooms.getValue(TEXT_SCALE_MAX)
        // A poster wall's title line, written in dp, is a line of rowTitle at the default text size.
        assertEquals(HouselightsType.rowTitle.lineHeight.value, default.infoLine.value, 0.01f)
        // How much taller a line of [height] at the default text size is at the largest.
        fun growth(height: Float) = with(Density(1f, TEXT_SCALE_MAX)) { height.sp.toDp().value } - height
        val header = growth(HouselightsType.rowTitle.lineHeight.value)
        assertTrue(header > 0f)
        assertEquals("the hero makes room for its header", default.heroMin.value - header, large.heroMin.value, 0.01f)
        assertEquals("a poster wall's title line", default.infoLine.value + growth(default.infoLine.value), large.infoLine.value, 0.01f)
        val label = growth(HouselightsType.entry.lineHeight.value)
        assertEquals("a button's label", default.labelRoom.value + label, large.labelRoom.value, 0.01f)
        val hint = growth(HouselightsType.meta.lineHeight.value)
        assertEquals("a tile's hint", default.hintRoom.value + hint, large.hintRoom.value, 0.01f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aHeroHoldsAnEpisodeAtTheLargestTextSize() {
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
        compose.setTvContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, TEXT_SCALE_MAX)) {
                HeroInfo(episode, Modifier.testTag(HERO))
            }
        }
        val band = compose.onNodeWithTag(HERO).getUnclippedBoundsInRoot()
        val overview = compose.onNodeWithText(episode.overview.orEmpty()).getUnclippedBoundsInRoot()
        val heading = compose.onNodeWithText("Le bureau").getUnclippedBoundsInRoot()
        assertEquals("its overview on the band's foot", band.bottom.value, overview.bottom.value, 0.5f)
        assertTrue("its heading under its logo's box: ${heading.top - band.top}", heading.top - band.top >= HeroDefaults.titleHeight)
    }

    private companion object {
        const val HERO = "hero"
    }
}
