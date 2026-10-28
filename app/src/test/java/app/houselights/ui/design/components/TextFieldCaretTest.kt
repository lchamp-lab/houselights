package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.TestKeyboard
import app.houselights.ui.testing.holdOnRemote
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressOnPluggedKeyboard
import app.houselights.ui.testing.pressOnRemote
import app.houselights.ui.testing.pressOnShownKeyboard
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Components: typing in an [HlTextField] starts after its text, whoever wrote it, where the keyboard's erase
 * key reaches all of it; then the remote's Left and Right move the cursor, leaving only from the text's start or end.
 * Keys come from the remote's D-pad ([pressOnRemote]), whose arrows Compose's own text field turns into focus moves,
 * and a keyboard's from the keyboard ([pressOnShownKeyboard], [pressOnPluggedKeyboard]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class TextFieldCaretTest {
    @get:Rule
    val compose = createComposeRule()

    private var value by mutableStateOf("media")
    private val share = FocusRequester()
    private val keyboard = TestKeyboard()

    /**
     * The field "Share" holding [value], its owner keeping [keep] of a change; [below], a second field under it;
     * [beside], one on each side of it, "Server" then "Folder", as Identify's title and year stand side by side.
     */
    private fun show(keep: (String) -> String = { it }, below: Boolean = false, beside: Boolean = false) {
        compose.setTvContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                Column {
                    Row {
                        if (beside) HlTextField("Server", "nas", {}, Modifier.weight(1f))
                        HlTextField("Share", value, { value = keep(it) }, Modifier.weight(1f).focusRequester(share))
                        if (beside) HlTextField("Folder", "films", {}, Modifier.weight(1f))
                    }
                    if (below) HlTextField("Folder", "", {})
                }
            }
        }
        compose.waitForIdle()
    }

    /** Focuses "Share", then OK starts typing. */
    private fun startTyping() {
        compose.runOnIdle { share.requestFocus() }
        compose.pressOnRemote(Key.DirectionCenter)
    }

    /** The text of "Share", where the keyboard types. */
    private fun text(): SemanticsNodeInteraction = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasContentDescription("Share")))

    private fun SemanticsNodeInteraction.assertCaretAt(index: Int) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(index)))

    /** "Share" still typing, its cursor at [index]. */
    private fun assertTypingAt(index: Int) {
        text().assertIsFocused()
        text().assertCaretAt(index)
    }

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
        // The keyboard's erase key, not the remote's.
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
        compose.pressOnRemote(Key.DirectionLeft, 2)
        text().assertCaretAt(3)

        // Down leaves typing for the field below; up and OK start typing again.
        compose.pressOnRemote(Key.DirectionDown)
        compose.pressOnRemote(Key.DirectionUp)
        compose.pressOnRemote(Key.DirectionCenter)
        text().assertCaretAt(5)
    }

    /** A one-line field scrolls to its cursor, focused or not: at rest, typed or not, a value shows its start. */
    @Test
    fun aFieldAtRestShowsItsStart() {
        show(below = true)
        text().assertCaretAt(0)

        startTyping()
        text().performTextInput("2")
        compose.pressOnRemote(Key.DirectionDown)
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
        compose.pressOnRemote(Key.DirectionLeft, 2)
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

    /** A field on each side, which Compose's own text field would leave for at the first arrow. */
    @Test
    fun leftAndRightMoveTheCursorWhileTyping() {
        show(beside = true)
        startTyping()

        compose.pressOnRemote(Key.DirectionLeft)
        assertTypingAt(4)
        // Held, it repeats.
        compose.holdOnRemote(Key.DirectionLeft, repeats = 2)
        assertTypingAt(1)
        compose.pressOnRemote(Key.DirectionRight)
        assertTypingAt(2)
    }

    @Test
    fun leftAtTheStartLeavesForTheFieldBeside() {
        show(beside = true)
        startTyping()

        // Held past the start, it stops there.
        compose.holdOnRemote(Key.DirectionLeft, repeats = 7)
        assertTypingAt(0)
        compose.pressOnRemote(Key.DirectionLeft)
        compose.onNodeWithContentDescription("Server").assertIsFocused()
        text().assertIsNotFocused()
    }

    /** At the end, Right ends typing on the field's cross, at its end (#78); Right from the cross goes on to the field beside. */
    @Test
    fun rightAtTheEndLeavesForItsCrossThenTheFieldBeside() {
        show(beside = true)
        startTyping()
        compose.pressOnRemote(Key.DirectionLeft, 2)

        // Held past the end, it stops there.
        compose.holdOnRemote(Key.DirectionRight, repeats = 4)
        assertTypingAt(5)
        compose.pressOnRemote(Key.DirectionRight)
        compose.onNodeWithContentDescription("Clear Share").assertIsFocused()
        text().assertIsNotFocused()
        compose.pressOnRemote(Key.DirectionRight)
        compose.onNodeWithContentDescription("Folder").assertIsFocused()
    }

    /** On the player the keyboard shown takes the arrows: Back hid it, and OK shows it again where the cursor is. */
    @Test
    fun okAfterMovingTypesAtTheCursor() {
        show(beside = true)
        startTyping()
        assertTrue("OK shows the keyboard", keyboard.shown)
        // Back, which the keyboard takes.
        keyboard.shown = false
        compose.pressOnRemote(Key.DirectionLeft, 2)
        assertFalse("The arrows leave the keyboard hidden", keyboard.shown)

        compose.pressOnRemote(Key.DirectionCenter)
        assertTrue("OK shows the keyboard again", keyboard.shown)
        assertTypingAt(3)
        text().performTextInput("2")
        assertEquals("med2ia", value)
        text().assertCaretAt(4)
    }

    /** The keyboard shown may send arrows of its own: they move the cursor and stop at the text's edges, as anywhere. */
    @Test
    fun theKeyboardsOwnArrowsNeverLeave() {
        show(beside = true)
        startTyping()

        compose.pressOnShownKeyboard(Key.DirectionRight)
        assertTypingAt(5)
        compose.pressOnShownKeyboard(Key.DirectionLeft, 6)
        assertTypingAt(0)
    }

    /** A keyboard plugged into the player keeps its keys: Shift and an arrow select. */
    @Test
    fun aPluggedKeyboardSelectsWithShift() {
        show(beside = true)
        startTyping()

        compose.pressOnPluggedKeyboard(Key.DirectionLeft, shift = true)
        text().assertIsFocused()
        text().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(5, 4)))
    }
}
