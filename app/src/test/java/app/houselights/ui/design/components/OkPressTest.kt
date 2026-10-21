package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlShapes
import app.houselights.ui.testing.TV
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
        compose.onRoot().performKeyInput {
            keyDown(Key.DirectionCenter)
            // Held past the remote's repeat delay.
            advanceEventTime(HELD_MS)
            keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        assertEquals(1, longClicks)
        assertEquals(emptyList<String>(), clicks)

        compose.press(Key.DirectionCenter)
        assertEquals(listOf("A"), clicks)
    }

    private companion object {
        const val HELD_MS = 800L
    }
}
