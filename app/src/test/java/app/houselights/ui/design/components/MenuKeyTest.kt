package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.UserState
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HlStroke
import app.houselights.ui.design.HomeGridDefaults
import app.houselights.ui.design.TEXT_SCALE_MAX
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

/**
 * DESIGN.md, Focus, Menu: what has options shows the Menu key, in a card's corner or under a tile, on the one with focus
 * alone and under no sheet, and nothing moves for it.
 */
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

    /** An episode's card: the key in its still's bottom right corner with focus, its caption's words where they were. */
    @Test
    fun anEpisodesKeyIsInItsCornerWithFocusTheCaptionUnmoved() {
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
        assertInCorner(keys().single(), picture(), line = 0f)
        assertEquals("the words stay where they were", atRest, caption())
    }

    /** A poster's key: only on the one with focus, above its progress line once started, never under a sheet. */
    @Test
    fun aPostersKeyIsOnTheFocusedOneAloneAboveItsProgressLine() {
        var sheet by mutableStateOf(false)
        compose.setTvContent {
            Row {
                PosterCard(MediaItem("m1", MediaKind.MOVIE, "Alien"), {}, Modifier.focusRequester(first), onLongClick = {})
                PosterCard(STARTED, {}, Modifier.testTag(SECOND), onLongClick = {})
                // A poster without options has none.
                PosterCard(MediaItem("m3", MediaKind.MOVIE, "Heat"), {}, Modifier.testTag(THIRD))
            }
            if (sheet) HlSideSheet("Le Grand Bleu", onDismiss = { sheet = false }) {}
        }
        compose.runOnIdle { first.requestFocus() }
        compose.waitForIdle()
        assertInCorner(
            keys().single(),
            bounds(compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and hasText("Alien"))),
            0f,
        )

        compose.press(Key.DirectionRight)
        // Above the line, which stays whole at the poster's foot.
        assertInCorner(keys().single(), bounds(compose.onNodeWithTag(SECOND)), HlStroke.bold.value)

        compose.press(Key.DirectionRight)
        assertEquals("no options, no key", emptyList<Rect>(), keys())
        compose.press(Key.DirectionLeft)
        assertEquals(1, keys().size)
        sheet = true
        compose.waitForIdle()
        assertEquals("under a sheet, focus is out", emptyList<Rect>(), keys())
    }

    /** At the largest text size the key keeps its size and its place, as the state mark does. */
    @Test
    fun theKeyKeepsItsSizeAtTheLargestText() {
        compose.setTvContent(textStep = TEXT_SCALE_MAX) {
            PosterCard(STARTED, {}, Modifier.focusRequester(first).testTag(SECOND), onLongClick = {})
        }
        compose.runOnIdle { first.requestFocus() }
        compose.waitForIdle()
        val key = keys().single()
        assertEquals(HlSize.menuKeyWidth.value, key.width, 0.5f)
        assertEquals(HlSize.menuKeyHeight.value, key.height, 0.5f)
        assertInCorner(key, bounds(compose.onNodeWithTag(SECOND)), HlStroke.bold.value)
    }

    private fun bounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInRoot

    /** [key] in [card]'s bottom right corner, the state mark's distance from its edges, above a progress line [line] tall. */
    private fun assertInCorner(key: Rect, card: Rect, line: Float) {
        val inset = HlSpace.xs.value
        assertEquals("$key at $card's end", card.right - inset, key.right, 0.5f)
        assertEquals("$key at $card's foot", card.bottom - inset - line, key.bottom, 0.5f)
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

    /** Home's case: its facts keep their score, the key showing at its action's end with focus, taking none of their room. */
    @Test
    fun theCasesKeyIsInItsActionAndItsFactsKeepTheirScore() {
        val film = MediaItem(
            "tgm",
            MediaKind.MOVIE,
            "Top Gun: Maverick",
            year = 2022,
            runtimeMs = 7_800_000,
            officialRating = "TP",
            communityRating = 8.2,
        )
        compose.setTvContent {
            Box(Modifier.size(HomeGridDefaults.case, caseHeight())) {
                ResumeCase(film, "Resume", {}, Modifier.focusRequester(first), onLongClick = {}, onLongClickLabel = "More options")
            }
        }
        compose.onNodeWithText("8.2", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("no key at rest", emptyList<Rect>(), keys())
        compose.runOnIdle { first.requestFocus() }
        compose.waitForIdle()
        compose.onNodeWithText("8.2", useUnmergedTree = true).assertIsDisplayed()
        val key = keys().single()
        val action = compose.onNodeWithText("Resume", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("on the action's line", action.center.y, key.center.y, 1f)
        assertTrue("after its words: $action, $key", key.left > action.right)
    }

    /** A result's options: the key at the end of its name's line with focus, its room kept, so the name never moves. */
    @Test
    fun aResultKeepsItsKeysRoomAndShowsItWithFocus() {
        compose.setTvContent {
            Column {
                HlButton("Lire", {}, Modifier.focusRequester(first))
                HlResultItem(null, LONG, listOf("1984", "Plusieurs titres possibles"), "Films/Dune.mkv", {}, onLongClick = {})
            }
        }
        compose.runOnIdle { first.requestFocus() }
        compose.waitForIdle()
        assertEquals(emptyList<Rect>(), keys())
        val name = { compose.onNodeWithText(LONG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot }
        val atRest = name()

        compose.press(Key.DirectionDown)
        val key = keys().single()
        assertEquals("the name stays where it was", atRest, name())
        assertTrue("$atRest beside $key", atRest.right <= key.left && key.top < atRest.bottom)
    }

    private companion object {
        const val TILE = "tile"
        const val SECOND = "second"
        const val THIRD = "third"

        /** A film started: its progress line burns at its poster's foot. */
        val STARTED = MediaItem("m2", MediaKind.MOVIE, "Le Grand Bleu", runtimeMs = 10_080_000, user = UserState(positionMs = 5_640_000))
        const val LONG = "Où l’on comprend enfin pourquoi les hiboux ne sont pas ce qu’ils semblent être"
    }
}
