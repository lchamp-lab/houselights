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

/** Backdrop pictures fade in over `SLOW`, except one already in memory under the launch screen: opaque at once. */
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
        val (first, later) = alphaOfANewPicture(atOnce = true)
        assertEquals(1f, first)
        assertEquals(1f, later)
    }

    @Test
    fun anyOtherPictureFadesIn() {
        val (first, later) = alphaOfANewPicture(atOnce = false)
        assertEquals("it comes from nothing", 0f, first)
        assertTrue("$later a few frames in: it is still coming up", later > 0f && later < 1f)
    }

    /** The alpha a picture is drawn at in the first frame it shows, then a few frames later. */
    private fun alphaOfANewPicture(atOnce: Boolean): Pair<Float, Float> {
        compose.mainClock.autoAdvance = false
        var target by mutableStateOf<String?>(null)
        var alpha = -1f
        compose.setContent {
            FadeBetween(target, Modifier, atOnce = { it != null && atOnce }) { picture, drawnAt ->
                if (picture != null) {
                    val now = drawnAt()
                    SideEffect { alpha = now }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        target = "backdrop"
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
