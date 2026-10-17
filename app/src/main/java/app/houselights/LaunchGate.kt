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
 * `Theme.Houselights.Launch`) stays while it is shut. It opens once, for the first of three reasons:
 * home is ready under it, its first content in and the pictures it shows first in memory
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

    /** When the settings were read, home's first content came, and its first pictures (ms since the process started). */
    private var settingsAt: Long? = null
    private var rowsAt: Long? = null
    private var picturesAt: Long? = null

    /** Why it opened; null while it holds. */
    val reason: Reason? get() = opened.value

    val isOpen: Boolean get() = opened.value != null

    /** What the splash screen asks at each frame it holds: true while it stays. A frame past the cap opens it. */
    fun keepOnScreen(): Boolean {
        if (!isOpen && sinceStart() >= capMs) open(Reason.CAP)
        return !isOpen
    }

    /** The settings are read: what the app starts on is known. Said in the log, as what comes before it. */
    fun settingsRead() = synchronized(this) {
        settingsAt = settingsAt ?: sinceStart()
    }

    /** Home's first content is in: its rows, or why it has none. */
    fun homeLoaded() = synchronized(this) {
        rowsAt = rowsAt ?: sinceStart()
        if (picturesAt != null) open(Reason.READY)
    }

    /** The pictures home shows first are in memory, or there are none to wait for. */
    fun picturesLoaded() = synchronized(this) {
        picturesAt = picturesAt ?: sinceStart()
        if (rowsAt != null) open(Reason.READY)
    }

    /** The app starts on another screen than home (welcome, settings unread, a screen brought back): nothing to wait for. */
    fun startsElsewhere() = open(Reason.START_SCREEN)

    /** Opens it at the cap if nothing did before, frames or not. */
    suspend fun openAtCap() {
        delay((capMs - sinceStart()).coerceAtLeast(0))
        open(Reason.CAP)
    }

    /** Waits until it opens, and says why. */
    suspend fun awaitOpen(): Reason = opened.filterNotNull().first()

    private fun open(reason: Reason) = synchronized(this) {
        if (!opened.compareAndSet(null, reason)) return
        val parts = listOfNotNull(
            "settings" to settingsAt,
            ("rows" to rowsAt).takeIf { reason != Reason.START_SCREEN },
            ("pictures" to picturesAt).takeIf { reason != Reason.START_SCREEN },
        ).joinToString { (what, ms) -> "$what ${ms?.let { "at $it ms" } ?: "not in"}" }
        report("Launch screen left ${sinceStart()} ms after the process started: ${reason.said} ($parts)")
    }

    private companion object {
        const val TAG = "Launch"

        /** The longest the launch screen holds, from the process's start: a slow source never holds the app. */
        const val CAP_MS = 1_500L
    }
}

/** Milliseconds since this process started, on the clock that counts deep sleep. */
private fun sinceProcessStart(): Long = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
