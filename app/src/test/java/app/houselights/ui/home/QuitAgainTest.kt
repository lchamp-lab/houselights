package app.houselights.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.houselights.ui.design.LaunchScreen
import app.houselights.ui.design.LocalLaunchScreen
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressBack
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Focus: Back on home's grid says once that a second Back quits, in its head, then quits, and that the scan
 * will resume while the library's first pass runs; under the launch screen, the first quits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class QuitAgainTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val home = fullHome()

    private val line = "Press Back again to quit"

    /** The line while the library's first pass runs: the scan waits while the app is away. */
    private val waits = "Press Back again to quit: the scan will resume when you come back"

    /** The launch screen, covering until it lifts. */
    private class Cover : LaunchScreen {
        override var covers = true

        override fun drawn() = Unit
    }

    private fun open(launch: LaunchScreen? = null, shown: HomeState = home) {
        compose.setTvContent {
            if (launch == null) HomeGrid(shown) else CompositionLocalProvider(LocalLaunchScreen provides launch) { HomeGrid(shown) }
        }
        compose.waitForIdle()
    }

    private val quits get() = compose.activity.isFinishing

    @Test
    fun aFirstBackSaysASecondQuits() {
        open()
        compose.pressBack()
        // Read out politely as it comes.
        compose.onNodeWithText(line).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertFalse("one Back never quits", quits)
        compose.pressBack()
        assertTrue(quits)
    }

    @Test
    fun everyDoorAsksTheSameWay() {
        open()
        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionDown)
        compose.pressBack()
        compose.onNodeWithText(line).assertIsDisplayed()
        assertFalse(quits)
        compose.pressBack()
        assertTrue(quits)
    }

    @Test
    fun theLineGoesWhenItsTimeIsOutAndBackAsksAgain() {
        open()
        compose.mainClock.autoAdvance = false
        compose.pressBack()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(line).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(QUIT_WINDOW_MS + FRAME_MS)
        compose.onNodeWithText(line).assertDoesNotExist()
        compose.pressBack()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(line).assertIsDisplayed()
        assertFalse("the first Back was too long ago", quits)
    }

    @Test
    fun theLineGoesAsSoonAsFocusMoves() {
        open()
        compose.pressBack()
        compose.onNodeWithText(line).assertIsDisplayed()
        // To another door it goes, and Back asks again.
        compose.press(Key.DirectionRight)
        compose.onNodeWithText(line).assertDoesNotExist()
        compose.pressBack()
        assertFalse(quits)
        compose.onNodeWithText(line).assertIsDisplayed()
    }

    /** On the library's first scan, a first Back says the scan will resume, then a second quits. */
    @Test
    fun onTheFirstScanTheLineSaysTheScanWillResume() {
        open(shown = HomeState(content = HomeContent.FirstScan, firstPass = true))
        compose.pressBack()
        compose.onNodeWithText(waits).assertIsDisplayed()
        assertFalse(quits)
        compose.pressBack()
        assertTrue(quits)
    }

    /** On home's grid after « Aller à l'accueil », while the first pass goes on, the line says so as well. */
    @Test
    fun onTheGridWhileTheFirstPassRunsTheLineSaysTheScanWillResume() {
        open(shown = home.copy(firstPass = true))
        compose.pressBack()
        compose.onNodeWithText(waits).assertIsDisplayed()
        compose.onNodeWithText(line).assertDoesNotExist()
    }

    @Test
    fun underTheLaunchScreenTheFirstBackQuitsWithoutTheLine() {
        open(Cover())
        compose.pressBack()
        assertTrue("nothing seen yet: Back quits", quits)
        compose.onNodeWithText(line).assertDoesNotExist()
    }

    @Test
    fun onceTheLaunchScreenLiftsTheFirstBackAsks() {
        val cover = Cover()
        open(cover)
        // It lifts without home being composed again.
        cover.covers = false
        compose.pressBack()
        compose.onNodeWithText(line).assertIsDisplayed()
        assertFalse(quits)
        compose.pressBack()
        assertTrue(quits)
    }

    private companion object {
        /** A frame and more, past the line's time. */
        const val FRAME_MS = 32L
    }
}
