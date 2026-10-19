package app.houselights.ui.design.components

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
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
 * Backdrop pictures fade in over `SLOW`, except one already in memory under the launch screen: opaque at once.
 * A picture handed on holds under the next one, and goes out when nothing comes.
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
        /** A few frames of `SLOW`: 100 ms of 600. */
        const val FRAMES = 6
    }
}
