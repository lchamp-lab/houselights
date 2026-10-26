package app.houselights.ui.testing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.SparseArray
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.pressKey
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.design.components.CutPosterCache
import app.houselights.ui.design.components.LocalCutPosters
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowInputDevice
import org.robolectric.util.ReflectionHelpers
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

/**
 * Sets [content] in the app's theme on the TV canvas (960 × 540 dp at density 1): a canvas dp is a test pixel. Its
 * doors' counts are cut into a cache of its own, as in a new process; its text at [textStep] (« Taille du texte »).
 */
fun ComposeContentTestRule.setTvContent(textStep: Float = 1f, content: @Composable () -> Unit) = setContent {
    CompositionLocalProvider(LocalCutPosters provides remember { CutPosterCache() }) { HouselightsTheme(textStep, content) }
}

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

/**
 * Presses [key] [times] times as the player's remote sends it, each after the last settles: from a D-pad that is a
 * device of its own, not a virtual one. [press]'s keys come from no device, so Compose's text field never gives them
 * the turn it gives a remote's arrows (a focus move) and OK (the keyboard) before its own handling.
 */
fun ComposeContentTestRule.pressOnRemote(key: Key, times: Int = 1) = repeat(times) {
    val at = SystemClock.uptimeMillis()
    remoteKey(at, at, KeyEvent.ACTION_DOWN, key)
    remoteKey(at, at, KeyEvent.ACTION_UP, key)
}

/** [key] held on the remote: its press, then [repeats] repeats as a held key sends them, then let go. */
fun ComposeContentTestRule.holdOnRemote(key: Key, repeats: Int) {
    val down = SystemClock.uptimeMillis()
    (0..repeats).forEach { remoteKey(down, down + it * REPEAT_MS, KeyEvent.ACTION_DOWN, key, repeat = it) }
    remoteKey(down, down + (repeats + 1) * REPEAT_MS, KeyEvent.ACTION_UP, key)
}

private fun ComposeContentTestRule.remoteKey(down: Long, at: Long, action: Int, key: Key, repeat: Int = 0) {
    val event = KeyEvent(down, at, action, key.nativeKeyCode, repeat, 0, remote(), 0, 0, REMOTE_SOURCES)
    onRoot().performKeyPress(ComposeKeyEvent(event))
    waitForIdle()
}

/** The remote's id, registered once a test (Robolectric's input manager starts each with no device) as a D-pad. */
private fun remote(): Int {
    val inputs = ReflectionHelpers.callStaticMethod<InputManager>(InputManager::class.java, "getInstance")
    if (inputs.getInputDevice(REMOTE) == null) {
        val device = ShadowInputDevice.makeInputDeviceNamed("Remote")
        // Not virtual (a virtual device's id is negative), its keys a D-pad's.
        ReflectionHelpers.setField(device, "mId", REMOTE)
        ReflectionHelpers.setField(device, "mSources", REMOTE_SOURCES)
        ReflectionHelpers.getField<SparseArray<InputDevice>>(inputs, "mInputDevices").put(REMOTE, device)
    }
    return REMOTE
}

private const val REMOTE = 7

/** A remote's keys as Android reads them: a keyboard's that are a D-pad's. */
private const val REMOTE_SOURCES = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_DPAD

/** Between a held key's repeats, as a remote sends them. */
private const val REPEAT_MS = 50L

/** A node whose long click accessibility services name [label]. */
fun longClickNamed(label: String) =
    SemanticsMatcher("long click named $label") { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == label }

/** A key of the remote ([KeyEvent] code) in the open sheet, sent to its window as Android does, so its own handlers get it. */
fun ComposeContentTestRule.pressInSheet(keyCode: Int) {
    runOnIdle {
        val window = sheetWindow()
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { window.decorView.dispatchKeyEvent(KeyEvent(it, keyCode)) }
    }
    waitForIdle()
}

/** Half a press in the open sheet: the key going down ([KeyEvent.ACTION_DOWN]) or let go, what happens between them the test's. */
fun ComposeContentTestRule.keyInSheet(action: Int, keyCode: Int) {
    runOnIdle { sheetWindow().decorView.dispatchKeyEvent(KeyEvent(action, keyCode)) }
    waitForIdle()
}

private fun sheetWindow() = checkNotNull(ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window) { "No sheet shows" }

/** Draws the activity's window now, as Android does each frame, and returns it: Robolectric draws only when asked. */
fun AndroidComposeTestRule<*, out ComponentActivity>.drawWindow(): Bitmap = runOnUiThread {
    val window = activity.window.decorView
    Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888).also { window.draw(Canvas(it)) }
}
