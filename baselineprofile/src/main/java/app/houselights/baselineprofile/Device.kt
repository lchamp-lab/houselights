package app.houselights.baselineprofile

import android.os.SystemClock
import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** The package the journeys run on: the variant the Gradle plugin installed (release, benchmark). */
internal val targetPackage: String
    get() = requireNotNull(InstrumentationRegistry.getArguments().getString("targetAppId")) {
        "targetAppId is set by the baseline profile Gradle plugin: run the generator or the benchmarks through Gradle"
    }

/** How long a viewer leaves between two presses of the remote while browsing. */
internal const val PRESS_MS = 400L

/** How long a screen may take to show. */
internal const val SCREEN_MS = 10_000L

/** A string of the app, in the device's language: the journeys find their way by what the screen says. */
internal fun MacrobenchmarkScope.appString(name: String): String {
    val resources = InstrumentationRegistry.getInstrumentation().context.packageManager.getResourcesForApplication(packageName)
    val id = resources.getIdentifier(name, "string", packageName)
    check(id != 0) { "No string $name in $packageName" }
    return resources.getString(id)
}

/** Presses [key] once and leaves the screen the time a viewer would. */
internal fun MacrobenchmarkScope.press(key: Int, times: Int = 1) {
    repeat(times) {
        device.pressKeyCode(key)
        SystemClock.sleep(PRESS_MS)
    }
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

/** Whether a text on screen reads [text]. */
internal fun MacrobenchmarkScope.shows(text: String): Boolean = device.hasObject(By.text(text))

/** Every word the element with focus says: its text, its description, and its children's. */
private fun UiObject2.words(): String =
    listOfNotNull(text, contentDescription).joinToString(" ") + " " + children.joinToString(" ") { it.words() }

/** Whether the element with focus says [text]. */
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
