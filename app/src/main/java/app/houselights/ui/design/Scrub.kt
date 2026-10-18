package app.houselights.ui.design

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf

/**
 * Whether an arrow held (or re-pressed within [AGAIN_MS], as infrared remotes repeat) runs focus across a
 * row: crossed cards neither grow nor light, sparing work. A run ends [SETTLE_MS] after the last arrow.
 */
class ScrubTracker(
    private val now: () -> Long = SystemClock::uptimeMillis,
    private val later: (Long, () -> Unit) -> () -> Unit,
) {
    private val running = mutableStateOf(false)
    private var lastArrow = Long.MIN_VALUE / 2
    private var stop: (() -> Unit)? = null

    /** True while focus is running; it changes only when a run starts or ends. */
    val active: Boolean get() = running.value

    /** Takes a key the activity receives; never consumes it. */
    fun onKey(event: KeyEvent) = onKey(event.action, event.keyCode, event.repeatCount)

    fun onKey(action: Int, keyCode: Int, repeatCount: Int) {
        if (keyCode !in ARROWS) return
        val at = now()
        when (action) {
            KeyEvent.ACTION_DOWN -> {
                stop?.invoke()
                stop = null
                if (repeatCount > 0 || at - lastArrow < AGAIN_MS) running.value = true
                lastArrow = at
            }
            KeyEvent.ACTION_UP -> if (running.value) {
                stop?.invoke()
                stop = later(SETTLE_MS) {
                    running.value = false
                    stop = null
                }
            }
        }
    }

    companion object {
        /** Arrows pressed again within this time run focus as a held arrow does (an infrared remote's repeat). */
        const val AGAIN_MS = 140L

        /** How long after the last arrow is released a run ends. */
        const val SETTLE_MS = 120L

        private val ARROWS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
        )
    }
}

/** The app's [ScrubTracker], fed every key by the activity. */
val Scrub = ScrubTracker { delay, block ->
    val handler = Handler(Looper.getMainLooper())
    val run = Runnable(block)
    handler.postDelayed(run, delay)
    val cancel: () -> Unit = { handler.removeCallbacks(run) }
    cancel
}
