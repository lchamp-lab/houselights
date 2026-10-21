package app.houselights.ui.design.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.houselights.ui.design.HlScrim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The settings' room is a library's posters side by side, each filling its slot, made a wall's field: as small,
 * never brighter than its cap, its colours where their posters lie.
 */
@RunWith(RobolectricTestRunner::class)
class PosterFieldTest {
    private val red = poster(0xFFE02020.toInt())
    private val blue = poster(0xFF2040E0.toInt())

    @Test
    fun eachPosterFillsItsSlot() {
        val strip = posterStrip(listOf(red, blue, red), W, H)
        val third = W / 3
        listOf(0, third - 1).forEach { x -> assertEquals("x $x", red.argb[0], strip[H / 2 * W + x]) }
        listOf(third, 2 * third - 1).forEach { x -> assertEquals("x $x", blue.argb[0], strip[H / 2 * W + x]) }
        assertEquals(red.argb[0], strip[(H - 1) * W + W - 1])
    }

    @Test
    fun aPosterIsCroppedToFillNotSquashed() {
        // Its left half white, its right half black: a slot narrower than the poster shows its middle, both halves.
        val halves = Points(IntArray(PW * PH) { if (it % PW < PW / 2) WHITE else BLACK }, PW, PH)
        val strip = posterStrip(listOf(halves, halves, halves, halves), W, H)
        val slot = W / 4
        val row = H / 2 * W
        assertEquals(WHITE, strip[row])
        assertEquals(BLACK, strip[row + slot - 1])
    }

    @Test
    fun theFieldIsAWallsAndKeepsItsCap() {
        val field = posterField(listOf(red, blue, poster(WHITE), poster(WHITE), red, blue))
        assertEquals(FIELD_W * FIELD_H, field.size)
        field.forEach { assertTrue("${Color(it).luminance()}", Color(it).luminance() <= HlScrim.FIELD_LUMINANCE_MAX + 1e-6f) }
    }

    @Test
    fun itsLightLiesWhereItsPostersDo() {
        val field = posterField(listOf(red, blue))
        val left = Color(field[FIELD_H / 2 * FIELD_W + FIELD_W / 8])
        val right = Color(field[FIELD_H / 2 * FIELD_W + FIELD_W * 7 / 8])
        assertTrue("red on the left: $left", left.red > left.blue)
        assertTrue("blue on the right: $right", right.blue > right.red)
    }

    private fun poster(argb: Int) = Points(IntArray(PW * PH) { argb }, PW, PH)

    private companion object {
        const val PW = 64
        const val PH = 96
        const val W = 192
        const val H = 108
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
