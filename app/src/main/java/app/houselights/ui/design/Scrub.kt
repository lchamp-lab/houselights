package app.houselights.ui.design

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf

/**
 * Whether the remote is running focus across a row or a wall, an arrow held: film running through
 * the gate. Cards it crosses then neither grow nor light; the one it stops on does, once the arrow
 * is let go ([ArtworkSurface][app.houselights.ui.design.components.ArtworkSurface]). Fewer pictures
 * read and no light computed for cards only crossed.
 *
 * An arrow repeated by the remote ([KeyEvent.getRepeatCount]) runs; so do arrows pressed again
 * within [AGAIN_MS], as infrared remotes repeat by pressing again. It stops [SETTLE_MS] after the
 * last arrow is let go, so such a remote does not light a card between two of its presses.
 */
class ScrubTracker(
    private val now: () -> Long = SystemClock::uptimeMillis,
    private val later: (Long, () -> Unit) -> () -> Unit,
) {
    private val running = mutableStateOf(false)
    private var lastArrow = Long.MIN_VALUE / 2
    private var stop: (() -> Unit)? = null

    /** Focus is running: read while composing or drawing, it changes when a run starts and ends. */
    val active: Boolean get() = running.value

    /** Takes a key the activity is given; never consumes it. */
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

        /** How long after the last arrow is let go a run ends. */
        const val SETTLE_MS = 120L

        private val ARROWS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
        )
    }
}

/** The app's [ScrubTracker], told every key by the activity. */
val Scrub = ScrubTracker { delay, block ->
    val handler = Handler(Looper.getMainLooper())
    val run = Runnable(block)
    handler.postDelayed(run, delay)
    val cancel: () -> Unit = { handler.removeCallbacks(run) }
    cancel
}
