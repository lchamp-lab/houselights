package app.houselights.core

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Failures that repeat, as a file failing at every pass or a report every few seconds while a server is away: the
 * first of each key is logged with its stack, the next ones only counted until [summarize] says how many in one line,
 * so that the journal's last lines are not all one trace (as `ImageFailureLog` does for pictures). A key failing
 * another way (another exception) is logged whole again, so a new cause is never only counted.
 */
class RepeatedFailures(private val tag: String) {
    /** Each key failing, with the kind of failure last logged whole for it. */
    private val logged = ConcurrentHashMap<String, String>()
    private val again = AtomicInteger()

    /** Logs [message] with [e]'s stack the first time [key] fails, or fails another way, else counts it. */
    fun failed(key: String, message: String, e: Throwable) {
        val kind = e.javaClass.name
        if (logged.put(key, kind) != kind) Log.w(tag, message, e) else again.incrementAndGet()
    }

    /** [key] works again: its next failure is logged whole. */
    fun recovered(key: String) {
        logged.remove(key)
    }

    /** Logs the [line] of the failures counted since the last one, if any. */
    fun summarize(line: (count: Int) -> String) {
        val count = again.getAndSet(0)
        if (count > 0) Log.w(tag, line(count))
    }
}
