package app.houselights

import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * The launch screen's gate, one per process: the splash (`Theme.Houselights.Launch`) covers the app while home
 * is drawn under it. It opens once: home is complete ([Reason.READY]), the app starts elsewhere
 * ([Reason.START_SCREEN]), or at [capMs] ([Reason.CAP]) so a slow NAS never holds the app; why is logged.
 */
class LaunchGate(
    private val sinceStart: () -> Long = ::sinceProcessStart,
    private val capMs: Long = CAP_MS,
    private val report: (String) -> Unit = { Log.w(TAG, it) },
) {
    enum class Reason(val said: String) {
        READY("ready"),
        START_SCREEN("start screen"),
        CAP("cap"),
    }

    private val opened = MutableStateFlow<Reason?>(null)

    /** Milestones in ms since process start: settings read, first rows, first pictures, first full frame. */
    private var settingsAt: Long? = null
    private var rowsAt: Long? = null
    private var picturesAt: Long? = null
    private var drawnAt: Long? = null

    /** Why it opened; null while it holds. */
    val reason: Reason? get() = opened.value

    val isOpen: Boolean get() = opened.value != null

    /** Records that the settings are read, so the start screen is known; logged only, it never opens the gate. */
    fun settingsRead() = synchronized(this) {
        settingsAt = settingsAt ?: sinceStart()
    }

    /** Records home's first content: its rows, or why it has none. */
    fun homeLoaded() = synchronized(this) {
        rowsAt = rowsAt ?: sinceStart()
        openIfReady()
    }

    /** Records that home's first pictures are in memory, or that there are none to wait for. */
    fun picturesLoaded() = synchronized(this) {
        picturesAt = picturesAt ?: sinceStart()
        openIfReady()
    }

    /** Records home's first full frame under the launch screen, backdrop picture included. */
    fun homeDrawn() = synchronized(this) {
        drawnAt = drawnAt ?: sinceStart()
        openIfReady()
    }

    /** Opens at once: the app starts on a screen other than home (welcome, unread settings, a restored screen). */
    fun startsElsewhere() = open(Reason.START_SCREEN)

    /** Opens the gate at the cap unless it is already open. */
    suspend fun openAtCap() {
        delay((capMs - sinceStart()).coerceAtLeast(0))
        open(Reason.CAP)
    }

    /** Suspends until the gate opens and returns why. */
    suspend fun awaitOpen(): Reason = opened.filterNotNull().first()

    private fun openIfReady() {
        if (rowsAt != null && picturesAt != null && drawnAt != null) open(Reason.READY)
    }

    private fun open(reason: Reason) = synchronized(this) {
        if (!opened.compareAndSet(null, reason)) return
        val home = reason != Reason.START_SCREEN
        val parts = listOfNotNull(
            "settings ${at(settingsAt, "not in")}",
            "rows ${at(rowsAt, "not in")}".takeIf { home },
            "pictures ${at(picturesAt, "not in")}".takeIf { home },
            "home ${drawnAt?.let { "drawn at $it ms" } ?: "not drawn"}".takeIf { home },
        ).joinToString()
        report("Launch screen left ${sinceStart()} ms after the process started: ${reason.said} ($parts)")
    }

    private fun at(ms: Long?, missing: String) = ms?.let { "at $it ms" } ?: missing

    private companion object {
        const val TAG = "Launch"

        /** Longest the launch screen holds, counted from process start. */
        const val CAP_MS = 1_500L
    }
}

/** Milliseconds since this process started, on the clock that counts deep sleep. */
private fun sinceProcessStart(): Long = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
