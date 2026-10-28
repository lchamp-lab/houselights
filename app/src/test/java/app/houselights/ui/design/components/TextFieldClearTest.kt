package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.TEXT_SCALE_MAX
import app.houselights.ui.design.TwoPaneDefaults
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.TestKeyboard
import app.houselights.ui.testing.pressOnRemote
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DESIGN.md, Components: a filled [HlTextField] has a cross at its end while it or its cross has focus (#78), a form at
 * rest staying bare. Right at rest reaches it, Left comes back to the field, Right goes on, Up and Down leave as from
 * the field, and the field beside lands on the field, never on its cross; OK on it empties the field, focus back on the
 * field at rest, the keyboard hidden; a screen reader reads it as a button named for the field. Focused at rest,
 * « OK saisir » gives way where the text needs the room: a path in a half-width field reads whole up to the cross, at
 * every text size. Keys come from the remote's D-pad ([pressOnRemote]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
// Real fonts, so the text, « OK saisir » and the cross measure as on the player.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextFieldClearTest {
    @get:Rule
    val compose = createComposeRule()

    private var share by mutableStateOf("media")

    /** What the owner of "Share" was told, in order. */
    private val told = mutableListOf<String>()
    private val shareFocus = FocusRequester()
    private val folderFocus = FocusRequester()
    private val keyboard = TestKeyboard()

    private fun show(textStep: Float = 1f, content: @Composable () -> Unit) {
        compose.setTvContent(textStep) { CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard, content = content) }
        compose.waitForIdle()
    }

    /** "Server", "Share" then "Folder" side by side, as Identify's title and year stand, each holding text. */
    private fun showThree() = show {
        Row {
            HlTextField("Server", "nas", {}, Modifier.weight(1f))
            HlTextField(
                "Share",
                share,
                {
                    told += it
                    share = it
                },
                Modifier.weight(1f).focusRequester(shareFocus),
                placeholder = "e.g. media",
            )
            HlTextField("Folder", "films", {}, Modifier.weight(1f).focusRequester(folderFocus))
        }
    }

    private fun cross(field: String) = compose.onNodeWithContentDescription("Clear $field")

    private fun field(name: String) = compose.onNodeWithContentDescription(name)

    /** The text of [field], where the keyboard types. */
    private fun text(field: String): SemanticsNodeInteraction =
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasContentDescription(field)))

    private fun focus(requester: FocusRequester) {
        compose.runOnIdle { requester.requestFocus() }
        compose.waitForIdle()
    }

    @Test
    fun theCrossShowsWhileAFilledFieldOrItsCrossHasFocus() {
        showThree()
        listOf("Server", "Share", "Folder").forEach { cross(it).assertDoesNotExist() }

        focus(shareFocus)
        cross("Share").assertExists()
        cross("Server").assertDoesNotExist()
        compose.pressOnRemote(Key.DirectionRight)
        cross("Share").assertIsFocused()
        compose.pressOnRemote(Key.DirectionRight)
        cross("Share").assertDoesNotExist()
        cross("Folder").assertExists()

        share = ""
        focus(shareFocus)
        cross("Share").assertDoesNotExist()
    }

    @Test
    fun rightAtRestReachesTheCrossLeftComesBackRightGoesOn() {
        showThree()
        focus(shareFocus)

        compose.pressOnRemote(Key.DirectionRight)
        cross("Share").assertIsFocused()
        compose.pressOnRemote(Key.DirectionLeft)
        field("Share").assertIsFocused()
        text("Share").assertIsNotFocused()
        assertFalse("at rest, the keyboard hidden", keyboard.shown)

        compose.pressOnRemote(Key.DirectionRight, times = 2)
        field("Folder").assertIsFocused()
    }

    /** Shown only with focus, the cross is never where Left from the field beside lands. */
    @Test
    fun leftFromTheFieldBesideLandsOnTheFieldNotItsCross() {
        showThree()
        focus(folderFocus)

        compose.pressOnRemote(Key.DirectionLeft)
        field("Share").assertIsFocused()
        cross("Share").assertIsNotFocused()
    }

    @Test
    fun okOnTheCrossEmptiesTheFieldAndBringsFocusBackAtRest() {
        showThree()
        focus(shareFocus)
        compose.pressOnRemote(Key.DirectionRight)

        compose.pressOnRemote(Key.DirectionCenter)
        assertEquals("told once, as typing tells it", listOf(""), told)
        field("Share").assertIsFocused()
        text("Share").assertIsNotFocused()
        assertFalse("the keyboard hidden", keyboard.shown)
        cross("Share").assertDoesNotExist()
        compose.onNode(hasText("e.g. media") and hasAnyAncestor(hasContentDescription("Share"))).assertExists()
        compose.onNode(hasText("type") and hasAnyAncestor(hasContentDescription("Share"))).assertExists()

        // OK opens the keyboard, as on any field.
        compose.pressOnRemote(Key.DirectionCenter)
        assertTrue(keyboard.shown)
        text("Share").assertIsFocused()
    }

    /** The keyboard's Next goes on to the next field, never to the cross that follows the field in focus order. */
    @Test
    fun theKeyboardsNextGoesToTheNextFieldNotTheCross() {
        show {
            Column {
                HlTextField("Share", share, { share = it }, Modifier.focusRequester(shareFocus))
                HlTextField("Folder", "films", {}, Modifier.focusRequester(folderFocus))
            }
        }
        focus(shareFocus)
        compose.pressOnRemote(Key.DirectionCenter)
        text("Share").assertIsFocused()

        text("Share").performImeAction()
        field("Folder").assertIsFocused()
    }

    /**
     * Up and Down from the cross leave as from the field whose frame it lies in: "Share" is two thirds wide, its cross
     * over "Right" below, where the field's middle is over "Left".
     */
    @Test
    fun upAndDownFromTheCrossLeaveAsFromTheField() {
        show {
            Column {
                Row {
                    HlTextField("Share", share, { share = it }, Modifier.weight(2f).focusRequester(shareFocus))
                    Spacer(Modifier.weight(1f))
                }
                Row {
                    HlTextField("Left", "a", {}, Modifier.weight(1f))
                    HlTextField("Right", "b", {}, Modifier.weight(1f))
                }
            }
        }
        focus(shareFocus)
        compose.pressOnRemote(Key.DirectionDown)
        field("Left").assertIsFocused()

        focus(shareFocus)
        compose.pressOnRemote(Key.DirectionRight)
        cross("Share").assertIsFocused()
        compose.pressOnRemote(Key.DirectionDown)
        field("Left").assertIsFocused()

        // Nothing above: focus stays on the cross.
        focus(shareFocus)
        compose.pressOnRemote(Key.DirectionRight)
        compose.pressOnRemote(Key.DirectionUp)
        cross("Share").assertIsFocused()
    }

    /** « Effacer Année »: a button, named for the field without its « (facultatif) ». */
    @Test
    fun theScreenReaderReadsTheCrossAsAButtonNamedForTheField() {
        show { HlTextField("Year", "2025", {}, Modifier.focusRequester(shareFocus), optional = true) }
        focus(shareFocus)

        field("Year (optional)").assertIsFocused()
        compose.onNode(hasContentDescription("Clear Year") and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        cross("Year (optional)").assertDoesNotExist()
    }

    /** A password has its cross for what was typed; emptied, it says again that the one saved stays. */
    @Test
    fun aPasswordEmptiedSaysItsSavedOneStays() {
        var password by mutableStateOf("secret")
        show {
            HlTextField(
                "Password",
                password,
                { password = it },
                Modifier.focusRequester(shareFocus),
                placeholder = "Unchanged",
                password = true,
            )
        }
        focus(shareFocus)
        compose.pressOnRemote(Key.DirectionRight)
        compose.pressOnRemote(Key.DirectionCenter)

        assertEquals("", password)
        compose.onNode(hasText("Unchanged") and hasAnyAncestor(hasContentDescription("Password"))).assertExists()
    }

    @Test
    fun aPathInAHalfWidthFieldReadsWholeUpToTheCross() = aPathReadsWhole(textStep = 1f)

    @Test
    fun aPathInAHalfWidthFieldReadsWholeUpToTheCrossAtTheLargestText() = aPathReadsWhole(textStep = TEXT_SCALE_MAX)

    /**
     * « Chemin de montage » and « Sous-dossier » side by side in a form's width, as « Modifier le dossier » holds them:
     * focused at rest, the path reads whole up to its cross, « OK saisir » giving way; « Films » keeps it, the text
     * whole before it.
     */
    private fun aPathReadsWhole(textStep: Float) {
        show(textStep) {
            Row(Modifier.width(FORM), horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                HlTextField("Mount path", "/volume1/video", {}, Modifier.weight(1f).focusRequester(shareFocus))
                HlTextField("Subfolder", "Films", {}, Modifier.weight(1f).focusRequester(folderFocus), optional = true)
            }
        }
        focus(shareFocus)
        val path = textEnd("Mount path")
        val cross = cross("Mount path").getUnclippedBoundsInRoot()
        assertTrue("the path whole, ending at $path, before its cross at ${cross.left}", path <= cross.left.value)
        compose.onNode(hasText("type") and hasAnyAncestor(hasContentDescription("Mount path"))).assertDoesNotExist()

        focus(folderFocus)
        val films = textEnd("Subfolder (optional)")
        val ok = compose.onNode(hasText("OK") and hasAnyAncestor(hasContentDescription("Subfolder (optional)")))
            .getUnclippedBoundsInRoot()
        assertTrue("« Films » whole, ending at $films, before « OK saisir » at ${ok.left}", films <= ok.left.value)
        assertTrue("« OK saisir » before the cross", ok.right <= cross("Subfolder").getUnclippedBoundsInRoot().left)
    }

    /** Where [field]'s text ends, as laid out from its start (dp, a test pixel). */
    private fun textEnd(field: String): Float {
        val node = text(field)
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return node.getUnclippedBoundsInRoot().left.value + layouts.first().getLineRight(0)
    }

    private companion object {
        /** A form's width beside its column, as « Modifier le dossier » lays it out (FormLayoutTest). */
        val FORM = 960.dp - HlSpace.safeH * 2 - TwoPaneDefaults.sideWide - HlSpace.xxl
    }
}
