package app.houselights.ui.design

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The left veil's floor: the quietest text of a column reads at 4.5:1 over the lightest image, under
 * the ambient spill too, while a dark image keeps its veil; and only what shows under the column
 * counts, the picture laid as `Backdrop` lays it.
 */
class ScrimTest {
    private val c = HouselightsColors

    /** 1080p at 2 px per dp: the column is `safeH` + `readableNarrow`, 568 dp. */
    private val screen = Size(1920f, 1080f)
    private val column = 1136f
    private val detail = HlScrim.down(fadeFrom = 0.55f)
    private val strength = 0.97f

    @Test
    fun onWhiteTheFloorIsTheOneDesignMdGives() {
        assertEquals(0.85f, HlScrim.need(1f), 0.0001f)
        assertEquals(0.775f, HlScrim.need(1f, ceiling = HlScrim.ceiling(c.contentSecondary)), 0.0001f)
    }

    @Test
    fun theFloorKeepsTheQuietestTextReadableUnderTheSpill() {
        // The spill at its brightest: a grey as light as the ambient tone can be.
        val light = Color(HlScrim.AMBIENT_VALUE, HlScrim.AMBIENT_VALUE, HlScrim.AMBIENT_VALUE)
        listOf(0f, 0.05f, 0.1f, 0.15f).forEach { spill ->
            val veiled = c.background.copy(alpha = HlScrim.need(1f, spill)).compositeOver(Color.White)
            val ground = light.copy(alpha = spill).compositeOver(veiled)
            val ratio = contrast(c.contentTertiary, ground)
            assertTrue("spill $spill: ${"%.2f".format(ratio)}:1", ratio >= 4.5f)
        }
    }

    @Test
    fun aDarkPictureKeepsItsVeil() {
        assertEquals(0f, sample(96, 54) { _, _ -> 0.1f }.floor(screen, column, 1f, strength, detail), 0f)
    }

    @Test
    fun aWhitePictureGetsTheFloor() {
        assertTrue(sample(96, 54) { _, _ -> 1f }.floor(screen, column, 1f, strength, detail) >= HlScrim.need(1f))
    }

    @Test
    fun aDimmedPictureNeedsLess() {
        val white = sample(96, 54) { _, _ -> 1f }
        assertTrue(white.floor(screen, column, 0.28f, strength, detail) < white.floor(screen, column, 1f, strength, detail))
    }

    @Test
    fun onlyWhatShowsUnderTheColumnCounts() {
        // Light past the column, dark under it.
        assertEquals(0f, sample(96, 54) { x, _ -> if (x >= 60) 1f else 0f }.floor(screen, column, 1f, strength, detail), 0f)
        // Wider than the screen, the picture shows its right end: its light left end is out of view.
        assertEquals(0f, sample(192, 54) { x, _ -> if (x < 96) 1f else 0f }.floor(screen, column, 1f, strength, detail), 0f)
    }

    private fun sample(width: Int, height: Int, grey: (x: Int, y: Int) -> Float) =
        ArtworkSample(width, height, FloatArray(width * height) { grey(it % width, it / width) })

    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }
}
