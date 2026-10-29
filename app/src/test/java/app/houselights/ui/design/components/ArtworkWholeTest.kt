package app.houselights.ui.design.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Performance: a picture says once it covers what it lies on ([ArtworkImage]'s `onWhole`), a saga's panel
 * then drawing nothing under it: decoded with no see-through part, its loader's fade over, never one that has some.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class ArtworkWholeTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun forgetTheLoader() = Coil.reset()

    /** A JPEG's picture covers what it lies on; one with see-through parts, or that is no picture, does not. */
    @Test
    fun aPictureWithNoSeeThroughPartCovers() {
        assertTrue("a JPEG's", came(picture(opaque = true)).isOpaque())
        assertFalse("a PNG's with see-through parts", came(picture(opaque = false)).isOpaque())
        assertFalse("no picture", came(ColorDrawable()).isOpaque())
    }

    /** The loader fades a picture in as long as its crossfade says, not at all from memory or without one. */
    @Test
    fun aPictureFadesInAsTheLoaderSays() {
        val fading = ImageRequest.Builder(context).data(URL).crossfade(FADE_MS).build()
        assertEquals(FADE_MS.toLong(), came(picture(opaque = true), fading, DataSource.NETWORK).fadeMillis())
        assertEquals("from the disk", FADE_MS.toLong(), came(picture(opaque = true), fading, DataSource.DISK).fadeMillis())
        assertEquals("from memory", 0L, came(picture(opaque = true), fading, DataSource.MEMORY_CACHE).fadeMillis())
        val still = ImageRequest.Builder(context).data(URL).crossfade(false).build()
        assertEquals("without a fade", 0L, came(picture(opaque = true), still, DataSource.NETWORK).fadeMillis())
    }

    /** `onWhole` is told once a picture with no see-through part is decoded, never of one with some. */
    @Test
    fun onWholeIsToldOfAPictureWithNoSeeThroughPart() {
        Coil.setImageLoader(loader())
        var shown = 0
        var whole = 0
        var clear = 0
        compose.setTvContent {
            Column {
                ArtworkImage(URL, "Dune", Modifier.size(SIDE.dp), size = SIZE, onShow = { shown++ }, onWhole = { whole++ })
                ArtworkImage(SEE_THROUGH, "Heat", Modifier.size(SIDE.dp), size = SIZE, onShow = { shown++ }, onWhole = { clear++ })
            }
        }
        compose.waitUntil(TIMEOUT_MS) { shown >= 2 && whole >= 1 }
        compose.waitForIdle()
        assertEquals("a picture with see-through parts", 0, clear)
    }

    private fun picture(opaque: Boolean): BitmapDrawable {
        val bitmap = Bitmap.createBitmap(SIDE, SIDE, if (opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
        if (!opaque) bitmap.setHasAlpha(true)
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun came(
        drawable: Drawable,
        request: ImageRequest = ImageRequest.Builder(context).data(URL).build(),
        source: DataSource = DataSource.NETWORK,
    ) = SuccessResult(drawable, request, source)

    /** A server whose pictures have no see-through part, but those whose address says they have. */
    private fun loader() = ImageLoader.Builder(context)
        .dispatcher(Dispatchers.Unconfined)
        .components {
            add(
                Fetcher.Factory<Any> { data, _, _ ->
                    val opaque = "see-through" !in data.toString()
                    Fetcher { DrawableResult(picture(opaque), isSampled = false, dataSource = DataSource.NETWORK) }
                },
            )
        }
        .build()

    private companion object {
        const val URL = "https://img.test/dune?w=224"
        const val SEE_THROUGH = "https://img.test/see-through?w=224"
        val SIZE = IntSize(224, 336)
        const val SIDE = 40
        const val FADE_MS = 300
        const val TIMEOUT_MS = 5_000L
    }
}
