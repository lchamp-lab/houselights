package app.houselights

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.houselights.core.TrackPlan
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player opens once the room is dark only if the app is still on screen. */
class PlayOnScreenTest {
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    private fun at(state: Lifecycle.State): Lifecycle = owner.lifecycle.apply { currentState = state }

    @Test
    fun homePressedDuringTheCueOpensNothingOverTheAppGoneTo() {
        at(Lifecycle.State.RESUMED)
        // Home: paused, then stopped.
        assertFalse(at(Lifecycle.State.CREATED).onScreen())
    }

    /** A system overlay over the app only pauses it: Lire still plays. */
    @Test
    fun anOverlayThatPausesTheAppLetsThePlayerOpen() {
        assertTrue(at(Lifecycle.State.RESUMED).onScreen())
        assertTrue(at(Lifecycle.State.STARTED).onScreen())
    }

    /** Home pressed while the title's tracks are read, the last wait before the player: it does not open. */
    @Test
    fun homePressedWhileTheTracksAreReadOpensNothing() = runTest {
        at(Lifecycle.State.RESUMED)
        var opened = false
        val readWhileHomeIsPressed: suspend () -> TrackPlan? = {
            at(Lifecycle.State.CREATED)
            null
        }
        val launched = owner.lifecycle.launchOnScreen(readWhileHomeIsPressed) { opened = true }
        assertFalse(launched || opened)
    }

    @Test
    fun theTracksReadOnScreenGoToThePlayer() = runTest {
        at(Lifecycle.State.STARTED)
        var opened = false
        assertTrue(owner.lifecycle.launchOnScreen({ null }) { opened = true })
        assertTrue(opened)
    }
}
