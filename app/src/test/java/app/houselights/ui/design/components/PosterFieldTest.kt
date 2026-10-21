package app.houselights.ui.design.components

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import app.houselights.core.ImageRef
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlScrim
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The settings' room is a library's posters side by side, each filling its slot, made a wall's field: as small,
 * never brighter than its cap, its colours where their posters lie; the last made shows at once, and a new one comes
 * in over the one showing, which never goes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class PosterFieldTest {
    @get:Rule
    val compose = createComposeRule()

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

    @Test
    fun theFieldKeptShowsAtOnce() {
        val kept = field("kept")
        PosterFields.keep(kept)
        lateinit var room: RoomField
        compose.setTvContent { room = rememberRoomField(posters("kept")) { _, _ -> error("made again") } }
        // The first frame: whole, no fade from the black room.
        compose.runOnIdle {
            assertSame(kept, room.under)
            assertEquals(1f, room.shown())
        }
    }

    @Test
    fun aNewListComesInOverTheOneShowingWhichStaysWhole() {
        val first = field("first")
        val next = field("next")
        PosterFields.keep(first)
        var posters by mutableStateOf(posters("first"))
        lateinit var room: RoomField
        compose.mainClock.autoAdvance = false
        compose.setTvContent { room = rememberRoomField(posters) { _, _ -> next } }
        compose.runOnIdle { posters = posters("next") }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(HlMotion.TINT / 2L)
        compose.runOnIdle {
            assertSame("the one showing, kept whole", first, room.under)
            assertSame("the next, coming in over it", next, room.over)
            assertEquals("never under whole", 1f, room.shown())
            assertTrue("half way: ${room.coming.value}", room.coming.value in HALF_WAY)
        }
        compose.mainClock.advanceTimeBy(HlMotion.TINT.toLong())
        compose.runOnIdle {
            assertSame(next, room.under)
            assertNull(room.over)
        }
    }

    /** A field made from posters named after [name]. */
    private fun field(name: String) = MadeField(
        posters(name).map { it.url(SAMPLE_PX) },
        Bitmap.createBitmap(FIELD_W, FIELD_H, Bitmap.Config.ARGB_8888),
        ImageLight.Grey,
    )

    private fun posters(name: String) = List(FIELD_POSTERS) { i -> ImageRef("$name-$i") { w -> "file:///$name-$i-$w.jpg" } }

    private fun poster(argb: Int) = Points(IntArray(PW * PH) { argb }, PW, PH)

    private companion object {
        const val PW = 64
        const val PH = 96
        const val W = 192
        const val H = 108
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()

        /** Half a fade on, give or take the frames it starts in. */
        val HALF_WAY = 0.1f..0.9f
    }
}
