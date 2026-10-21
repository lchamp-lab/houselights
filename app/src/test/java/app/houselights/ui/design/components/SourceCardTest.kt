package app.houselights.ui.design.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.houselights.core.ImageRef
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HouselightsColors
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.drawWindow
import app.houselights.ui.testing.pictureServer
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import coil.Coil
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * A source card's posters melt into its plate from 62 % of their height, in the glass at rest and in focus's white
 * with it; above, they stay as they are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class SourceCardTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun forgetTheLoader() = Coil.reset()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun thePostersMeltIntoThePlate() {
        val grey = Color(GREY, GREY, GREY)
        val pictures = ImageLoader.Builder(compose.activity).dispatcher(Dispatchers.Unconfined)
        Coil.setImageLoader(pictures.components { add(pictureServer(grey.toArgb())) }.build())
        val above = FocusRequester()
        val poster = ImageRef("poster") { "https://img.test/poster?w=$it" }
        compose.setTvContent {
            Column {
                HlButton("Avant", onClick = {}, modifier = Modifier.focusRequester(above))
                SourceCard(
                    "Films", "NFS", "192.168.1.20/volume1/media/films", List(SOURCE_STRIP) { poster }, onClick = {},
                    modifier = Modifier.width(HlLayout.readableNarrow).testTag("card"),
                    facts = listOf("11 titres"),
                    scanned = "Analysé à l’instant",
                )
            }
        }
        val c = HouselightsColors
        // In the first poster: high up, where the melt starts, nine tenths down, and at its foot.
        fun pixels(): List<Color> {
            val card = compose.onNodeWithTag("card").fetchSemanticsNode()
            val window = compose.drawWindow()
            val gap = HlSize.hairline.value * compose.density.density
            val slot = (card.size.width - gap * (SOURCE_STRIP - 1)) / SOURCE_STRIP
            val strip = slot / HlSize.POSTER_RATIO
            val x = (card.positionInWindow.x + slot / 2).toInt()
            val y = card.positionInWindow.y
            val heights = listOf(HIGH, HlScrim.STRIP_FOOT.first().at - MARGIN, LOW, FOOT)
            return heights.map { Color(window.getPixel(x, (y + strip * it).toInt())) }
        }
        val rest = pixels()
        assertNear(grey, rest[0])
        assertNear(grey, rest[1])
        assertTrue("melting into the glass: ${rest[2]}", rest[2].luminance() < grey.luminance() - STEP)
        assertNear(c.glass, rest[3])
        compose.runOnIdle { above.requestFocus() }
        compose.press(Key.DirectionDown)
        compose.onNodeWithTag("card").assertIsFocused()
        val lit = pixels()
        // The posters stay as they are; their foot melts into focus's white.
        assertNear(grey, lit[0])
        assertNear(grey, lit[1])
        assertTrue("melting into the white: ${lit[2]}", lit[2].luminance() > grey.luminance() + STEP)
        assertNear(c.focus, lit[3])
    }

    private fun assertNear(expected: Color, actual: Color, within: Float = 0.03f) {
        val apart = listOf(expected.red - actual.red, expected.green - actual.green, expected.blue - actual.blue).maxOf { abs(it) }
        assertTrue("$actual is not $expected", apart <= within)
    }

    private companion object {
        const val GREY = 0.5f
        const val HIGH = 0.3f
        const val MARGIN = 0.03f
        const val LOW = 0.9f
        const val FOOT = 0.99f
        const val STEP = 0.05f
    }
}
