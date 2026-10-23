package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSize
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

/** DESIGN.md, Focus, Menu: what has options shows the Menu key, under a card or a tile, and nothing moves for it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
// Real fonts, so a name fills the line it is given.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MenuKeyTest {
    @get:Rule
    val compose = createComposeRule()

    private val first = FocusRequester()

    private fun keys(): List<Rect> =
        compose.onAllNodes(hasTestTag(MENU_KEY), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }

    /** The card's picture: the one surface with a long click. */
    private fun picture(): Rect =
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)).fetchSemanticsNode().boundsInRoot

    /** The words under the card, by what they say: its caption's, not its fallback artwork's. */
    private fun caption(): Map<String, Rect> {
        val bottom = picture().bottom
        return compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.boundsInRoot.top >= bottom }
            .associate { node -> node.config[SemanticsProperties.Text].joinToString() to node.boundsInRoot }
    }

    @Test
    fun aCardKeepsItsKeysRoomAndShowsItWithFocus() {
        compose.setTvContent {
            Row {
                HlButton("Lire", {}, Modifier.focusRequester(first))
                LandscapeCard(
                    MediaItem("e1", MediaKind.EPISODE, LONG),
                    {},
                    width = HlSize.episode,
                    title = LONG,
                    number = "12",
                    facts = listOf("52 min", null),
                    onLongClick = {},
                    onLongClickLabel = "Plus d’options",
                )
            }
        }
        compose.runOnIdle { first.requestFocus() }
        compose.waitForIdle()
        assertEquals(emptyList<Rect>(), keys())
        val atRest = caption()

        compose.press(Key.DirectionRight)
        val key = keys().single()
        assertEquals("the key ends with the card", picture().right, key.right, 0.5f)
        // A long name stops short of the key, and the words stay where they were.
        assertEquals(atRest, caption())
        val name = atRest.getValue(LONG)
        assertTrue("$name beside $key", name.right <= key.left && key.top < name.bottom)
    }

    /** A tile's options: the key on its label's second line; a tile without has none. */
    @Test
    fun aTileWithOptionsHasTheKeyUnderIt() {
        compose.setTvContent {
            Row {
                HlIconButton(HlIcons.Check, "Marquer la saison comme vue", {}, Modifier.testTag(TILE), onLongClick = {})
                HlIconButton(HlIcons.Heart, "Ajouter aux favoris", {})
            }
        }
        val tile = compose.onNodeWithTag(TILE).fetchSemanticsNode().boundsInRoot
        val key = keys().single()
        assertTrue("$key under $tile", key.top >= tile.bottom)
    }

    private companion object {
        const val TILE = "tile"
        const val LONG = "Où l’on comprend enfin pourquoi les hiboux ne sont pas ce qu’ils semblent être"
    }
}
