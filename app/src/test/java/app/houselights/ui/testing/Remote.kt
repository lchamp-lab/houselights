package app.houselights.ui.testing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
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

/** Presses [key] [times] times on the screen, each after the last settles. */
@OptIn(ExperimentalTestApi::class)
fun ComposeContentTestRule.press(key: Key, times: Int = 1) = repeat(times) {
    onRoot().performKeyInput { pressKey(key) }
    waitForIdle()
}

/** OK held past the remote's repeat delay, its repeats sent as a remote sends them, then let go. */
@OptIn(ExperimentalTestApi::class)
fun ComposeContentTestRule.holdOk() {
    onRoot().performKeyInput {
        keyDown(Key.DirectionCenter)
        advanceEventTime(HELD_MS)
        keyUp(Key.DirectionCenter)
    }
    waitForIdle()
}

/** Longer than a remote's repeat delay, so held OK repeats several times. */
private const val HELD_MS = 800L

/** A node whose long click accessibility services name [label]. */
fun longClickNamed(label: String) =
    SemanticsMatcher("long click named $label") { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == label }

/** A key of the remote ([KeyEvent] code) in the open sheet, sent to its window as Android does, so its own handlers get it. */
fun ComposeContentTestRule.pressInSheet(keyCode: Int) {
    runOnIdle {
        val window = checkNotNull(ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window) { "No sheet shows" }
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { window.decorView.dispatchKeyEvent(KeyEvent(it, keyCode)) }
    }
    waitForIdle()
}

/** Draws the activity's window now, as Android does each frame, and returns it: Robolectric draws only when asked. */
fun AndroidComposeTestRule<*, out ComponentActivity>.drawWindow(): Bitmap = runOnUiThread {
    val window = activity.window.decorView
    Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888).also { window.draw(Canvas(it)) }
}
