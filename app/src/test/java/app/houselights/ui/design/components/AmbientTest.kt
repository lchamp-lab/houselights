package app.houselights.ui.design.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.ImageRef
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HouselightsColors
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.Options
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

/**
 * A picture's light is the tone that covers it, its dark low enough under any text; the tiny copies it and
 * the veils are read from stay out of the way of the posters in Coil's memory cache.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AmbientTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Serves a picture of the requested size, as a decoder would, and counts the files read. */
    private val files = object : Fetcher.Factory<Uri> {
        var reads = 0

        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.scheme != "test") {
                null
            } else {
                Fetcher {
                    reads++
                    val width = (options.size.width as? Dimension.Pixels)?.px ?: FULL
                    val height = (options.size.height as? Dimension.Pixels)?.px ?: FULL
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    DrawableResult(BitmapDrawable(context.resources, bitmap), isSampled = true, dataSource = DataSource.DISK)
                }
            }
    }

    private val loader = ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder(context).maxSizeBytes(MEMORY).build() }
        .dispatcher(Dispatchers.Unconfined)
        .components { add(files) }
        .build()

    private fun card(url: String) = ImageRequest.Builder(context).data(url).size(CARD_W, CARD_H).build()

    @Test
    fun aCardsLightLeavesItsPosterInMemory() = runTest {
        loader.execute(card(POSTER))
        loader.execute(glowSampleRequest(context, POSTER))
        val again = loader.execute(card(POSTER)) as SuccessResult
        assertEquals(DataSource.MEMORY_CACHE, again.dataSource)
        assertEquals("the poster, then its light", 2, files.reads)
    }

    /** Its tone is cached on its own: the copy it was read from would only take a picture's room in memory. */
    @Test
    fun aCardsLightIsNotKeptInMemory() = runTest {
        loader.execute(card(POSTER))
        loader.execute(glowSampleRequest(context, POSTER))
        assertEquals(setOf(MemoryCache.Key(POSTER)), loader.memoryCache?.keys)
    }

    @Test
    fun aBackdropsVeilLeavesItsPictureInMemory() = runTest {
        // A local file: the same path whatever the width asked for.
        val image = ImageRef(BACKDROP) { BACKDROP }
        loader.execute(ImageRequest.Builder(context).data(BACKDROP).size(SCREEN_W, SCREEN_H).build())
        loader.execute(backdropSampleRequest(context, image))
        assertEquals(SCREEN_W, loader.memoryCache?.get(MemoryCache.Key(BACKDROP))?.bitmap?.width)
        // And the veil of the next showing finds its own copy.
        loader.execute(backdropSampleRequest(context, image))
        assertEquals(2, files.reads)
    }

    @Test
    fun aPicturesDarkIsDarkEnoughInEveryHue() {
        (0 until 360).forEach { hue ->
            listOf(HlScrim.GLOW_GREY, 0.3f, 0.5f, 0.75f, 1f).forEach { saturation ->
                val deep = deepOf(hue.toFloat(), saturation)
                assertTrue("$hue°, $saturation: ${deep.luminance()}", deep.luminance() <= HlScrim.DEEP_LUMINANCE_MAX)
                // Never crushed to black: the deepest blue keeps its value.
                assertTrue("$hue°, $saturation: ${deep.luminance()}", deep.luminance() > 0.002f)
            }
        }
    }

    @Test
    fun aFewSparksDoNotTintARedPicture() {
        // Dark red with a few yellow sparks, as Evil Dead: red, not olive.
        val light = lightOf(palette(96, 54) { x, y -> if (x % 23 == 0 && y % 11 == 0) 0xFFF0A810.toInt() else 0xFF681010.toInt() })
        val hsv = hsv(light.deep)
        assertTrue("${hsv.toList()}", hsv[0] < 10f || hsv[0] > 350f)
        assertTrue(hsv(light.glow)[0].let { it < 10f || it > 350f })
    }

    @Test
    fun aRedTitleOnTealIsTeal() {
        val light = lightOf(palette(96, 54) { x, y -> if (x in 30..60 && y in 20..30) 0xFFD02020.toInt() else 0xFF1E6E68.toInt() })
        assertTrue("${hsv(light.deep).toList()}", hsv(light.deep)[0] in 160f..190f)
    }

    @Test
    fun aGreyPictureGetsTheNeutralDarkAndAWhiteGlow() {
        val light = lightOf(palette(96, 54) { x, _ -> 0xFF000000.toInt() or ((40 + x * 2) * 0x010101) })
        assertEquals(ImageLight.Grey, light)
        assertEquals(HouselightsColors.ambientDeep, light.deep)
        assertEquals(0f, hsv(light.glow)[1], 0f)
    }

    @Test
    fun theGlowIsBrightAndNeverGarish() {
        (0 until 360 step 7).forEach { hue ->
            listOf(0.12f, 0.3f, 0.6f, 0.95f).forEach { saturation ->
                val (h, s, v) = hsv(glowOf(hue.toFloat(), saturation)).toList()
                assertEquals("value at $hue°", HlScrim.GLOW_VALUE, v, 1f / 255)
                assertTrue("saturation $s at $hue°", s in HlScrim.GLOW_SATURATION_MIN - 0.01f..HlScrim.GLOW_SATURATION_MAX + 0.01f)
                assertTrue(h >= 0f)
            }
        }
    }

    @Test
    fun aWallNeverAsksForMoreThanItsSample() {
        val image = ImageRef(BACKDROP) { "$BACKDROP?w=$it" }
        val urls = backdropRequests(context, image, BackdropStyle.Diffuse, SCREEN_W).map { it.data }
        assertEquals(listOf("$BACKDROP?w=$SAMPLE_PX", "$BACKDROP?w=$SAMPLE_PX"), urls)
    }

    /** [width] × [height] points of [argb] at each, as Palette reads a sample. */
    private fun palette(width: Int, height: Int, argb: (Int, Int) -> Int): Palette {
        val pixels = IntArray(width * height) { argb(it % width, it / width) }
        return Palette.from(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)).maximumColorCount(16).generate()
    }

    private fun hsv(color: Color) = FloatArray(3).also { android.graphics.Color.colorToHSV(color.toArgb(), it) }

    private companion object {
        const val POSTER = "test://poster/42"
        const val BACKDROP = "test://fanart/42"
        const val CARD_W = 200
        const val CARD_H = 300
        const val SCREEN_W = 1920
        const val SCREEN_H = 1080
        const val FULL = 1000
        const val MEMORY = 64 * 1024 * 1024
    }
}
