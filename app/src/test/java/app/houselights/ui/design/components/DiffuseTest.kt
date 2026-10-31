package app.houselights.ui.design.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.ImageRef
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.VeilStop
import app.houselights.ui.design.noise
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.memory.MemoryCache
import coil.request.SuccessResult
import coil.size.Dimension
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * A wall's diffuse field is a small, blurred copy of its backdrop's sample, never brighter than the cap its text
 * reads over, enlarged without rings, and memory-cached beside the sample.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiffuseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val pictures = mapOf(
        "white" to picture { _, _ -> WHITE },
        "red" to picture { _, _ -> 0xFFE02020.toInt() },
        "a light disc" to picture { x, y -> if ((x - 48) * (x - 48) + (y - 27) * (y - 27) < 200) WHITE else 0xFF102030.toInt() },
        "noise" to picture { x, y ->
            val v = (noise(x, y) * 255).toInt()
            0xFF000000.toInt() or (v shl 16) or ((255 - v) shl 8) or (v / 2)
        },
    )

    @Test
    fun aFieldIs64By36() {
        pictures.forEach { (name, points) -> assertEquals(name, FIELD_W * FIELD_H, diffuseField(points, W, H).size) }
        runTest {
            val sample = Bitmap.createBitmap(pictures.getValue("noise"), W, H, Bitmap.Config.ARGB_8888)
            val field = DiffuseField.transform(sample, coil.size.Size(W, H))
            assertEquals(FIELD_W to FIELD_H, field.width to field.height)
        }
    }

    @Test
    fun noPointIsBrighterThanTheCap() {
        pictures.forEach { (name, points) ->
            diffuseField(points, W, H).forEach {
                assertTrue("$name: ${Color(it).luminance()}", Color(it).luminance() <= HlScrim.FIELD_LUMINANCE_MAX + 1e-6f)
            }
        }
    }

    @Test
    fun aWhitePictureSitsAtTheCap() {
        val field = diffuseField(pictures.getValue("white"), W, H).map { Color(it).luminance() }.sorted()
        val p95 = field[((field.size - 1) * 0.95f).toInt()]
        // Within a level of the cap: the field is rounded down.
        assertTrue("$p95", p95 > HlScrim.FIELD_LUMINANCE_MAX * 0.95f)
    }

    @Test
    fun aDarkPictureKeepsItsLevel() {
        // Under the cap, as it was, to the level it is rounded down to.
        diffuseField(picture { _, _ -> 0xFF202020.toInt() }, W, H).forEach { assertTrue(abs((it and 0xFF) - 0x20) <= 1) }
    }

    @Test
    fun greyStaysGreyAndRedStaysRed() {
        diffuseField(picture { x, _ -> 0xFF000000.toInt() or ((x * 2) * 0x010101) }, W, H).forEach {
            assertTrue(it and 0xFF == (it shr 8) and 0xFF && it and 0xFF == (it shr 16) and 0xFF)
        }
        diffuseField(pictures.getValue("red"), W, H).forEach {
            assertTrue((it shr 16) and 0xFF > (it shr 8) and 0xFF && (it shr 16) and 0xFF > it and 0xFF)
        }
    }

    @Test
    fun aWhitePointSpreadsUnderTheCap() {
        val field = diffuseField(picture { x, y -> if (x == 48 && y == 27) WHITE else BLACK }, W, H)
        val lit = field.count { it and 0xFFFFFF != 0 }
        assertTrue("$lit points lit", lit >= 25)
        field.forEach { assertTrue(Color(it).luminance() <= HlScrim.FIELD_LUMINANCE_MAX + 1e-6f) }
    }

    @Test
    fun theEdgesAreNotDarkened() {
        val field = diffuseField(picture { _, _ -> 0xFF303030.toInt() }, W, H)
        val middle = field[FIELD_W * FIELD_H / 2] and 0xFF
        assertTrue(field.all { abs((it and 0xFF) - middle) <= 1 })
    }

    @Test
    fun aWiderPictureShowsItsRightEnd() {
        // As the backdrop lays it: the light left end is cropped out.
        val field = diffuseField(IntArray(W * 2 * H) { if (it % (W * 2) < W) WHITE else BLACK }, W * 2, H)
        assertTrue(field.all { it and 0xFFFFFF == 0 })
    }

    @Test
    fun theSameSampleGivesTheSameField() {
        val noise = pictures.getValue("noise")
        assertTrue(diffuseField(noise, W, H).contentEquals(diffuseField(noise, W, H)))
    }

    @Test
    @Config(sdk = [SHADER_FILTERS])
    fun itIsEnlargedWithoutRings() {
        // A field of a smooth ramp, a level every 15 points across, drawn clear of its veils at mid-height.
        val ramp = picture { x, _ -> 0xFF000000.toInt() or (x * 2 * 0x010101) }
        val levels = diffuseField(ramp, W, H).map { it and 0xFF }
        assertTrue("its rows alike", levels.chunked(FIELD_W).distinct().size == 1)
        val field = Bitmap.createBitmap(diffuseField(ramp, W, H), FIELD_W, FIELD_H, Bitmap.Config.ARGB_8888)
        val screen = drawn(field, BackdropStyle.Diffuse.veils.down)
        // Over each tile of the pattern, the screen shows the ramp its levels draw, plus the pattern's half level.
        var before = Float.NaN
        for (tile in 0 until SCREEN_W / TILE) {
            val ideal = (tile * TILE until (tile + 1) * TILE).map { bilinear(levels, it) }.average().toFloat() + HALF
            val error = tileMean(screen, tile * TILE, MID_TILE) - ideal
            assertEquals("tile $tile: the ramp, without its steps", 0f, error, RAMP_SLACK)
            if (!before.isNaN()) assertEquals("tile $tile: no step from its neighbour", before, error, STEP_SLACK)
            before = error
        }
        // The light follows the ramp, and its veils darken the top.
        val row = IntArray(SCREEN_W).also { screen.getPixels(it, 0, SCREEN_W, 0, SCREEN_H / 2, SCREEN_W, 1) }.map { (it shr 8) and 0xFF }
        assertTrue(row.takeLast(60).average() > row.take(60).average() + 20)
        val top = IntArray(SCREEN_W).also { screen.getPixels(it, 0, SCREEN_W, 0, 0, SCREEN_W, 1) }.map { (it shr 8) and 0xFF }
        assertTrue(top.takeLast(60).average() < row.takeLast(60).average() * 0.5)
    }

    @Test
    @Config(sdk = [SHADER_FILTERS])
    fun theFractionOfALevelShows() {
        // An even field of 60 under an even veil leaving 205 of 255: 48.24 levels, its fraction carried by the pattern.
        val field = Bitmap.createBitmap(IntArray(FIELD_W * FIELD_H) { GREY_60 }, FIELD_W, FIELD_H, Bitmap.Config.ARGB_8888)
        val veil = (255 - 205) / 255f
        val screen = drawn(field, listOf(VeilStop(0f, veil), VeilStop(1f, veil)))
        val light = 60 * 205 / 255f
        for (tile in 0 until SCREEN_W / TILE) {
            assertEquals("tile $tile", light + HALF, tileMean(screen, tile * TILE, MID_TILE), FRACTION_SLACK)
        }
    }

    /** [field] drawn on a screen as a wall's, under veils [down] fully lifted. */
    private fun drawn(field: Bitmap, down: List<VeilStop>): Bitmap {
        val screen = Bitmap.createBitmap(SCREEN_W, SCREEN_H, Bitmap.Config.ARGB_8888)
        val (width, height) = SCREEN_W.toFloat() to SCREEN_H.toFloat()
        Canvas(screen).drawRect(0f, 0f, width, height, diffusePaint(field, width, height, down, 1f))
        return screen
    }

    /** The mean level of the pattern's tile of [screen] from [left], [top]: all 64 steps of its dither. */
    private fun tileMean(screen: Bitmap, left: Int, top: Int): Float {
        val points = IntArray(TILE * TILE).also { screen.getPixels(it, 0, TILE, left, top, TILE, TILE) }
        return points.map { (it shr 8) and 0xFF }.average().toFloat()
    }

    /** A field's row of [levels] at screen point [x], enlarged as bilinear filtering does, its middles aligned. */
    private fun bilinear(levels: List<Int>, x: Int): Float {
        val at = (x + 0.5f) * FIELD_W / SCREEN_W - 0.5f
        val from = kotlin.math.floor(at).toInt()
        val share = at - from
        return levels[from.coerceIn(0, FIELD_W - 1)] * (1 - share) + levels[(from + 1).coerceIn(0, FIELD_W - 1)] * share
    }

    @Test
    fun aFieldIsCachedUnderItsOwnKeyBesideItsSample() = runTest {
        val loader = testLoader(files, context) { memoryCache { MemoryCache.Builder(context).maxSizeBytes(MEMORY).build() } }
        val image = ImageRef(BACKDROP) { BACKDROP }
        val field = loader.execute(diffuseRequest(context, image)) as SuccessResult
        assertEquals(FIELD_W, (field.drawable as BitmapDrawable).bitmap.width)
        loader.execute(backdropSampleRequest(context, image))
        val keys = loader.memoryCache!!.keys
        assertEquals(2, keys.size)
        assertTrue(keys.any { DiffuseField.cacheKey in it.extras.values })
        // Found again in memory, the sample still there.
        assertEquals(DataSource.MEMORY_CACHE, (loader.execute(diffuseRequest(context, image)) as SuccessResult).dataSource)
        assertEquals(SAMPLE_PX, loader.memoryCache!![keys.first { DiffuseField.cacheKey !in it.extras.values }]?.bitmap?.width)
    }

    /** Serves, for any test:// address, a noisy picture of the requested size, as a decoder would. */
    private val files = Fetcher.Factory<Uri> { data, options, _ ->
        if (data.scheme != "test") {
            null
        } else {
            Fetcher {
                val width = (options.size.width as? Dimension.Pixels)?.px ?: W
                val height = (options.size.height as? Dimension.Pixels)?.px ?: H
                val noise = IntArray(width * height) { 0xFF000000.toInt() or it * 7919 % 0xFFFFFF }
                val bitmap = Bitmap.createBitmap(noise, width, height, Bitmap.Config.ARGB_8888)
                DrawableResult(BitmapDrawable(context.resources, bitmap), isSampled = true, dataSource = DataSource.DISK)
            }
        }
    }

    private fun picture(argb: (x: Int, y: Int) -> Int) = IntArray(W * H) { argb(it % W, it / W) }

    private companion object {
        const val W = 96
        const val H = 54
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val SCREEN_W = 960
        const val SCREEN_H = 540

        /** The dither pattern's side, and a tile of it at mid-height, clear of the veils. */
        const val TILE = 8
        const val MID_TILE = SCREEN_H / 2 / TILE * TILE

        /** What the pattern adds on average, rounded to the nearest level. */
        const val HALF = 0.5f

        /** In levels: rings, or a pattern stuck at whole levels, step a level every 15 points across. */
        const val RAMP_SLACK = 0.15f
        const val STEP_SLACK = 0.2f
        const val FRACTION_SLACK = 0.1f
        const val GREY_60 = 0xFF3C3C3C.toInt()

        /** Skia on the CPU filters the field from here, as the player's GPU does: Robolectric's API 28 does not. */
        const val SHADER_FILTERS = 35
        const val BACKDROP = "test://fanart/7"
        const val MEMORY = 64 * 1024 * 1024
    }
}
