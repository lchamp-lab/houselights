package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import app.houselights.settings.TextSize
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HouselightsTheme
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
import org.robolectric.annotation.GraphicsMode

/**
 * DESIGN.md, Type: a title's page without a logo sets its title to the logo's box (440 × 104), on one line in its
 * signature when it fits there, else in `display` on one line or two even ones, as tall as the box.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
// Real fonts, so titles measure as on the device.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TitleArtTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(vararg titles: String) = compose.setTvContent {
        Column {
            titles.forEach { title ->
                TitleArt(title, logo = null, maxWidth = HlLayout.titleArtWidth, maxHeight = HlLayout.titleArtHeight, measured = true)
            }
        }
    }

    private fun layout(title: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first()
    }

    @Test
    fun aShortTitleIsSetInItsSignature() {
        show("Bugonia")
        val set = layout("Bugonia")
        assertEquals(HouselightsType.titleSignature.fontSize, set.layoutInput.style.fontSize)
        assertEquals(1, set.lineCount)
    }

    @Test
    fun aTitleTooWideForItsSignatureKeepsOneLineInDisplay() {
        val title = "Top Gun\u00A0: Maverick"
        show(title)
        val set = layout(title)
        assertEquals(HouselightsType.display.fontSize, set.layoutInput.style.fontSize)
        assertEquals(1, set.lineCount)
    }

    @Test
    fun aLongTitleTakesTwoEvenLines() {
        val title = "Spider-Man\u00A0: Across the Spider-Verse"
        show(title)
        val set = layout(title)
        assertEquals(HouselightsType.display.fontSize, set.layoutInput.style.fontSize)
        assertEquals(2, set.lineCount)
    }

    /**
     * Under Standard the signature is chosen as at Standard, then set at the viewer's size: at Compacte « Top Gun :
     * Maverick » would fit the signature, now 61 sp, but stays in `display`, smaller, and « Bugonia » keeps its signature.
     */
    @Test
    fun aSmallerTextSizeNeverGrowsATitle() {
        val title = "Top Gun\u00A0: Maverick"
        var signed = 0
        compose.setTvContent(TextSize.COMPACT.step) {
            val measurer = rememberTextMeasurer()
            signed = measurer.measure(title, Hl.type.titleSignature, softWrap = false, maxLines = 1).size.width
            Column {
                listOf("Bugonia", title).forEach {
                    TitleArt(it, logo = null, maxWidth = HlLayout.titleArtWidth, maxHeight = HlLayout.titleArtHeight, measured = true)
                }
            }
        }
        compose.waitForIdle()
        assertTrue("its signature fits at Compacte: $signed", signed <= HlLayout.titleArtWidth.value)
        assertEquals(HouselightsType.display.fontSize, layout(title).layoutInput.style.fontSize)
        assertEquals(TextSize.COMPACT.step, layout(title).layoutInput.density.fontScale)
        assertEquals(HouselightsType.titleSignature.fontSize, layout("Bugonia").layoutInput.style.fontSize)
    }

    /** Android set under 1 too: the product decides, not the app's step. */
    @Test
    fun androidUnderOneNeverGrowsATitle() {
        val title = "Top Gun\u00A0: Maverick"
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 0.85f)) {
                HouselightsTheme {
                    TitleArt(title, logo = null, maxWidth = HlLayout.titleArtWidth, maxHeight = HlLayout.titleArtHeight, measured = true)
                }
            }
        }
        compose.waitForIdle()
        assertEquals(HouselightsType.display.fontSize, layout(title).layoutInput.style.fontSize)
    }

    @Test
    fun aTitleKeepsToTheLogosBox() {
        val titles = arrayOf("Bugonia", "Top Gun\u00A0: Maverick", "Spider-Man\u00A0: Brand New Day", "Les Sept Samouraïs")
        show(*titles)
        titles.forEach { title ->
            val box = compose.onNodeWithText(title).getUnclippedBoundsInRoot()
            assertTrue("$title: ${box.width} wide", box.width <= HlLayout.titleArtWidth)
            assertTrue("$title: ${box.height} tall", box.height <= HlLayout.titleArtHeight)
        }
    }
}
