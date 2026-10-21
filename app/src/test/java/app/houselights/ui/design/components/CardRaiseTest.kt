package app.houselights.ui.design.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.Person
import app.houselights.ui.design.HlSize
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Focus: the focused card is drawn above its neighbours, its whole column when its picture is not its
 * root. Siblings are touched in the order they are drawn, topmost first, so a touch where two cards overlap tells it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class CardRaiseTest {
    @get:Rule
    val compose = createComposeRule()

    /** Cards "A" then "B", B [overlap] further: which one a touch where they overlap reaches, before and after A takes focus. */
    private fun reached(overlap: Dp, card: @Composable (name: String, modifier: Modifier) -> Unit): Pair<String?, String?> {
        var touched: String? = null
        val first = FocusRequester()
        compose.setTvContent {
            Box {
                listOf("A", "B").forEachIndexed { i, name ->
                    val focus = if (i == 0) Modifier.focusRequester(first) else Modifier
                    val touch = Modifier.pointerInput(Unit) { detectTapGestures { touched = name } }
                    card(name, Modifier.offset(x = overlap * i).then(focus).testTag(name).then(touch))
                }
            }
        }
        fun touch(): String? {
            touched = null
            compose.onNodeWithTag("B", useUnmergedTree = true).performTouchInput { click(Offset(width / 4f, height / 4f)) }
            return touched
        }
        val atRest = touch()
        compose.runOnIdle { first.requestFocus() }
        compose.waitForIdle()
        return atRest to touch()
    }

    @Test
    fun aFocusedPersonIsOnTopOfTheNext() {
        val reached = reached(HlSize.personColumn / 2) { name, modifier -> PersonCard(Person(name, "Rôle", null), modifier) }
        assertEquals("drawn later, B is on top at rest", "B", reached.first)
        assertEquals("with focus, A is", "A", reached.second)
    }

    @Test
    fun aFocusedStillIsOnTopOfTheNext() {
        val reached = reached(HlSize.landscape / 2) { name, modifier ->
            LandscapeCard(MediaItem(name, MediaKind.EPISODE, name), onClick = {}, modifier = modifier, title = name)
        }
        assertEquals("drawn later, B is on top at rest", "B", reached.first)
        assertEquals("with focus, A is", "A", reached.second)
    }
}
