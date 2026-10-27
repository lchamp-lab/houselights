package app.houselights.ui.design.components

import android.graphics.Bitmap
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.houselights.ui.design.ArtworkSample
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HouselightsColors
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.testing.TV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max

/**
 * Backdrop pictures fade in over `TINT`, one fade at a time, except one already in memory under the launch screen:
 * opaque at once. What shows holds whole under what comes, and goes out into the room when nothing comes; plates
 * take the pictures' dark as their layers composite.
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
        assertEquals("drawn in the frame that brings it", 1, framesToFirst)
    }

    @Test
    fun anyOtherPictureFadesIn() {
        val (first, later) = alphas(from = null, to = "backdrop", watched = "backdrop")
        assertEquals("it comes from nothing", 0f, first)
        assertTrue("$later a few frames in: it is still coming up", later > 0f && later < 1f)
    }

    @Test
    fun whatShowsHoldsUnderWhatComes() {
        val (first, later) = alphas(from = "left", to = "detail", watched = "left")
        assertEquals(1f, first)
        assertEquals(1f, later)
    }

    @Test
    fun whatShowsGoesOutIntoTheRoom() {
        val (_, later) = alphas(from = "left", to = null, watched = "left")
        assertTrue("$later: going out", later < 1f)
    }

    @Test
    fun picturesComingEvery350MsFadeOneAtATime() {
        val drawn = Drawn()
        val show = fade("A", drawn)
        var most = 0
        var frames = 0
        listOf("B", "C", "D").forEach { picture ->
            show(picture)
            repeat(EVERY_350_MS) {
                compose.mainClock.advanceTimeByFrame()
                val now = drawn.now()
                most = max(most, now.size)
                // A picture is posed: whatever comes, the screen is never less than covered.
                assertTrue("frame ${frames++}: $now", covered(now) >= 1f)
            }
        }
        repeat(TINT_FRAMES * 3) {
            compose.mainClock.advanceTimeByFrame()
            most = max(most, drawn.now().size)
            assertTrue(covered(drawn.now()) >= 1f)
        }
        assertEquals("never more than two layers", 2, most)
        assertEquals("the last one alone, whole", listOf("D" to 1f), drawn.now())
    }

    @Test
    fun theLastToComeDuringAFadeTakesThePlaceOfAnyWaiting() {
        val drawn = Drawn()
        val show = fade("A", drawn)
        val ever = mutableSetOf<String?>()
        listOf("B", "C", "D").forEach { picture ->
            show(picture)
            repeat(FRAMES) {
                compose.mainClock.advanceTimeByFrame()
                ever += drawn.layers.keys
            }
        }
        repeat(TINT_FRAMES * 3) {
            compose.mainClock.advanceTimeByFrame()
            ever += drawn.layers.keys
        }
        assertEquals("C waited, then gave way to D", setOf("A", "B", "D"), ever)
        assertEquals(listOf("D" to 1f), drawn.now())
    }

    @Test
    fun nothingComingDuringAFadeTakesItsTurnAndEverythingGoesOutTogether() {
        val drawn = Drawn()
        val show = fade("A", drawn)
        show("B")
        repeat(TINT_FRAMES / 2) { compose.mainClock.advanceTimeByFrame() }
        show(null)
        var before = shares(drawn.now())
        repeat(TINT_FRAMES * 3) {
            compose.mainClock.advanceTimeByFrame()
            val now = shares(drawn.now())
            assertTrue("$before, then $now: A only goes out", (now["A"] ?: 0f) <= (before["A"] ?: 0f))
            (now.keys + before.keys).forEach { picture ->
                assertEquals("$picture from $before to $now: no jump", before[picture] ?: 0f, now[picture] ?: 0f, STEP)
            }
            assertTrue("$before, then $now: the pictures only go out", now.values.sum() <= before.values.sum() + 1e-6f)
            before = now
        }
        assertTrue("everything went out together: ${drawn.now()}", drawn.layers.isEmpty())
    }

    @Test
    fun platesFollowTheFadeIntoTheRoom() {
        val state = BackdropState()
        var target by mutableStateOf<Shot?>(shot("A", grey = 0.2f, Color(0xFF1C0707)))
        compose.mainClock.autoAdvance = false
        compose.setContent { HouselightsTheme { BackdropShots(target, Modifier, recede = null, lights = state) } }
        repeat(TINT_FRAMES) { compose.mainClock.advanceTimeByFrame() }
        val dark = state.plateAlpha()
        target = shot("B", grey = 1f, Color(0xFF07071C))
        Snapshot.sendApplyNotifications()
        repeat(TINT_FRAMES / 2) { compose.mainClock.advanceTimeByFrame() }
        target = null
        Snapshot.sendApplyNotifications()
        var deep = state.deep()
        var plate = state.plateAlpha()
        var light = plate
        repeat(TINT_FRAMES * 3) {
            compose.mainClock.advanceTimeByFrame()
            light = max(light, state.plateAlpha())
            assertEquals("plates move with the pictures", plate, state.plateAlpha(), (HlAlpha.PLATE_MAX - HlAlpha.PLATE) * STEP)
            assertEquals(deep.red, state.deep().red, STEP * 0x1C / 255)
            assertEquals(deep.blue, state.deep().blue, STEP * 0x1C / 255)
            deep = state.deep()
            plate = state.plateAlpha()
        }
        assertTrue("a light picture raised them: from $dark to $light", light > dark + 0.05f)
        assertEquals(HouselightsColors.ambientDeep, state.deep())
        assertEquals(HlAlpha.PLATE, state.plateAlpha(), 0f)
        assertEquals(0f, state.glow().alpha, 0f)
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

    /** Frames [alphas] took to first draw what it watched. */
    private var framesToFirst = 0

    /** The layers a [FadeBetween] composes, bottom to top, each with what its alpha reads now. */
    private class Drawn {
        val layers = LinkedHashMap<String?, () -> Float>()

        fun now(): List<Pair<String?, Float>> = layers.map { (picture, alpha) -> picture to alpha() }
    }

    /** Fades between pictures (null: the room), from [first], into [drawn]; returns how to show the next one. */
    private fun fade(first: String?, drawn: Drawn): (String?) -> Unit {
        compose.mainClock.autoAdvance = false
        var target by mutableStateOf(first)
        compose.setContent {
            FadeBetween(target, Modifier, empty = { it == null }) { picture, alpha ->
                SideEffect { drawn.layers[picture] = alpha }
                DisposableEffect(picture) { onDispose { drawn.layers.remove(picture) } }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        return {
            target = it
            Snapshot.sendApplyNotifications()
        }
    }

    /** The layers' alphas summed: 1 or more where something whole lies under what comes. */
    private fun covered(layers: List<Pair<String?, Float>>): Float = layers.sumOf { (_, alpha) -> alpha.toDouble() }.toFloat()

    /** How much of each layer shows through the ones above it. */
    private fun shares(layers: List<Pair<String?, Float>>): Map<String?, Float> {
        var above = 1f
        return layers.asReversed().associate { (picture, alpha) -> (picture to alpha * above).also { above *= 1f - alpha } }
    }

    /** A backdrop of an even [grey] picture whose dark is [deep]. */
    private fun shot(url: String, grey: Float, deep: Color): Shot {
        val picture = Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888).asImageBitmap()
        val sample = ArtworkSample(SAMPLE_W, SAMPLE_H, FloatArray(SAMPLE_W * SAMPLE_H) { grey })
        return Shot(PreparedBackdrop(picture, sample, ImageLight(deep, Color.Red)), BackdropStyle.Hero, url)
    }

    /** The alpha [watched] is drawn at once the backdrop goes [from] one picture [to] another: first, then a few frames later. */
    private fun alphas(from: String?, to: String?, watched: String, atOnce: (String?) -> Boolean = { false }): Pair<Float, Float> {
        compose.mainClock.autoAdvance = false
        var target by mutableStateOf(from)
        var alpha = NOT_DRAWN
        compose.setContent {
            FadeBetween(target, Modifier, atOnce, empty = { it == null }) { picture, drawnAt ->
                if (picture == watched) {
                    val now = drawnAt()
                    SideEffect { alpha = now }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        target = to
        alpha = NOT_DRAWN
        Snapshot.sendApplyNotifications()
        // Its first frame: a target is taken up within a frame or two.
        framesToFirst = 0
        repeat(2) {
            if (alpha == NOT_DRAWN) {
                compose.mainClock.advanceTimeByFrame()
                framesToFirst++
            }
        }
        val first = alpha
        repeat(FRAMES) { compose.mainClock.advanceTimeByFrame() }
        return first to alpha
    }

    private companion object {
        /** A few frames of `TINT`: 100 ms of 480. */
        const val FRAMES = 6
        const val FRAME_MS = 16
        const val TINT_FRAMES = HlMotion.TINT / FRAME_MS
        /** 352 ms: a title settled every 350 ms or so, as an arrow pressed a little over twice a second. */
        const val EVERY_350_MS = (350 + FRAME_MS - 1) / FRAME_MS
        /** The most one layer's share moves in a frame of a linear `TINT`, and a frame's slack. */
        const val STEP = 2f * FRAME_MS / HlMotion.TINT
        const val NOT_DRAWN = -1f
        const val SAMPLE_W = 16
        const val SAMPLE_H = 9
    }
}
