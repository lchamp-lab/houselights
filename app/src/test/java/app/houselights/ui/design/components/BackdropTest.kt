package app.houselights.ui.design.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.ImageRef
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.SuccessResult
import coil.size.Dimension
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** A backdrop is decoded at most at the size of its file, a wall's never: the layer it is drawn into enlarges it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackdropTest {
    @get:Rule
    val coil = CoilRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Serves, for any test:// address, a PNG file of [width] by [height], as a disk would; [asked] gets each width asked for. */
    private fun files(width: Int, height: Int, asked: MutableList<Int?>) = Fetcher.Factory<Uri> { data, options, _ ->
        if (data.scheme != "test") {
            null
        } else {
            Fetcher {
                asked += (options.size.width as? Dimension.Pixels)?.px
                val png = ByteArrayOutputStream().also {
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                SourceResult(ImageSource(Buffer().write(png.toByteArray()), context), mimeType = "image/png", dataSource = DataSource.DISK)
            }
        }
    }

    /** A loader of [files], working on the thread that asks. */
    private fun loader(width: Int, height: Int, asked: MutableList<Int?> = mutableListOf()) =
        testLoader(files(width, height, asked), context) { allowHardware(false) }

    /** The backdrop of a screen of [SCREEN_W] by [SCREEN_H], as decoded from a file of [width] by [height]. */
    private fun decoded(width: Int, height: Int): Bitmap {
        lateinit var bitmap: Bitmap
        runTest {
            val result = loader(width, height).execute(artworkRequest(context, "test://backdrop/42", SCREEN_W, SCREEN_H)) as SuccessResult
            bitmap = (result.drawable as BitmapDrawable).bitmap
        }
        return bitmap
    }

    @Test
    fun aSmallerFileIsNotEnlargedInMemory() {
        val picture = decoded(1280, 720)
        assertEquals(1280 to 720, picture.width to picture.height)
    }

    @Test
    fun aLargerFileStillComesDownToTheScreen() {
        val picture = decoded(3840, 2160)
        assertEquals(SCREEN_W to SCREEN_H, picture.width to picture.height)
    }

    @Test
    fun aBackdropKnowsWhetherItCameFromMemory() {
        coil.serve(loader(1280, 720))
        val image = ImageRef("backdrop42") { "test://backdrop/42?w=$it" }
        runTest {
            val first = prepareBackdrop(context, image, BackdropStyle.Hero, SCREEN_W)
            val again = prepareBackdrop(context, image, BackdropStyle.Hero, SCREEN_W)
            assertEquals("read from the file", false, first?.fromMemory)
            assertEquals("found in memory", true, again?.fromMemory)
        }
    }

    @Test
    fun aWallPreparesItsDiffuseFieldNeverItsPicture() {
        val asked = mutableListOf<Int?>()
        coil.serve(loader(1280, 720, asked))
        val image = ImageRef("backdrop42") { "test://backdrop/42?w=$it" }
        runTest {
            val first = prepareBackdrop(context, image, BackdropStyle.Diffuse, SCREEN_W)
            val again = prepareBackdrop(context, image, BackdropStyle.Diffuse, SCREEN_W)
            assertEquals(FIELD_W to FIELD_H, first?.picture?.let { it.width to it.height })
            assertEquals("read from the file", false, first?.fromMemory)
            assertEquals("found in memory", true, again?.fromMemory)
            assertTrue("widths asked: $asked", asked.isNotEmpty() && asked.all { it != null && it <= SAMPLE_PX })
        }
    }

    private companion object {
        const val SCREEN_W = 1920
        const val SCREEN_H = 1080
    }
}
