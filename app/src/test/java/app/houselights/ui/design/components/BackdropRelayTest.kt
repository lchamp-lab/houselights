package app.houselights.ui.design.components

import android.graphics.Bitmap
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import app.houselights.ui.design.ArtworkSample
import app.houselights.ui.testing.TV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Screens cut, the picture stays: a backdrop arriving starts from the picture of the screen left,
 * whole, which stays whole under what comes until it is in; a screen without a backdrop lets it go out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class BackdropRelayTest {
    @get:Rule
    val compose = createComposeRule()

    private val picture by lazy {
        PreparedBackdrop(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap(), ArtworkSample(1, 1, floatArrayOf(0.5f)))
    }
    private fun shot(style: BackdropStyle = BackdropStyle.Hero, url: String = "a") = Shot(picture, style, url)

    @Test
    fun theArrivingBackdropStartsFromThePictureLeft() {
        val relay = BackdropRelay()
        val home = Any()
        assertNull("the first screen starts from nothing", relay.claim(home))
        relay.shows(home, shot())
        val detail = BackdropState()
        detail.startFrom(relay.claim(detail))
        relay.release(home)
        val seed = detail.shown!!
        assertTrue(seed.handedOn && seed.atOnce)
        assertSame(picture, seed.prepared)
        assertNull("another backdrop took it: nothing goes out", relay.leaving)
    }

    @Test
    fun aScreenWithoutABackdropLetsThePictureGoOut() {
        val relay = BackdropRelay()
        val home = Any()
        relay.claim(home)
        val shown = shot()
        relay.shows(home, shown)
        relay.release(home)
        assertSame(shown, relay.leaving)
        val back = Any()
        assertNull("gone out, it is not handed on again", relay.claim(back))
        relay.gone(shown)
        assertNull(relay.leaving)
    }

    @Test
    fun aBackdropLeftBehindNoLongerSaysWhatShows() {
        val relay = BackdropRelay()
        val home = Any()
        relay.claim(home)
        relay.shows(home, shot(url = "home"))
        val detail = Any()
        relay.claim(detail)
        relay.shows(home, shot(url = "late"))
        assertEquals("home", relay.claim(Any())?.url)
    }

    @Test
    fun theSamePictureInTheSameStyleContinuesAtOnce() {
        val state = BackdropState()
        state.startFrom(shot().handedOn())
        state.show(null, shot(), covered = false)
        assertTrue("nothing to fade: the same picture", state.shown!!.atOnce)
        val other = BackdropState()
        other.startFrom(shot().handedOn())
        other.show(null, shot(BackdropStyle.Full), covered = false)
        assertFalse("its veils change: they fade", other.shown!!.atOnce)
    }

    /** The picture handed on stays whole while what replaces it comes up. */
    @Test
    fun whatIsHandedOnHoldsUnderWhatComes() {
        assertEquals(1f, leavingAlpha(coming = "detail"))
    }

    /** With nothing coming, it goes out on the black room. */
    @Test
    fun whatIsHandedOnGoesOutForNothing() {
        val alone = leavingAlpha(coming = null)
        assertTrue("$alone: going out", alone < 1f)
    }

    private fun leavingAlpha(coming: String?): Float {
        compose.mainClock.autoAdvance = false
        var target by mutableStateOf<String?>("left")
        var alpha = -1f
        compose.setContent {
            FadeBetween(target, Modifier, holds = { leaving, next -> leaving == "left" && next != null }) { shown, drawnAt ->
                if (shown == "left") {
                    val now = drawnAt()
                    SideEffect { alpha = now }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        target = coming
        Snapshot.sendApplyNotifications()
        repeat(FRAMES) { compose.mainClock.advanceTimeByFrame() }
        return alpha
    }

    private companion object {
        /** A few frames of `SLOW`: 100 ms of 600. */
        const val FRAMES = 6
    }
}
