package app.houselights.ui.design.components

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.houselights.ui.design.ArtworkSample
import app.houselights.ui.testing.SettleMainLooper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Between screens, a new backdrop starts from the picture left (at once when it is the same); a screen without one lets it go out. */
@RunWith(RobolectricTestRunner::class)
class BackdropRelayTest {
    @get:Rule
    val settle = SettleMainLooper()

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
}
