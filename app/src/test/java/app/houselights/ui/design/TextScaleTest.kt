package app.houselights.ui.design

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import app.houselights.settings.TextSize
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, One canvas: « Taille du texte » multiplies the system's text size, the product kept within 0.75 and 1.3,
 * and the canvas is laid out at it: Compacte in a straight line, Grande on Android's curve.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class TextScaleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theStepMultipliesTheSystemsTextSize() {
        assertEquals(0.85f, textScaleOf(1f, TextSize.COMPACT.step), EPSILON)
        assertEquals(1f, textScaleOf(1f, TextSize.STANDARD.step), EPSILON)
        assertEquals(1.15f, textScaleOf(1f, TextSize.LARGE.step), EPSILON)
        assertEquals("Compacte on Android at 1.15", 0.9775f, textScaleOf(1.15f, TextSize.COMPACT.step), EPSILON)
    }

    @Test
    fun theProductKeepsWithinItsBounds() {
        assertEquals("Grande on Android's largest", 1.3f, textScaleOf(1.3f, TextSize.LARGE.step), EPSILON)
        assertEquals("Grande on Android at 1.15, 1.32", 1.3f, textScaleOf(1.15f, TextSize.LARGE.step), EPSILON)
        assertEquals("Compacte on Android's smallest, 0.72", 0.75f, textScaleOf(0.85f, TextSize.COMPACT.step), EPSILON)
        assertEquals("a system past Android's largest", 1.3f, textScaleOf(2f), EPSILON)
    }

    @Test
    fun theCanvasIsLaidOutAtTheStep() {
        var canvas: Density? = null
        compose.setTvContent(TextSize.COMPACT.step) { canvas = LocalDensity.current }
        compose.waitForIdle()
        val step = TextSize.COMPACT.step
        assertEquals(canvasDensityOf(CANVAS_PX, step), canvas)
        // In a straight line: every size, a title's too, times the step.
        listOf(HouselightsType.body, HouselightsType.titleSignature).forEach { style ->
            val size = style.fontSize
            assertEquals("$size", size.value * step, with(checkNotNull(canvas)) { size.toDp().value }, EPSILON)
        }
    }

    /** With Android at its largest, Grande lays the canvas out as Standard does: at 1.3, never past it. */
    @Test
    fun grandeOnAndroidsLargestAddsNothing() {
        val canvases = mutableMapOf<TextSize, Density>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                listOf(TextSize.STANDARD, TextSize.LARGE).forEach { size ->
                    HouselightsTheme(size.step) { canvases[size] = LocalDensity.current }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(1.3f, canvases.getValue(TextSize.LARGE).fontScale, EPSILON)
        assertEquals(canvases.getValue(TextSize.STANDARD), canvases.getValue(TextSize.LARGE))
    }

    /** Grande follows Android's curve: the reading sizes grow most, a title hardly, the signature not at all. */
    @Test
    fun grandeGrowsTheSmallSizesMost() {
        var canvas: Density? = null
        compose.setTvContent(TextSize.LARGE.step) { canvas = LocalDensity.current }
        compose.waitForIdle()
        fun growth(style: TextStyle) = with(checkNotNull(canvas)) { style.fontSize.toDp().value } / style.fontSize.value - 1
        with(HouselightsType) {
            // Android's curve: 12 to 15 sp grow 15 to 16 %, 16 sp 13 %, 30 sp and up keep their size.
            listOf(meta, body).forEach { assertTrue("${it.fontSize}: ${growth(it)}", growth(it) >= 0.15f - EPSILON) }
            assertTrue("${lead.fontSize}: ${growth(lead)}", growth(lead) in 0.12f..0.14f)
            assertTrue("${headline.fontSize}: ${growth(headline)}", growth(headline) < 0.01f)
            assertEquals("${titleSignature.fontSize}", 0f, growth(titleSignature), EPSILON)
        }
    }

    private companion object {
        const val CANVAS_PX = 960
        const val EPSILON = 0.001f
    }
}
