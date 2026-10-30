package app.houselights.ui.design.components

import android.app.Application
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.ImageRef
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A poster is decoded at its width by its 2:3 height ([posterDecode]): its preload asks what its card asks, so it hits memory. */
@RunWith(RobolectricTestRunner::class)
class PosterDecodeTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private val item = MediaItem("dune", MediaKind.MOVIE, "Dune", poster = ImageRef("p-dune") { "https://img.test/dune?w=$it" })

    @Test
    fun aPosterIsDecodedAtItsShape() {
        assertEquals(HEIGHT, posterDecode(WIDTH))
        assertEquals("rounded", ODD_HEIGHT, posterDecode(ODD))
    }

    @Test
    fun aPostersPreloadAsksWhatItsCardAsks() {
        listOf(WIDTH, ODD).forEach { px ->
            val card = artworkModel(context, "https://img.test/dune?w=$px", IntSize(px, posterDecode(px)), fades = true)
            assertEquals("at $px", card, posterRequest(context, item, px))
        }
    }

    private companion object {
        const val WIDTH = 240
        const val HEIGHT = 360
        const val ODD = 187
        const val ODD_HEIGHT = 281 // 280.5
    }
}
