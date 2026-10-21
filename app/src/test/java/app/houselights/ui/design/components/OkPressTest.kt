package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.Text
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlShapes
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.holdOk
import app.houselights.ui.testing.longClickNamed
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** OK clicks the surface that took its press, on its release, and a long click spends it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
@OptIn(ExperimentalTestApi::class)
class OkPressTest {
    @get:Rule
    val compose = createComposeRule()

    private val clicks = mutableListOf<String>()
    private var longClicks = 0
    private val first = FocusRequester()

    @Composable
    private fun Option(name: String, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
        HlFocusSurface(
            onClick = { clicks += name },
            look = FocusStyle.fill(rest = Hl.colors.glass, restInk = Hl.colors.content),
            shape = HlShapes.control,
            modifier = modifier.testTag(name),
            onLongClick = onLongClick,
        ) { Text(name) }
    }

    private fun showTwo(onLongClick: (() -> Unit)? = null) {
        compose.setTvContent {
            Row {
                Option("A", Modifier.focusRequester(first), onLongClick)
                Option("B")
            }
        }
        compose.runOnIdle { first.requestFocus() }
    }

    @Test
    fun okLetGoOnAnotherSurfaceClicksNothing() {
        showTwo()
        // The thumb slides from OK to the right arrow: the press stays with A, focus goes to B.
        compose.onRoot().performKeyInput {
            keyDown(Key.DirectionCenter)
            pressKey(Key.DirectionRight)
            keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("B").assertIsFocused()
        assertEquals(emptyList<String>(), clicks)

        compose.press(Key.DirectionCenter)
        assertEquals(listOf("B"), clicks)
    }

    @Test
    fun aLongClickIsNotAClickToo() {
        showTwo(onLongClick = { longClicks++ })
        compose.holdOk()
        assertEquals(1, longClicks)
        assertEquals(emptyList<String>(), clicks)

        compose.press(Key.DirectionCenter)
        assertEquals(listOf("A"), clicks)
    }

    /** The watched tile and an episode: held, OK does their long click alone, which each names to accessibility. */
    @Test
    fun aTileAndAnEpisodeNameTheirLongClick() {
        val marked = mutableListOf<String>()
        compose.setTvContent {
            Row {
                HlIconButton(
                    HlIcons.Check,
                    SEASON,
                    onClick = { clicks += "tile" },
                    modifier = Modifier.focusRequester(first),
                    onLongClick = { marked += "show" },
                    onLongClickLabel = SHOW,
                )
                LandscapeCard(
                    MediaItem("e1", MediaKind.EPISODE, "Pilote"),
                    onClick = { clicks += "episode" },
                    onLongClick = { marked += "episode" },
                    onLongClickLabel = EPISODE,
                )
            }
        }
        compose.runOnIdle { first.requestFocus() }
        compose.holdOk()
        compose.press(Key.DirectionRight)
        compose.holdOk()
        assertEquals(listOf("show", "episode"), marked)
        assertEquals(emptyList<String>(), clicks)
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("episode"), clicks)

        listOf(SHOW, EPISODE).forEach { compose.onNode(longClickNamed(it)).assertExists() }
    }

    /** A card read as one text ([ArtworkSurface]'s `readAs`) keeps its long click for accessibility services. */
    @Test
    fun aCardReadAsOneTextKeepsItsLongClick() {
        compose.setTvContent {
            ArtworkSurface(onClick = {}, readAs = "Pilote", acts = true, onLongClick = { longClicks++ }, onLongClickLabel = EPISODE) {}
        }
        compose.onNodeWithContentDescription("Pilote").assert(longClickNamed(EPISODE)).performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(1, longClicks)
    }

    private companion object {
        const val SEASON = "Marquer la saison comme vue"
        const val SHOW = "Marquer la série comme vue"
        const val EPISODE = "Marquer l’épisode comme vu"
    }
}
