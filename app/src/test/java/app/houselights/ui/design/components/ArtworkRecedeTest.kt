package app.houselights.ui.design.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.pictureServer
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DESIGN.md, Focus: a picture steps back through its filter read while drawing, so it never composes again as it does;
 * a card's reading is the same modifier from one composition to the next, so its surface skips composing again as focus
 * comes into its row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class ArtworkRecedeTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val coil = CoilRule()

    @Test
    fun aPictureNeverComposesAgainAsItStepsBack() {
        coil.serve(pictureServer())
        val recede = mutableFloatStateOf(0f)
        var shown = false
        var composed = 0
        val probe = Composed { composed++ }
        compose.setTvContent {
            CompositionLocalProvider(LocalArtworkRecede provides { recede.floatValue }) {
                ArtworkImage(URL, "Dune", Modifier.size(SIDE.dp), pictureModifier = probe, size = SIZE, onShow = { shown = true })
            }
        }
        compose.waitUntil(TIMEOUT_MS) { shown }
        compose.waitForIdle()
        composed = 0
        // Stepping back as a sibling takes focus, each of its steps.
        listOf(STEP, 2 * STEP, 1f, 0f).forEach {
            recede.floatValue = it
            compose.waitForIdle()
        }
        assertEquals("the picture composed again as it stepped back", 0, composed)
    }

    /** Drawn on a bitmap of its own, which Robolectric's native graphics make. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aRecedingPictureIsDrawnThroughItsStepsFilter() {
        val recede = mutableFloatStateOf(0f)
        val picture = Drawn()
        val painter = RecedingPainter(picture) { recede.floatValue }
        assertEquals(picture.intrinsicSize, painter.intrinsicSize)
        fun drawIt(alpha: Float = 1f, filter: ColorFilter? = null) =
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(ImageBitmap(PX, PX)), Size(PX.toFloat(), PX.toFloat())) {
                with(painter) { draw(size, alpha, filter) }
            }
        listOf(0f, STEP, 2 * STEP, 1f).forEach {
            recede.floatValue = it
            drawIt()
            assertSame("at $it", recedeFilterAt(it), picture.drawnFilter)
        }
        // A filter its drawer gives holds, and the loader's fade applies.
        val own = ColorFilter.tint(Color.Black)
        drawIt(alpha = HALF, filter = own)
        assertSame(own, picture.drawnFilter)
        assertEquals(HALF, picture.drawnAlpha, 0f)
    }

    @Test
    fun aCardsReadingIsTheSameModifierFromOneCompositionToTheNext() {
        val readings = mutableListOf<Modifier>()
        var row by mutableIntStateOf(0)
        var focused by mutableStateOf(false)
        val open = {}
        compose.setTvContent {
            // Composed again as focus comes into the card's row, the card itself unchanged.
            if (row >= 0) readings += rememberCardReading("Dune", "vu", focused, open, null, null)
        }
        compose.waitForIdle()
        row++
        compose.waitForIdle()
        assertEquals(2, readings.size)
        assertSame(readings[0], readings[1])
        focused = true
        compose.waitForIdle()
        assertNotEquals("focus restated", readings[1], readings.last())
    }

    /** A modifier never equal to another: the picture's layout updates it each time the picture composes again. */
    private class Composed(private val again: () -> Unit) : ModifierNodeElement<Composed.Node>() {
        class Node : Modifier.Node()

        override fun create() = Node()

        override fun update(node: Node) = again()

        @Suppress("EqualsAlwaysReturnsTrueOrFalse") // Never equal, so that each composition updates it: what it counts.
        override fun equals(other: Any?) = false

        override fun hashCode() = 0
    }

    /** A picture that keeps the filter and the alpha it was last drawn with. */
    private class Drawn : Painter() {
        var drawnFilter: ColorFilter? = null
        var drawnAlpha = 1f
        private var given: ColorFilter? = null
        private var givenAlpha = 1f

        override val intrinsicSize = Size(PX.toFloat(), PX * 1.5f)

        override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
            given = colorFilter
            return true
        }

        override fun applyAlpha(alpha: Float): Boolean {
            givenAlpha = alpha
            return true
        }

        override fun DrawScope.onDraw() {
            drawnFilter = given
            drawnAlpha = givenAlpha
        }
    }

    private companion object {
        const val URL = "https://img.test/dune?w=224"
        val SIZE = IntSize(224, 336)
        const val SIDE = 40
        const val PX = 4
        const val STEP = 0.3f
        const val HALF = 0.5f
        const val TIMEOUT_MS = 5_000L
    }
}
