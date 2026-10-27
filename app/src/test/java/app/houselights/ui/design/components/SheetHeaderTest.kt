package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DESIGN.md, Sheets: a sheet's artwork is as tall as its title and the line of facts under it, as the title sets
 * beside it, on one line or two; the title gives up its width, never the artwork.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class SheetHeaderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theArtworkIsAsTallAsTheTitleBlock() {
        compose.setTvContent {
            HlSideSheet(
                "Fiche technique",
                onDismiss = {},
                facts = listOf("Colony", "2026"),
                artwork = { PosterThumb(null, Modifier.testTag("art"), width = null) },
            ) { HlSettingRow("Résolution", value = "Full HD") }
        }
        val art = compose.onNodeWithTag("art").getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText("Fiche technique").getUnclippedBoundsInRoot()
        val facts = compose.onNodeWithText("2026").getUnclippedBoundsInRoot()
        assertEquals("its foot is the facts' foot", facts.bottom.value, art.bottom.value, 1f)
        val reach = title.height / 2
        assertTrue("its top is the title's: $art, $title", art.top <= title.top + reach && art.top >= title.top - reach)
        assertEquals("a poster's shape", art.height.value * 2 / 3, art.width.value, 1f)
        assertTrue("the words after it", title.left >= art.right)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun threePostersKeepTheirShapeBesideATitleOnTwoLines() {
        RuntimeEnvironment.setFontScale(LARGEST)
        val title = "Films et séries de la médiathèque du salon"
        compose.setTvContent {
            HlSideSheet(
                title,
                onDismiss = {},
                facts = listOf("Films"),
                badges = listOf("NFS"),
                artwork = {
                    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xxs)) {
                        repeat(POSTERS) { PosterThumb(null, Modifier.testTag("art$it"), width = null) }
                    }
                },
            ) { HlSettingRow("Analyser maintenant", description = "Cherche les nouveaux fichiers", onClick = {}) }
        }
        val words = compose.onNodeWithText(title).getUnclippedBoundsInRoot()
        val facts = compose.onNodeWithText("Films").getUnclippedBoundsInRoot()
        val line = HouselightsType.subject.lineHeight.value * LARGEST
        assertTrue("the title on two lines: $words", words.height.value > line)
        (0 until POSTERS).map { compose.onNodeWithTag("art$it").getUnclippedBoundsInRoot() }.forEach { art ->
            assertEquals("its foot is the facts' foot", facts.bottom.value, art.bottom.value, 1f)
            assertEquals("a poster's shape, whole: $art", art.height.value * HlSize.POSTER_RATIO, art.width.value, 1f)
            assertTrue("before the words", art.right <= words.left)
        }
    }

    private companion object {
        const val LARGEST = 1.3f
        const val POSTERS = 3
    }
}
