package app.houselights.ui.design.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HouselightsColors
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.drawWindow
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** DESIGN.md, Focus: the bar's Gradation signs it in white and never takes focus; its places are words. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class NavBarFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val places = FocusRequester()

    private fun bar(status: String? = null) = compose.setTvContent {
        HlNavBar(
            listOf("Accueil", "Films", "Séries"),
            current = 0,
            onPlace = {},
            Modifier.testTag("bar"),
            track = Modifier.focusRequester(places),
            status = status,
        ) { HlIconButton(HlIcons.Settings, "Réglages", onClick = {}) }
    }

    @Test
    // Real drawing, so the mark's ink can be read.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun gradationSignsTheBarInWhiteAndFocusNeverStopsOnIt() {
        bar()
        // Into the places: on the current one, home, a word like the others.
        compose.runOnIdle { places.requestFocus() }
        compose.waitForIdle()
        compose.onNodeWithText("Accueil").assertIsFocused().assertIsSelected()
        // Left from home goes nowhere: the mark before it is no stop.
        compose.press(Key.DirectionLeft)
        compose.onNodeWithText("Accueil").assertIsFocused()
        compose.onNodeWithContentDescription("Accueil").assertDoesNotExist()

        // The mark at the bar's start, its wide bar in projector white: one ink, no ember beside the place.
        val bar = compose.onNodeWithTag("bar").fetchSemanticsNode()
        val window = compose.drawWindow()
        val wide = Color(window.getPixel(bar.positionInWindow.x.toInt() + WIDE_BAR, (bar.positionInWindow.y + bar.size.height / 2).toInt()))
        assertNear(HouselightsColors.content, wide)
    }

    /** Back's line: before the tiles, on the bar's band, read out politely as it comes. */
    @Test
    fun theStatusStandsBeforeTheTilesAndIsReadOutAsItComes() {
        bar(status = "Appuyez encore sur Retour pour quitter")
        val line = compose.onNodeWithText("Appuyez encore sur Retour pour quitter")
        line.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        val status = line.getUnclippedBoundsInRoot()
        val tile = compose.onNode(hasContentDescription("Réglages")).getUnclippedBoundsInRoot()
        val band = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        assertTrue("the line ends at ${status.right}, before the tile at ${tile.left}", status.right <= tile.left)
        assertTrue("the line lies in the bar's band", status.top >= band.top && status.bottom <= band.bottom)
        assertEquals("centred on the tiles", (tile.top + tile.bottom).value / 2, (status.top + status.bottom).value / 2, 1f)
    }

    private fun assertNear(expected: Color, actual: Color, within: Float = 0.03f) {
        val apart = listOf(expected.red - actual.red, expected.green - actual.green, expected.blue - actual.blue).maxOf { abs(it) }
        assertTrue("$actual is not $expected", apart <= within)
    }

    private companion object {
        /** Inside the mark's wide bar, from the bar's start, in pixels at density 1. */
        const val WIDE_BAR = 4
    }
}
