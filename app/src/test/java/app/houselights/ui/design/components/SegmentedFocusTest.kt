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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.width
import app.houselights.ui.testing.TV
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
        // Long names, so the weights measure apart by a whole dp at `place`'s size.
        val seasons = listOf("Première saison", "Deuxième saison", "Troisième saison", "Quatrième saison")
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
        val bold = bounds(seasons[0]).width
        compose.runOnIdle { above.requestFocus() }
        compose.press(Key.DirectionDown)
        compose.onNodeWithText(seasons[0]).assertIsFocused().assertIsSelected()
        compose.press(Key.DirectionRight, times = 2)
        compose.onNodeWithText(seasons[2]).assertIsFocused().assertIsSelected()
        compose.onNodeWithText(seasons[0]).assertIsNotSelected()
        assertTrue("the weights measure apart, so a move would show", bounds(seasons[0]).width < bold)
        assertEquals(atRest, words())
    }
}
