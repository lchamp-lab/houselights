package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Components: the remote cannot move an [HlTextField]'s cursor, so typing starts after its text, whoever
 * wrote it, where the keyboard's erase key reaches all of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class TextFieldCaretTest {
    @get:Rule
    val compose = createComposeRule()

    private var value by mutableStateOf("media")
    private val share = FocusRequester()

    /** The field "Share" holding [value], its owner keeping [keep] of a change; [below], a second field under it. */
    private fun show(keep: (String) -> String = { it }, below: Boolean = false) {
        compose.setTvContent {
            Column {
                HlTextField("Share", value, { value = keep(it) }, Modifier.focusRequester(share))
                if (below) HlTextField("Folder", "", {})
            }
        }
        compose.waitForIdle()
    }

    /** Focuses "Share", then OK starts typing. */
    private fun startTyping() {
        compose.runOnIdle { share.requestFocus() }
        compose.press(Key.DirectionCenter)
    }

    /** The text of "Share", where the keyboard types. */
    private fun text(): SemanticsNodeInteraction = compose.onAllNodes(hasSetTextAction()).onFirst()

    private fun SemanticsNodeInteraction.assertCaretAt(index: Int) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(index)))

    @Test
    fun typingStartsAfterItsText() {
        show()
        startTyping()
        text().assertCaretAt(5)

        text().performTextInput("2")
        assertEquals("media2", value)
    }

    @Test
    fun eraseEmptiesAFilledField() {
        show()
        startTyping()
        compose.press(Key.Backspace, 5)
        assertEquals("", value)
    }

    /** Like the last folder's share, read after the form shows. */
    @Test
    fun aValueArrivingLaterIsTypedAfter() {
        value = ""
        show()
        value = "media"
        compose.waitForIdle()

        startTyping()
        text().assertCaretAt(5)
    }

    @Test
    fun typingStartsAtTheEndAgain() {
        show(below = true)
        startTyping()
        compose.press(Key.DirectionLeft, 2)
        text().assertCaretAt(3)

        // Down leaves typing for the field below; up and OK start typing again.
        compose.press(Key.DirectionDown)
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionCenter)
        text().assertCaretAt(5)
    }

    /** A one-line field scrolls to its cursor, focused or not: at rest, typed or not, a value shows its start. */
    @Test
    fun aFieldAtRestShowsItsStart() {
        show(below = true)
        text().assertCaretAt(0)

        startTyping()
        text().performTextInput("2")
        compose.press(Key.DirectionDown)
        text().assertCaretAt(0)
    }

    /**
     * A letter, then the text as it was, from the keyboard before the field composes again: the owner gets both,
     * unless the second is exactly what was composed, cursor included, which Compose itself drops.
     */
    @Test
    fun anEditUndoneBeforeAFrameReachesTheOwner() {
        show()
        startTyping()
        // So the text as it was comes with its cursor elsewhere than composed.
        compose.press(Key.DirectionLeft, 2)
        val field = text().fetchSemanticsNode().config
        compose.runOnIdle {
            field[SemanticsActions.InsertTextAtCursor].action?.invoke(AnnotatedString("2"))
            field[SemanticsActions.SetText].action?.invoke(AnnotatedString("media"))
        }
        assertEquals("media", value)
    }

    /** Like Identify's year, which keeps four digits. */
    @Test
    fun aFilteredValueKeepsTheCursorAtTheEnd() {
        value = "2024"
        show(keep = { it.filter(Char::isDigit).take(4) })
        startTyping()

        text().performTextInput("5")
        assertEquals("2024", value)
        text().assertCaretAt(4)
    }
}
