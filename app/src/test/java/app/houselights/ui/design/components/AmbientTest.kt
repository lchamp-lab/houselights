package app.houselights.ui.design.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.ImageRef
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The tiny copies the light and the veil are read from stay apart from the pictures shown, in
 * Coil's memory: a card coming back finds its poster there, without decoding or fading it in again.
 */
@RunWith(RobolectricTestRunner::class)
class AmbientTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Hands out a picture of the size asked for, as a decoder would, and counts the files read. */
    private val files = object : Fetcher.Factory<Uri> {
        var reads = 0

        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.scheme != "test") null else Fetcher {
                reads++
                val width = (options.size.width as? Dimension.Pixels)?.px ?: FULL
                val height = (options.size.height as? Dimension.Pixels)?.px ?: FULL
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                DrawableResult(BitmapDrawable(context.resources, bitmap), isSampled = true, dataSource = DataSource.DISK)
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

    /** Its tone is kept apart: the copy it was read from would only take a picture's room in memory. */
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
