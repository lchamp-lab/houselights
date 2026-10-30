package app.houselights.ui.design.components

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * DESIGN.md, Home: a door's tint reads its posters from copies an eighth of their size, never their every pixel, and
 * comes out as it did from the posters whole.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DoorTintTest {
    /** A poster as a 4K screen asks for one: red on its left, blue on its right, greener down its height. */
    private fun poster(): Bitmap {
        val argb = IntArray(WIDTH * HEIGHT) { i ->
            val x = i % WIDTH
            val y = i / WIDTH
            val green = y * LEVEL / (HEIGHT - 1)
            if (x < WIDTH / 2) Color.rgb(LEVEL, green, 0) else Color.rgb(0, green, LEVEL)
        }
        return Bitmap.createBitmap(argb, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    }

    @Test
    fun aTintReadsItsPostersSmallerAndKeepsTheirColours() {
        val poster = poster()
        val points = tintPoints(poster)
        assertEquals("an eighth of its width", WIDTH / 8, points.width)
        assertEquals("an eighth of its height", HEIGHT / 8, points.height)
        assertFalse("the poster itself kept", poster.isRecycled)

        // As the tint came from the poster whole.
        val whole = IntArray(WIDTH * HEIGHT).also { poster.getPixels(it, 0, WIDTH, 0, 0, WIDTH, HEIGHT) }
        val before = averagedStrip(listOf(Points(whole, WIDTH, HEIGHT), Points(whole, WIDTH, HEIGHT)), TINT_W, TINT_H)
        val tint = checkNotNull(doorTint(listOf(poster, poster)))
        val after = IntArray(TINT_W * TINT_H).also { tint.getPixels(it, 0, TINT_W, 0, 0, TINT_W, TINT_H) }
        before.indices.forEach { i ->
            listOf(RED_SHIFT, GREEN_SHIFT, 0).forEach { shift ->
                val was = (before[i] shr shift) and LEVEL
                val now = (after[i] shr shift) and LEVEL
                assertTrue("point $i: ${before[i].hex()} then ${after[i].hex()}", abs(was - now) <= NEAR)
            }
        }
    }

    private fun Int.hex() = "#%08X".format(this)

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 720
        const val TINT_W = 24
        const val TINT_H = 9
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
        const val LEVEL = 0xFF

        /** Levels a point may move by, read from the smaller copy. */
        const val NEAR = 8
    }
}
