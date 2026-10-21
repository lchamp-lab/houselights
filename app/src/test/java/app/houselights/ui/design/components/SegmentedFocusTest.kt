package app.houselights.ui.design.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.width
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

/** DESIGN.md, Focus: focus entering an [HlSegmented] track lands on the current option, from any side. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class SegmentedFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun focusEntersATrackOnTheCurrentOption() {
        val above = FocusRequester()
        compose.setTvContent {
            Column {
                HlButton("Trier", onClick = {}, modifier = Modifier.focusRequester(above).testTag("above"))
                HlSegmented(listOf("Accueil", "Films", "Séries", "Collections"), selected = 2, onSelect = {})
                HlButton("Filtrer", onClick = {}, modifier = Modifier.testTag("below"))
            }
        }
        compose.runOnIdle { above.requestFocus() }
        // From above the first option: the current one, not the nearest.
        compose.press(Key.DirectionDown)
        compose.onNodeWithText("Séries").assertIsFocused()
        compose.onNodeWithText("Accueil").assertIsNotFocused()

        // Along the track, out of it, and back in from below: the current one again.
        compose.press(Key.DirectionLeft, times = 2)
        compose.onNodeWithText("Accueil").assertIsFocused()
        compose.press(Key.DirectionDown)
        compose.onNodeWithTag("below").assertIsFocused()
        compose.press(Key.DirectionUp)
        compose.onNodeWithText("Séries").assertIsFocused()
    }

    /** A track that selects as focus moves turns each word it reaches bold and lit: none of the words moves. */
    @Test
    // Real fonts, so a word measures wider in bold.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun choosingAWordMovesNone() {
        val above = FocusRequester()
        val seasons = (1..4).map { "Saison $it" }
        compose.setTvContent {
            var selected by remember { mutableIntStateOf(0) }
            Column {
                HlButton("Trier", onClick = {}, modifier = Modifier.focusRequester(above))
                HlSegmented(seasons, selected = selected, onSelect = { selected = it }, selectOnFocus = true)
            }
        }
        fun bounds(word: String) = compose.onNodeWithText(word, useUnmergedTree = true).getUnclippedBoundsInRoot()
        fun words() = seasons.map { bounds(it).left }
        val atRest = words()
        val bold = bounds("Saison 1").width
        compose.runOnIdle { above.requestFocus() }
        compose.press(Key.DirectionDown)
        compose.onNodeWithText("Saison 1").assertIsFocused().assertIsSelected()
        compose.press(Key.DirectionRight, times = 2)
        compose.onNodeWithText("Saison 3").assertIsFocused().assertIsSelected()
        compose.onNodeWithText("Saison 1").assertIsNotSelected()
        assertTrue("the weights measure apart, so a move would show", bounds("Saison 1").width < bold)
        assertEquals(atRest, words())
    }

    /** Home's bar: its first place is Gradation, read as its name, a tab like the words, inked as they are. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun homeIsAPlaceDrawnAsGradation() {
        val above = FocusRequester()
        compose.setTvContent {
            var selected by remember { mutableIntStateOf(0) }
            Column {
                HlButton("Trier", onClick = {}, modifier = Modifier.focusRequester(above))
                HlSegmented(listOf("Accueil", "Films", "Séries"), selected = selected, onSelect = { selected = it }, markFirst = true)
            }
        }
        val c = HouselightsColors
        val home = compose.onNodeWithContentDescription("Accueil")
        compose.onNodeWithText("Accueil").assertDoesNotExist()
        home.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertIsSelected()
        // Current: the ember light, orange at the wide bar to raspberry at the thin one.
        val (wide, thin) = bars()
        assertTrue("the wide bar is the warmer: $wide, $thin", wide.green > thin.green + 0.2f)
        assertNear(c.emberTo, thin, 0.1f)

        compose.runOnIdle { above.requestFocus() }
        compose.press(Key.DirectionDown)
        home.assertIsFocused()
        // On focus's white, the mark stays lit in the one ember that holds there.
        bars().toList().forEach { assertNear(c.emberOnLight, it) }

        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText("Films").assertIsFocused().assertIsSelected()
        home.assertIsNotSelected()
        // No longer the place: a word's grey at rest, black on focus's white.
        bars().toList().forEach { assertNear(c.contentSecondary, it) }
        compose.press(Key.DirectionLeft)
        bars().toList().forEach { assertNear(c.onFocus, it) }
    }

    /** The middle of the mark's wide bar and of its thin one, as the window draws them. */
    private fun bars(): Pair<Color, Color> {
        val mark = compose.onNodeWithContentDescription("Accueil", useUnmergedTree = true).fetchSemanticsNode()
        val window = compose.drawWindow()
        val left = mark.positionInWindow.x.toInt()
        val middle = mark.positionInWindow.y.toInt() + mark.size.height / 2
        fun at(x: Int) = Color(window.getPixel(left + x, middle))
        return at(mark.size.width * 2 / 9) to at(mark.size.width - 1)
    }

    private fun assertNear(expected: Color, actual: Color, within: Float = 0.02f) {
        val apart = listOf(expected.red - actual.red, expected.green - actual.green, expected.blue - actual.blue).maxOf { abs(it) }
        assertTrue("$actual is not $expected", apart <= within)
    }
}
