package app.houselights

import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * The launch screen's gate, one per process: the splash screen (the mark alone on black,
 * `Theme.Houselights.Launch`) covers the app while it is shut, and home is composed, laid out and
 * drawn under it. It opens once, for the first of three reasons: home is whole under it, its first
 * content in, the pictures it shows first in memory and a first frame of all of it drawn
 * ([Reason.READY]); the app starts on another screen ([Reason.START_SCREEN]); or [capMs] after the
 * process started ([Reason.CAP]), so that a NAS or TMDB slow to answer never holds the app. It says
 * in the log, at the warning level release builds keep, when and why it opened.
 */
class LaunchGate(
    /** Milliseconds since the process started. */
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

    /**
     * When the settings were read, home's first content came, its first pictures, and when home drew
     * its first frame whole (ms since the process started).
     */
    private var settingsAt: Long? = null
    private var rowsAt: Long? = null
    private var picturesAt: Long? = null
    private var drawnAt: Long? = null

    /** Why it opened; null while it holds. */
    val reason: Reason? get() = opened.value

    val isOpen: Boolean get() = opened.value != null

    /** The settings are read: what the app starts on is known. Said in the log, as what comes before it. */
    fun settingsRead() = synchronized(this) {
        settingsAt = settingsAt ?: sinceStart()
    }

    /** Home's first content is in: its rows, or why it has none. */
    fun homeLoaded() = synchronized(this) {
        rowsAt = rowsAt ?: sinceStart()
        openIfReady()
    }

    /** The pictures home shows first are in memory, or there are none to wait for. */
    fun picturesLoaded() = synchronized(this) {
        picturesAt = picturesAt ?: sinceStart()
        openIfReady()
    }

    /** Home drew its first frame whole under the launch screen: its content, and its backdrop's picture. */
    fun homeDrawn() = synchronized(this) {
        drawnAt = drawnAt ?: sinceStart()
        openIfReady()
    }

    /** The app starts on another screen than home (welcome, settings unread, a screen brought back): nothing to wait for. */
    fun startsElsewhere() = open(Reason.START_SCREEN)

    /** Opens it at the cap if nothing did before. */
    suspend fun openAtCap() {
        delay((capMs - sinceStart()).coerceAtLeast(0))
        open(Reason.CAP)
    }

    /** Waits until it opens, and says why. */
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

    /** When a part came, or [missing]. */
    private fun at(ms: Long?, missing: String) = ms?.let { "at $it ms" } ?: missing

    private companion object {
        const val TAG = "Launch"

        /** The longest the launch screen holds, from the process's start: a slow source never holds the app. */
        const val CAP_MS = 1_500L
    }
}

/** Milliseconds since this process started, on the clock that counts deep sleep. */
private fun sinceProcessStart(): Long = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
