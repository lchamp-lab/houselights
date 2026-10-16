package app.houselights.ui.testing

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.houselights.ui.design.HouselightsTheme
import org.robolectric.shadows.ShadowDialog

/**
 * What a Compose test of the design system runs on: the app's theme (its canvas density, its list
 * scrolling), on the TV canvas. Robolectric gives the screen 960 × 540 dp at density 1
 * (`@Config(qualifiers = TV)`), so a dp of the canvas is a pixel of the test.
 */
fun ComposeContentTestRule.setTvContent(content: @Composable () -> Unit) = setContent { HouselightsTheme(content) }

/** The screen of a television, as Robolectric qualifiers. */
const val TV = "w960dp-h540dp-land-mdpi"

/**
 * Presses [key] on the remote, [times] times, each press once what it started has settled. The
 * key goes to what has focus in the window of [window], the screen's unless a sheet is open.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeContentTestRule.press(key: Key, times: Int = 1, window: SemanticsNodeInteraction = onRoot()) = repeat(times) {
    window.performKeyInput { pressKey(key) }
    waitForIdle()
}

/**
 * Back on the remote in the sheet that shows, as Android delivers it: to the window, which hands
 * it to what has focus and then to the sheet's own Back ([androidx.activity.compose.BackHandler],
 * else closing). A key sent to the content alone would never reach the window's Back.
 */
fun ComposeContentTestRule.pressBackInSheet() {
    runOnIdle {
        val window = checkNotNull(ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window) { "No sheet shows" }
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { window.decorView.dispatchKeyEvent(KeyEvent(it, KeyEvent.KEYCODE_BACK)) }
    }
    waitForIdle()
}
