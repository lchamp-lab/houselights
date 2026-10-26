package app.houselights.ui.testing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.SparseArray
import android.view.InputDevice
import android.view.KeyCharacterMap
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
fun ComposeContentTestRule.pressOnRemote(key: Key, times: Int = 1) = repeat(times) { tap(KeySource.Remote, key) }

/** [key] held on the remote: its press, then [repeats] repeats as a held key sends them, then let go. */
fun ComposeContentTestRule.holdOnRemote(key: Key, repeats: Int) {
    val down = SystemClock.uptimeMillis()
    (0..repeats).forEach { send(KeySource.Remote, down, down + it * REPEAT_MS, KeyEvent.ACTION_DOWN, key, repeat = it) }
    send(KeySource.Remote, down, down + (repeats + 1) * REPEAT_MS, KeyEvent.ACTION_UP, key)
}

/** Presses [key] [times] times as the keyboard shown sends its own arrows: from Android's virtual keyboard. */
fun ComposeContentTestRule.pressOnShownKeyboard(key: Key, times: Int = 1) = repeat(times) { tap(KeySource.Shown, key) }

/** Presses [key] on a keyboard plugged into the player, Shift held when [shift]. */
fun ComposeContentTestRule.pressOnPluggedKeyboard(key: Key, shift: Boolean = false) =
    tap(KeySource.Plugged, key, meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0)

/**
 * Where a key comes from: a device as Android lists it, its [id] (a virtual one's negative) and [sources], and the
 * [source] and [flags] of its keys.
 */
private enum class KeySource(val id: Int, val sources: Int, val source: Int, val flags: Int = 0) {
    /** The player's remote: a device of its own, its keys a keyboard's that are a D-pad's. */
    Remote(7, KEYBOARD_DPAD, KEYBOARD_DPAD),

    /** The keyboard shown, which sends its keys through the text field's connection from Android's virtual keyboard. */
    Shown(KeyCharacterMap.VIRTUAL_KEYBOARD, KEYBOARD_DPAD, InputDevice.SOURCE_UNKNOWN, KeyEvent.FLAG_SOFT_KEYBOARD),

    /** A keyboard plugged in: a device of its own, its arrows a D-pad's, its keys read as a keyboard's alone. */
    Plugged(8, KEYBOARD_DPAD, InputDevice.SOURCE_KEYBOARD),
}

private fun ComposeContentTestRule.tap(from: KeySource, key: Key, meta: Int = 0) {
    val at = SystemClock.uptimeMillis()
    send(from, at, at, KeyEvent.ACTION_DOWN, key, meta = meta)
    send(from, at, at, KeyEvent.ACTION_UP, key, meta = meta)
}

private fun ComposeContentTestRule.send(
    from: KeySource,
    down: Long,
    at: Long,
    action: Int,
    key: Key,
    repeat: Int = 0,
    meta: Int = 0,
) {
    val event = KeyEvent(down, at, action, key.nativeKeyCode, repeat, meta, registered(from), 0, from.flags, from.source)
    check(event.device?.id == from.id) { "Android does not list the device of ${from.name}'s keys" }
    // Else the test would quietly send what Compose's text field reads as another's keys.
    check(fromADPad(event) == (from == KeySource.Remote)) { "${from.name}'s keys do not read as its own" }
    onRoot().performKeyPress(ComposeKeyEvent(event))
    waitForIdle()
}

/** As Compose's own text field tells a D-pad's keys, which it turns into focus moves, from a keyboard's. */
private fun fromADPad(event: KeyEvent): Boolean {
    val device = event.device ?: return false
    return device.supportsSource(InputDevice.SOURCE_DPAD) && !device.isVirtual &&
        event.source != InputDevice.SOURCE_KEYBOARD
}

/** [from]'s device id, listed once a test (Robolectric's input manager starts each with no device). */
private fun registered(from: KeySource): Int {
    val inputs = ReflectionHelpers.callStaticMethod<InputManager>(InputManager::class.java, "getInstance")
    if (inputs.getInputDevice(from.id) == null) {
        val device = ShadowInputDevice.makeInputDeviceNamed(from.name)
        // Virtual when its id is negative.
        ReflectionHelpers.setField(device, "mId", from.id)
        ReflectionHelpers.setField(device, "mSources", from.sources)
        ReflectionHelpers.getField<SparseArray<InputDevice>>(inputs, "mInputDevices").put(from.id, device)
    }
    return from.id
}

/** A keyboard's keys that are a D-pad's, as Android lists a remote, its virtual keyboard and a keyboard with arrows. */
private const val KEYBOARD_DPAD = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_DPAD

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
