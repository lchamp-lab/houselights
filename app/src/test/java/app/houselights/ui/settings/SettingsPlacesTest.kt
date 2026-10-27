package app.houselights.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import app.houselights.core.SourceConfig
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The settings' places: focus lights a place at once, its pane opening once focus settles there, or at once on OK. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class SettingsPlacesTest {
    @get:Rule
    val compose = createComposeRule()

    private val panes = panesFor(SourceConfig.Local, pathMapping = true, counts = true, tracks = false, trakt = false)
    private var open by mutableStateOf(Pane.Library)
    private val opened = mutableListOf<Pane>()

    /** The places with focus on [from], the clock then moved by hand. */
    private fun placesFocusedOn(from: String) {
        compose.setTvContent {
            val memory = rememberFocusMemory()
            SettingsPlaces(panes, open, memory, ready = true) {
                open = it
                opened += it
            }
        }
        compose.onNodeWithText(from).requestFocus()
        compose.waitForIdle()
        compose.runOnIdle { opened.clear() }
        compose.mainClock.autoAdvance = false
    }

    private fun press(key: Key) = compose.onRoot().performKeyInput { pressKey(key) }

    /** Down three times, quickly: no pane passed over opens, only Trakt's, where focus stays. */
    @Test
    fun steppingDownTheListOpensOnlyThePlaceFocusSettlesOn() {
        placesFocusedOn("Bibliothèque")
        repeat(3) {
            press(Key.DirectionDown)
            compose.mainClock.advanceTimeBy(STEP_MS)
        }
        compose.onNodeWithText("Trakt").assertIsFocused()
        assertEquals("no pane passed over opens", emptyList<Pane>(), opened)
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE)
        assertEquals(listOf(Pane.Trakt), opened)
    }

    @Test
    fun okOpensThePlaceAtOnce() {
        placesFocusedOn("Bibliothèque")
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeByFrame()
        assertEquals(listOf(Pane.Numbers), opened)
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE)
        assertEquals("opened once", listOf(Pane.Numbers), opened)
    }

    private companion object {
        /** A remote's arrow held down repeats about this often. */
        const val STEP_MS = 100L
    }
}
