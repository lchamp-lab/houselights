package app.houselights.ui.design.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.houselights.core.ImageRef
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HouselightsColors
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.drawWindow
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** A source card's posters melt into its plate at their foot, the glass at rest and focus's white with it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class SourceCardTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun thePostersMeltIntoThePlate() {
        val above = FocusRequester()
        val poster = ImageRef("broken") { "file:///nonexistent/poster.jpg" }
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
        // The strip's foot, and a point high in it; its six slots are posters, two thirds as wide as tall.
        fun pixels(): Pair<Color, Color> {
            val card = compose.onNodeWithTag("card").fetchSemanticsNode()
            val window = compose.drawWindow()
            val x = card.positionInWindow.x.toInt() + card.size.width / 12
            val strip = card.size.width / SOURCE_STRIP * 3 / 2
            val y = card.positionInWindow.y.toInt()
            return Color(window.getPixel(x, y + strip - 2)) to Color(window.getPixel(x, y + strip / 4))
        }
        val (foot, high) = pixels()
        assertNear(c.glass, foot)
        compose.runOnIdle { above.requestFocus() }
        compose.press(Key.DirectionDown)
        compose.onNodeWithTag("card").assertIsFocused()
        val (lit, still) = pixels()
        assertNear(c.focus, lit)
        // The posters stay as they are: no white over them.
        assertTrue("the strip stays dark: $still", still.luminance() < 0.1f && abs(still.luminance() - high.luminance()) < 0.02f)
    }

    private fun assertNear(expected: Color, actual: Color, within: Float = 0.03f) {
        val apart = listOf(expected.red - actual.red, expected.green - actual.green, expected.blue - actual.blue).maxOf { abs(it) }
        assertTrue("$actual is not $expected", apart <= within)
    }
}
