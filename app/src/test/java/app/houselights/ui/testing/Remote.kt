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

/** Sets [content] in the app's theme on the TV canvas (960 × 540 dp at density 1): a canvas dp is a test pixel. */
fun ComposeContentTestRule.setTvContent(content: @Composable () -> Unit) = setContent { HouselightsTheme(content) }

/** The screen of a television, as Robolectric qualifiers. */
const val TV = "w960dp-h540dp-land-mdpi"

/** Presses [key] [times] times, each after the last settles, in [window] (the screen's unless a sheet is open). */
@OptIn(ExperimentalTestApi::class)
fun ComposeContentTestRule.press(key: Key, times: Int = 1, window: SemanticsNodeInteraction = onRoot()) = repeat(times) {
    window.performKeyInput { pressKey(key) }
    waitForIdle()
}

/** Back in the open sheet, sent to its window as Android does, so the sheet's own Back handler gets it. */
fun ComposeContentTestRule.pressBackInSheet() {
    runOnIdle {
        val window = checkNotNull(ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window) { "No sheet shows" }
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { window.decorView.dispatchKeyEvent(KeyEvent(it, KeyEvent.KEYCODE_BACK)) }
    }
    waitForIdle()
}
