package app.houselights.ui.design.components

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HouselightsColors
import app.houselights.ui.testing.TV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Backdrop pictures fade in over `TINT`, except one already in memory under the launch screen: opaque at once.
 * A leaving picture holds under the next one as it was drawn, and goes out when nothing comes; plates take the
 * pictures' dark as their layers composite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class BackdropFadeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun onlyAFirstPictureInMemoryUnderTheLaunchScreenShowsAtOnce() {
        assertTrue(showsAtOnce(first = true, inMemory = true, covered = true))
        assertFalse("a picture after the first", showsAtOnce(first = false, inMemory = true, covered = true))
        assertFalse("a picture loaded from the disk or the server", showsAtOnce(first = true, inMemory = false, covered = true))
        assertFalse(
            "nothing covers the screen: a warm start, a screen opened later",
            showsAtOnce(first = true, inMemory = true, covered = false),
        )
    }

    @Test
    fun aPictureThatShowsAtOnceIsWholeFromItsFirstFrame() {
        val (first, later) = alphas(from = null, to = "backdrop", watched = "backdrop", atOnce = { it != null })
        assertEquals(1f, first)
        assertEquals(1f, later)
    }

    @Test
    fun anyOtherPictureFadesIn() {
        val (first, later) = alphas(from = null, to = "backdrop", watched = "backdrop")
        assertEquals("it comes from nothing", 0f, first)
        assertTrue("$later a few frames in: it is still coming up", later > 0f && later < 1f)
    }

    @Test
    fun whatIsHandedOnHoldsUnderWhatComes() {
        val (first, later) = alphas(from = "left", to = "detail", watched = "left", holds = handedOn)
        assertEquals(1f, first)
        assertEquals(1f, later)
    }

    @Test
    fun whatIsHandedOnGoesOutForNothing() {
        val (_, later) = alphas(from = "left", to = null, watched = "left", holds = handedOn)
        assertTrue("$later: going out", later < 1f)
    }

    @Test
    fun aPictureInterruptedAsItCameHoldsWhereItWas() {
        compose.mainClock.autoAdvance = false
        var target by mutableStateOf("first")
        val drawn = mutableMapOf<String, Float>()
        compose.setContent {
            FadeBetween(target, Modifier, holds = { _, coming -> coming != null }) { picture, alpha ->
                val now = alpha()
                SideEffect { drawn[picture] = now }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        target = "second"
        Snapshot.sendApplyNotifications()
        repeat(FRAMES) { compose.mainClock.advanceTimeByFrame() }
        val halfway = drawn.getValue("second")
        assertTrue("$halfway", halfway > 0f && halfway < 1f)
        target = "third"
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        val held = drawn.getValue("second")
        repeat(FRAMES) { compose.mainClock.advanceTimeByFrame() }
        // What showed stays as it was under the third, a frame on at most: no dip, no jump.
        assertEquals(1f, drawn.getValue("first"))
        assertEquals(held, drawn.getValue("second"))
        assertEquals(halfway, held, 16f / HlMotion.TINT)
        assertTrue(drawn.getValue("third") > 0f && drawn.getValue("third") < 1f)
    }

    @Test
    fun platesTakeThePicturesDarkAsTheirLayersComposite() {
        val state = BackdropState()
        assertEquals(HouselightsColors.ambientDeep, state.deep())
        assertEquals(HlAlpha.PLATE, state.plateAlpha(), 0f)
        assertEquals(Color.Transparent, state.glow())
        var coming = 0.25f
        state.layers += LitLayer(ImageLight(Color(0xFF1C0707), Color.Red), 0.8f) { 1f }
        state.layers += LitLayer(ImageLight(Color(0xFF07071C), Color.Blue), 0.9f) { coming }
        // The blue dark a quarter in over the red one, as the screen blends them.
        val deep = state.deep()
        assertEquals((0x1C * 0.75f + 0x07 * 0.25f) / 255, deep.red, 1f / 255)
        assertEquals(0x07 / 255f, deep.green, 1f / 255)
        assertEquals((0x07 * 0.75f + 0x1C * 0.25f) / 255, deep.blue, 1f / 255)
        assertEquals(0.825f, state.plateAlpha(), 0.0001f)
        assertEquals(0.75f, state.glow().red, 0.01f)
        coming = 1f
        assertEquals(Color(0xFF07071C), state.deep())
    }

    /** The picture left by the previous screen holds under any other. */
    private val handedOn = { leaving: String?, coming: String? -> leaving == "left" && coming != null }

    /** The alpha [watched] is drawn at once the backdrop goes [from] one picture [to] another: first, then a few frames later. */
    private fun alphas(
        from: String?,
        to: String?,
        watched: String,
        atOnce: (String?) -> Boolean = { false },
        holds: (String?, String?) -> Boolean = { _, _ -> false },
    ): Pair<Float, Float> {
        compose.mainClock.autoAdvance = false
        var target by mutableStateOf(from)
        var alpha = -1f
        compose.setContent {
            FadeBetween(target, Modifier, atOnce, holds) { picture, drawnAt ->
                if (picture == watched) {
                    val now = drawnAt()
                    SideEffect { alpha = now }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        target = to
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        val first = alpha
        repeat(FRAMES) { compose.mainClock.advanceTimeByFrame() }
        return first to alpha
    }

    private companion object {
        /** A few frames of `TINT`: 100 ms of 480. */
        const val FRAMES = 6
    }
}
