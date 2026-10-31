package app.houselights.ui.design.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.ImageRef
import app.houselights.ui.design.HomeGridDefaults
import app.houselights.ui.design.canvasDensityOf
import app.houselights.ui.testing.setTvContent
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DESIGN.md, Pictures and titles: a logo narrower than its box's pixels is drawn at its own pixels at 1080p, and at
 * 4K as large as at 1080p, not at its pixels there, half the size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogoSizeTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Every logo a file of [LOGO], all ink, whatever size is asked: a server's has one size for every screen. */
    private val logos = Fetcher.Factory<Any> { _, options, _ ->
        Fetcher {
            val logo = Bitmap.createBitmap(LOGO.width, LOGO.height, Bitmap.Config.ARGB_8888).apply { eraseColor(INK) }
            DrawableResult(BitmapDrawable(options.context.resources, logo), isSampled = false, dataSource = DataSource.NETWORK)
        }
    }

    @Before
    fun serveLogos() = coil.serve(logos)

    @get:Rule
    val coil = CoilRule()

    /** The case's logo as drawn, in px. */
    private fun drawn(): IntSize {
        val logo = ImageRef("logo") { "https://img.test/logo?w=$it" }
        compose.setTvContent { TitleArt("Heat", logo, HomeGridDefaults.caseLogoWidth, HomeGridDefaults.caseLogoHeight) }
        compose.waitForIdle()
        return compose.onNodeWithContentDescription("Heat").fetchSemanticsNode().size
    }

    /** How many times the canvas's px at 1080p this screen has. */
    private fun scale(): Int = (canvasDensityOf(context.resources.displayMetrics.widthPixels).density / FULL_HD_DENSITY).toInt()

    @Test
    @Config(qualifiers = FULL_HD)
    fun at1080pALogoIsNeverEnlarged() {
        assertEquals(1, scale())
        assertEquals(LOGO, drawn())
    }

    @Test
    @Config(qualifiers = UHD)
    fun at4kALogoIsAsLargeAsAt1080p() {
        assertEquals(2, scale())
        assertEquals(IntSize(LOGO.width * 2, LOGO.height * 2), drawn())
    }

    private companion object {
        /** Narrower than the case's logo would be at 1080p, so its own pixels hold it there. */
        val LOGO = IntSize(300, 75)
        const val INK = 0xFFFFFFFF.toInt()
        const val FULL_HD_DENSITY = 2f
    }
}

private const val FULL_HD = "w960dp-h540dp-land-xhdpi"
private const val UHD = "w960dp-h540dp-land-xxxhdpi"
