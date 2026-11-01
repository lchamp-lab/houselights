package app.houselights.ui.design.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.Person
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.drawWindow
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
import kotlin.math.roundToInt

/**
 * DESIGN.md, Focus: the focused card is drawn above its neighbours, its whole column when its picture is not its
 * root. Siblings are touched in the order they are drawn, topmost first, so a touch where two cards overlap tells it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class CardRaiseTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

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

    /** A group's frame is drawn over its content: over a card drawn later than the one it frames, which it overlaps. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theGroupsFrameLiesAboveTheNextCard() {
        val first = FocusRequester()
        var white = Color.Unspecified
        compose.setTvContent {
            white = Hl.colors.focus
            val frame = rememberGroupFrame()
            CompositionLocalProvider(LocalGroupFrame provides frame) {
                Box(Modifier.padding(HlSpace.xl).then(frame.drawn)) {
                    listOf("A", "B").forEachIndexed { i, name ->
                        val focus = if (i == 0) Modifier.focusRequester(first) else Modifier
                        val card = Modifier.offset(x = HlSize.poster / 2 * i).then(focus).testTag(name)
                        ArtworkSurface(onClick = {}, modifier = card.width(HlSize.poster).aspectRatio(2f / 3f)) {
                            Box(Modifier.fillMaxSize().background(Picture))
                        }
                    }
                }
            }
        }
        compose.runOnIdle { first.requestFocus() }
        compose.waitForIdle()
        val a = compose.onNodeWithTag("A").fetchSemanticsNode()
        // A's frame's right side, on its middle line, where B's picture lies.
        val x = a.positionInWindow.x + a.size.width + HlFocus.frameGap.value + HlFocus.frame.value / 2
        val y = a.positionInWindow.y + a.size.height / 2
        val pixel = compose.drawWindow().getPixel(x.roundToInt(), y.roundToInt())
        val focus = white.toArgb()
        val channels = listOf(16, 8, 0).map { shift -> abs((pixel shr shift and 0xFF) - (focus shr shift and 0xFF)) }
        assertTrue("focus white over B: #${Integer.toHexString(pixel)}", channels.all { it <= TOLERANCE })
    }

    private companion object {
        /** A picture far from focus's white. */
        val Picture = Color(0xFF204060)

        /** How far a pixel may be from focus's white, per channel. */
        const val TOLERANCE = 16
    }
}
