package app.houselights.ui.design

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.design.components.HlTwoPane
import app.houselights.ui.design.components.MediaRow
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.Showcase
import app.houselights.ui.design.components.ShowcaseRow
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.drawWindow
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
import kotlin.math.roundToInt

/** Focus frames and glows without growing: its frame stays inside the room its container keeps for it, its glow past it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class FocusReachTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theFrameStandsOffWhatItFrames() {
        // The gap, then the stroke, whatever the size of what it frames.
        assertEquals((HlFocus.frameGap + HlFocus.frame).value, HlFocus.reach.value, 0f)
        assertTrue(HlFocus.frame > HlSize.focusBorder)
    }

    @Test
    fun aRowAndAWallHoldTheFrame() {
        // MediaRow keeps HlFocus.room above and below its cards, a wall HlFocus.wallRoom above its first line.
        assertTrue(HlFocus.reach <= HlFocus.room)
        assertTrue(HlFocus.reach <= HlFocus.wallRoom)
        assertEquals(0f, HlFocus.room.value % 4f, 0f)
    }

    /** Past its room, a row draws a focused card's glow as the card alone draws it, out to its last lit ring. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aRowDrawsTheGlowWhole() {
        var inRow by mutableStateOf(true)
        show { card -> if (inRow) MediaRow(header = null) { item { card() } } else alone(card) }
        val row = glowAbove()
        compose.runOnIdle { inRow = false }
        val free = glowAbove()
        // The glow fades to clear over its last ring: the ring before it is the last to light.
        val lastLit = HlFocus.glow * (HlScrim.GLOW_STEPS - 2) / HlScrim.GLOW_STEPS + HALF_PX
        assertTrue("the glow lights to its last ring: $free", free[lastLit.px()] > 0)
        val past = HlFocus.room.px()..HlFocus.glow.px() + 2
        assertEquals("a row cuts no glow past its room", past.map { free[it] }, past.map { row[it] })
    }

    /** Where a wall's grid cuts its first line's glow, [HlFocus.wallRoom] above it, the glow has faded. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aWallCutsTheGlowOnceFaded() {
        show { card -> alone(card) }
        val glow = glowAbove()
        // The last line of pixels the grid draws.
        val cut = glow[HlFocus.wallRoom.px() - 1]
        assertTrue("faded to $cut of 255 at the grid's edge: $glow", cut <= FADED)
        assertTrue("the glow lights the wall's room: $glow", glow[HlFocus.room.px()] > FADED)
    }

    @Test
    fun aFrameStaysNearerItsCardThanItsNeighbour() {
        // Cards of a row are HlSpace.s apart at the closest (people), a wall's posters its gutter.
        listOf(HlSpace.s, HlSpace.m, HlLayout.wallGutter).forEach { apart ->
            assertTrue("cards $apart apart", HlFocus.reach * 2 <= apart)
        }
    }

    @Test
    fun sevenPostersFillAWall() {
        val across = HlSize.wallPoster * 7 + HlLayout.wallGutter * 6
        assertEquals(HlLayout.CANVAS_WIDTH - 2 * HlSpace.safeH.value, across.value, 0.01f)
    }

    /** A pane's showcases run to the screen's end, the last one brought in with its frame inside the safe area. */
    @Test
    fun aShowcaseRowKeepsItsLastFrameInsideTheSafeArea() {
        compose.setTvContent {
            HlTwoPane(side = {}) {
                ShowcaseRow {
                    items(SHOWCASES) { i ->
                        Showcase("Dossier $i", "NFS", emptyList(), onClick = {}, modifier = Modifier.testTag("showcase-$i"))
                    }
                }
            }
        }
        compose.onNodeWithTag("showcase-0").requestFocus()
        compose.press(Key.DirectionRight, times = SHOWCASES - 1)
        val last = compose.onNodeWithTag("showcase-${SHOWCASES - 1}").assertIsFocused()
        val box = last.getUnclippedBoundsInRoot()
        // Half a pixel short of it, where a test pixel rounds the frame's reach.
        assertTrue("$box", box.right + HlFocus.reach <= HlLayout.CANVAS_WIDTH.dp - HlSpace.safeH + HALF_PX)
    }

    @Test
    fun focusStaysClearOfTheFades() {
        // A list keeps focus HlFocus.edge from its ends: past the fade of what scrolls.
        assertTrue(HlSpace.fadeEdge <= HlFocus.edge)
    }

    private val focus = FocusRequester()

    /** A poster without a picture, glowing white in the light under the screen, [ABOVE] the window's top. */
    private fun show(holder: @Composable (card: @Composable () -> Unit) -> Unit) =
        compose.setTvContent {
            CompositionLocalProvider(LocalAmbient provides WhiteLight) {
                Box(Modifier.fillMaxSize().background(Color.Black).padding(top = ABOVE)) {
                    holder {
                        PosterCard(
                            MediaItem("1", MediaKind.MOVIE, "Film"),
                            onClick = {},
                            modifier = Modifier.focusRequester(focus).testTag(CARD),
                        )
                    }
                }
            }
        }

    /** The card alone, where a row would place it. */
    @Composable
    private fun alone(card: @Composable () -> Unit) =
        Box(Modifier.padding(start = HlSpace.safeH, top = HlFocus.room)) { card() }

    /** With focus on the card, its glow's level (0 to 255) at each pixel above its top, 0 at its edge. */
    private fun glowAbove(): List<Int> {
        compose.runOnIdle { focus.requestFocus() }
        compose.waitForIdle()
        val card = compose.onNodeWithTag(CARD).fetchSemanticsNode()
        val window = compose.drawWindow()
        val x = (card.positionInWindow.x + card.size.width / 2).roundToInt()
        val top = card.positionInWindow.y.roundToInt()
        return (0..ABOVE.px()).map { d -> android.graphics.Color.red(window.getPixel(x, top - 1 - d)) }
    }

    /** Whole pixels on the TV canvas, one per dp. */
    private fun Dp.px(): Int = value.roundToInt()

    /** The light of a white picture under the screen, so a card without one glows white. */
    private object WhiteLight : AmbientLight {
        override fun deep() = HouselightsColors.ambientDeep
        override fun plateAlpha() = HlAlpha.PLATE
        override fun glow() = Color.White
    }

    private companion object {
        const val CARD = "card"
        const val SHOWCASES = 3
        val ABOVE = 60.dp
        val HALF_PX = 0.5.dp

        /** About 1 % of a white glow: no edge on the wall's field. */
        const val FADED = 4
    }
}
