package app.houselights.ui.design.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.houselights.ui.design.HlScrim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The colours of a wall: what its posters show, each poster alike, a hue apart, as the room's light. */
class WallTonesTest {
    private fun swatch(hue: Float, population: Int, saturation: Float = 0.8f, value: Float = 0.7f) =
        WallSwatch(Color.hsv(hue, saturation, value).toArgb(), population)

    private fun hueOf(color: Color): Float {
        val (r, g, b) = Triple(color.red, color.green, color.blue)
        val max = maxOf(r, g, b)
        val chroma = max - minOf(r, g, b)
        val sector = when (max) {
            r -> ((g - b) / chroma).mod(6f)
            g -> (b - r) / chroma + 2
            else -> (r - g) / chroma + 4
        }
        return sector * 60f
    }

    @Test
    fun greysBlacksAndWhitesGiveNoLight() {
        val wall = listOf(listOf(Color.Black, Color.White, Color.Gray).map { WallSwatch(it.toArgb(), 500) })
        assertTrue(wallTones(wall, 3).isEmpty())
    }

    @Test
    fun theHeaviestColourComesFirst() {
        val wall = listOf(listOf(swatch(0f, 600), swatch(120f, 300), swatch(230f, 100)))
        val tones = wallTones(wall, 3)
        assertEquals(3, tones.size)
        assertEquals(0f, hueOf(tones[0]), 2f)
        assertEquals(120f, hueOf(tones[1]), 2f)
        assertEquals(230f, hueOf(tones[2]), 2f)
    }

    @Test
    fun theSecondColourAnswersTheFirst() {
        // Red first; teal, across the wheel, answers it before a heavier ochre next to it.
        val wall = listOf(listOf(swatch(0f, 600), swatch(45f, 300), swatch(180f, 250)))
        val hues = wallTones(wall, 3).map { hueOf(it) }
        assertEquals(0f, hues[0], 2f)
        assertEquals(180f, hues[1], 2f)
        assertEquals(45f, hues[2], 2f)
    }

    @Test
    fun everyPosterCountsAlike() {
        // A poster read from a large file does not outweigh one read from a small one.
        val wall = listOf(listOf(swatch(0f, 10_000)), listOf(swatch(200f, 50)))
        val hues = wallTones(wall, 3).map { hueOf(it) }
        assertTrue(hues.any { it < 2f || it > 358f })
        assertTrue(hues.any { kotlin.math.abs(it - 200f) < 2f })
    }

    @Test
    fun closeHuesAreOneColour() {
        // A red and an orange-red light the room as one; green is another colour.
        val wall = listOf(listOf(swatch(0f, 500), swatch(25f, 400), swatch(130f, 100)))
        val tones = wallTones(wall, 3)
        assertEquals(2, tones.size)
        assertEquals(130f, hueOf(tones[1]), 2f)
    }

    @Test
    fun theyComeOutAsTheRoomsLight() {
        val wall = listOf(listOf(swatch(30f, 500, saturation = 1f, value = 1f), swatch(260f, 300, saturation = 0.2f)))
        wallTones(wall, 3).forEach {
            val max = maxOf(it.red, it.green, it.blue)
            val saturation = (max - minOf(it.red, it.green, it.blue)) / max
            assertEquals(HlScrim.AMBIENT_VALUE, max, 0.01f)
            assertTrue(saturation in HlScrim.AMBIENT_SATURATION_MIN - 0.01f..HlScrim.AMBIENT_SATURATION_MAX + 0.01f)
        }
    }
}
