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
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.memory.MemoryCache
import coil.request.SuccessResult
import coil.size.Dimension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.sin

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
            val v = ((sin(x * 12.9898f + y * 78.233f) * 43758.547f).let { it - kotlin.math.floor(it) } * 255).toInt()
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
    fun itIsEnlargedWithoutRings() {
        // A field of a smooth ramp, a level every 15 points across: drawn, no level holds more than a few in a row.
        val ramp = picture { x, _ -> 0xFF000000.toInt() or (x * 2 * 0x010101) }
        val field = Bitmap.createBitmap(diffuseField(ramp, W, H), FIELD_W, FIELD_H, Bitmap.Config.ARGB_8888)
        val screen = Bitmap.createBitmap(SCREEN_W, SCREEN_H, Bitmap.Config.ARGB_8888)
        val (width, height) = SCREEN_W.toFloat() to SCREEN_H.toFloat()
        Canvas(screen).drawRect(0f, 0f, width, height, diffusePaint(field, width, height, BackdropStyle.Diffuse.veils.down, 1f))
        val row = IntArray(SCREEN_W).also { screen.getPixels(it, 0, SCREEN_W, 0, SCREEN_H / 2, SCREEN_W, 1) }.map { (it shr 8) and 0xFF }
        val runs = mutableListOf(1)
        row.zipWithNext().forEach { (a, b) -> if (a == b) runs[runs.lastIndex]++ else runs += 1 }
        val longest = runs.sorted()[(runs.size * 0.95f).toInt().coerceAtMost(runs.lastIndex)]
        assertTrue("95 % of runs under $longest points: $runs", longest <= 4)
        // The light follows the ramp, and its veils darken the top.
        assertTrue(row.takeLast(60).average() > row.take(60).average() + 20)
        val top = IntArray(SCREEN_W).also { screen.getPixels(it, 0, SCREEN_W, 0, 0, SCREEN_W, 1) }.map { (it shr 8) and 0xFF }
        assertTrue(top.takeLast(60).average() < row.takeLast(60).average() * 0.5)
    }

    @Test
    fun aFieldIsCachedUnderItsOwnKeyBesideItsSample() = runTest {
        val loader = ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder(context).maxSizeBytes(MEMORY).build() }
            .dispatcher(Dispatchers.Unconfined)
            .components { add(files) }
            .build()
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
        if (data.scheme != "test") null else Fetcher {
            val width = (options.size.width as? Dimension.Pixels)?.px ?: W
            val height = (options.size.height as? Dimension.Pixels)?.px ?: H
            val noise = IntArray(width * height) { 0xFF000000.toInt() or it * 7919 % 0xFFFFFF }
            val bitmap = Bitmap.createBitmap(noise, width, height, Bitmap.Config.ARGB_8888)
            DrawableResult(BitmapDrawable(context.resources, bitmap), isSampled = true, dataSource = DataSource.DISK)
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
        const val BACKDROP = "test://fanart/7"
        const val MEMORY = 64 * 1024 * 1024
    }
}
