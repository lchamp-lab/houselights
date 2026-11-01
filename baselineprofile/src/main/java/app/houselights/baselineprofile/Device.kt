package app.houselights.baselineprofile

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** The package under test: the variant the Gradle plugin installed (release, benchmark). */
internal val targetPackage: String
    get() = requireNotNull(InstrumentationRegistry.getArguments().getString("targetAppId")) {
        "targetAppId is set by the baseline profile Gradle plugin: run the generator or the benchmarks through Gradle"
    }

/** Pause between two remote presses, as a viewer browses. */
internal const val PRESS_MS = 400L

/** How long a screen may take to show. */
internal const val SCREEN_MS = 10_000L

/** An app string in the device's language: the journeys navigate by on-screen text. */
internal fun MacrobenchmarkScope.appString(name: String): String {
    val resources = InstrumentationRegistry.getInstrumentation().context.packageManager.getResourcesForApplication(packageName)
    val id = resources.getIdentifier(name, "string", packageName)
    check(id != 0) { "No string $name in $packageName" }
    return resources.getString(id)
}

/** Presses [key] [times] times, pausing as a viewer would. */
internal fun MacrobenchmarkScope.press(key: Int, times: Int = 1) {
    repeat(times) {
        device.pressKeyCode(key)
        SystemClock.sleep(PRESS_MS)
    }
}

/**
 * Holds [key] as a remote does, [repeats] repeats at the system's key repeat rate, then waits for the run to end:
 * the repeat count is what tells the app the arrow is held, which [press] cannot send.
 */
internal fun hold(key: Int, repeats: Int) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val down = SystemClock.uptimeMillis()
    fun send(action: Int, repeat: Int) {
        val event = KeyEvent(
            down,
            SystemClock.uptimeMillis(),
            action,
            key,
            repeat,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD,
        )
        check(automation.injectInputEvent(event, true)) { "Key $key not injected" }
    }
    send(KeyEvent.ACTION_DOWN, 0)
    SystemClock.sleep(ViewConfiguration.getKeyRepeatTimeout().toLong())
    for (repeat in 1..repeats) {
        send(KeyEvent.ACTION_DOWN, repeat)
        SystemClock.sleep(ViewConfiguration.getKeyRepeatDelay().toLong())
    }
    send(KeyEvent.ACTION_UP, 0)
    SystemClock.sleep(PRESS_MS)
}

internal fun MacrobenchmarkScope.right(times: Int = 1) = press(KeyEvent.KEYCODE_DPAD_RIGHT, times)
internal fun MacrobenchmarkScope.left(times: Int = 1) = press(KeyEvent.KEYCODE_DPAD_LEFT, times)
internal fun MacrobenchmarkScope.up(times: Int = 1) = press(KeyEvent.KEYCODE_DPAD_UP, times)
internal fun MacrobenchmarkScope.down(times: Int = 1) = press(KeyEvent.KEYCODE_DPAD_DOWN, times)
internal fun MacrobenchmarkScope.ok() = press(KeyEvent.KEYCODE_DPAD_CENTER)
internal fun MacrobenchmarkScope.back(times: Int = 1) = press(KeyEvent.KEYCODE_BACK, times)

/** Waits until something on screen matches [selector]; fails, saying what was awaited, after [timeout]. */
internal fun MacrobenchmarkScope.await(selector: BySelector, what: String, timeout: Long = SCREEN_MS): UiObject2 =
    checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Waited ${timeout / 1000} s for $what" }

/** Whether something on screen begins with [text]: a heading or a button carries its count after its name. */
internal fun MacrobenchmarkScope.shows(text: String): Boolean = device.hasObject(By.textStartsWith(text))

/** All the text an element says: its text, description and children's. */
private fun UiObject2.words(): String =
    listOfNotNull(text, contentDescription).joinToString(" ") + " " + children.joinToString(" ") { it.words() }

internal fun MacrobenchmarkScope.focusSays(text: String): Boolean =
    device.findObject(By.focused(true))?.words()?.contains(text) == true

/** Moves focus with [key] until the element with focus says [text], [max] presses at most. */
internal fun MacrobenchmarkScope.focusOn(text: String, key: Int, max: Int = 12) {
    repeat(max) {
        if (focusSays(text)) return
        press(key)
    }
    check(focusSays(text)) { "Focus never reached \"$text\"" }
}

/** What on screen says [text]: its exact text first, then a text or description beginning with it. */
internal fun MacrobenchmarkScope.find(text: String): UiObject2? =
    device.findObject(By.text(text)) ?: device.findObject(By.textStartsWith(text)) ?: device.findObject(By.descStartsWith(text))

/**
 * Moves focus to what says [text] wherever it lies on screen: each press goes from the element with focus toward
 * it, down or up while it lies under or over, else right or left. A grid's layout (a door across the whole row or
 * half of it) changes no journey; [max] presses at most.
 */
internal fun MacrobenchmarkScope.focusTo(text: String, max: Int = 12) {
    repeat(max) {
        if (focusSays(text)) return
        val target = checkNotNull(find(text)) { "Nothing on screen says \"$text\"" }.visibleBounds
        // None while a screen is still covered or settling: waited for, not pressed.
        val from = device.findObject(By.focused(true))?.visibleBounds
        val key = from?.let { box -> towards(box, target) }
        if (key == null) SystemClock.sleep(PRESS_MS) else press(key)
    }
    check(focusSays(text)) { "Focus never reached \"$text\"" }
}

/** The arrow from [from] toward [to]; none when [to] lies inside it. */
private fun towards(from: Rect, to: Rect): Int? = when {
    to.centerY() > from.bottom -> KeyEvent.KEYCODE_DPAD_DOWN
    to.centerY() < from.top -> KeyEvent.KEYCODE_DPAD_UP
    to.centerX() > from.right -> KeyEvent.KEYCODE_DPAD_RIGHT
    to.centerX() < from.left -> KeyEvent.KEYCODE_DPAD_LEFT
    else -> null
}
