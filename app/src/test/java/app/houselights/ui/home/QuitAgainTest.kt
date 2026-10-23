package app.houselights.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.houselights.ui.design.LaunchScreen
import app.houselights.ui.design.LocalLaunchScreen
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Focus: Back on home's grid says once that a second Back quits, in its head, then quits; under the
 * launch screen, the first quits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class QuitAgainTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val home = fullHome()

    private val line = "Press Back again to quit"

    /** The launch screen, covering until it lifts. */
    private class Cover : LaunchScreen {
        override var covers = true

        override fun drawn() = Unit
    }

    private fun open(launch: LaunchScreen? = null) {
        compose.setTvContent {
            if (launch == null) HomeGrid(home) else CompositionLocalProvider(LocalLaunchScreen provides launch) { HomeGrid(home) }
        }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private val quits get() = compose.activity.isFinishing

    @Test
    fun aFirstBackSaysASecondQuits() {
        open()
        back()
        compose.onNodeWithText(line).assertIsDisplayed()
        assertFalse("one Back never quits", quits)
        back()
        assertTrue(quits)
    }

    @Test
    fun everyDoorAsksTheSameWay() {
        open()
        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionDown)
        back()
        compose.onNodeWithText(line).assertIsDisplayed()
        assertFalse(quits)
        back()
        assertTrue(quits)
    }

    @Test
    fun theLineGoesWhenItsTimeIsOutAndBackAsksAgain() {
        open()
        compose.mainClock.autoAdvance = false
        back()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(line).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(QUIT_WINDOW_MS + FRAME_MS)
        compose.onNodeWithText(line).assertDoesNotExist()
        back()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(line).assertIsDisplayed()
        assertFalse("the first Back was too long ago", quits)
    }

    @Test
    fun theLineGoesAsSoonAsFocusMoves() {
        open()
        back()
        compose.onNodeWithText(line).assertIsDisplayed()
        // To another door it goes, and Back asks again.
        compose.press(Key.DirectionRight)
        compose.onNodeWithText(line).assertDoesNotExist()
        back()
        assertFalse(quits)
        compose.onNodeWithText(line).assertIsDisplayed()
    }

    @Test
    fun underTheLaunchScreenTheFirstBackQuitsWithoutTheLine() {
        open(Cover())
        back()
        assertTrue("nothing seen yet: Back quits", quits)
        compose.onNodeWithText(line).assertDoesNotExist()
    }

    @Test
    fun onceTheLaunchScreenLiftsTheFirstBackAsks() {
        val cover = Cover()
        open(cover)
        // It lifts without home being composed again.
        cover.covers = false
        back()
        compose.onNodeWithText(line).assertIsDisplayed()
        assertFalse(quits)
        back()
        assertTrue(quits)
    }

    private companion object {
        /** A frame and more, past the line's time. */
        const val FRAME_MS = 32L
    }
}
