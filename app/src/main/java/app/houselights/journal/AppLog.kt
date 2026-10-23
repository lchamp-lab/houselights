package app.houselights.journal

import android.os.Process
import android.util.Log
import app.houselights.player.Redact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/**
 * The app's lines of the system log, by its uid, so those of a process that crashed before this one too (Android 9
 * gives an app its own uid's lines alone, without a permission): the last [MAX_LINES] at most, within [MAX_BYTES], in
 * threadtime form, each masked ([Redact.text]).
 */
class AppLog(
    /** Starts the command and gives what it prints; logcat's own by default. */
    private val run: (List<String>) -> java.lang.Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
) {
    /** The lines, oldest first; none when logcat cannot be read. */
    suspend fun read(): List<String> = coroutineScope {
        val process = withContext(Dispatchers.IO) {
            try {
                run(COMMAND)
            } catch (e: IOException) {
                Log.w(TAG, "logcat could not start", e)
                null
            }
        } ?: return@coroutineScope emptyList()
        // logcat -d ends once it has printed the buffer: a stuck one is stopped, which ends the reading.
        val watchdog = launch {
            delay(READ_LIMIT_MS)
            process.destroy()
        }
        try {
            withContext(Dispatchers.IO) { keep(process.inputStream, Process.myUid()) }
        } finally {
            watchdog.cancel()
            process.destroy()
        }
    }

    companion object {
        private const val TAG = "Journal"

        /** Lines kept at most, and their size once masked. */
        const val MAX_LINES = 3_000
        const val MAX_BYTES = 1_000_000

        /** Fewer lines than this is almost nothing: the app has hardly run, or Android dropped its lines. */
        const val FEW_LINES = 20

        private const val READ_LIMIT_MS = 10_000L

        /**
         * Dumps the last lines and exits, each with its date, time, uid, process, thread and level. Android 9 has no
         * --uid: logd gives an app its own uid's lines, their uid blank, and with READ_LOGS those of all, each with its
         * uid, which [keep] leaves out.
         */
        private val COMMAND = listOf("logcat", "-d", "-v", "threadtime", "-v", "uid", "-t", "$MAX_LINES")

        /**
         * "10-23 10:42:11.123 10057  4321  4350 I Scan    : …": the time, the uid if logd gave it, then the process,
         * and the thread, level and rest as threadtime has them, whatever their characters. A uid named, as root, is
         * never an app's.
         */
        private val LINE = Regex("""(\d\d-\d\d \d\d:\d\d:\d\d\.\d+) +(?:(\d+) +)?(\d+)( +\d+ \S .*)""", RegexOption.DOT_MATCHES_ALL)

        /** Threadtime's width for the process, as %5d. */
        private const val PID_WIDTH = 5

        /**
         * The last lines of [input] of [uid], without their uid, each masked, within [MAX_LINES] and [MAX_BYTES]; what
         * was read if it breaks off.
         */
        private fun keep(input: InputStream, uid: Int): List<String> {
            val kept = Tail()
            try {
                input.bufferedReader().useLines { lines -> lines.forEach { line -> threadtime(line, uid)?.let(kept::add) } }
            } catch (e: IOException) {
                Log.w(TAG, "logcat stopped answering", e)
            }
            return kept.lines.toList()
        }

        /** [line] in threadtime form if it is [uid]'s or without uid; null for another uid's and logcat's dividers. */
        private fun threadtime(line: String, uid: Int): String? {
            val match = LINE.matchEntire(line) ?: return null
            val (time, lineUid, pid) = match.destructured
            if (lineUid.isNotEmpty() && lineUid.toIntOrNull() != uid) return null
            return "$time ${pid.padStart(PID_WIDTH)}${match.groupValues.last()}"
        }
    }

    /** The last lines added, masked, the oldest dropped past [MAX_LINES] or [MAX_BYTES]. */
    private class Tail {
        val lines = ArrayDeque<String>()
        private var bytes = 0

        fun add(line: String) {
            val masked = Redact.text(line)
            lines.addLast(masked)
            bytes += size(masked)
            while (lines.size > MAX_LINES || bytes > MAX_BYTES) bytes -= size(lines.removeFirst())
        }

        /** Its bytes and its line break. */
        private fun size(line: String) = line.toByteArray().size + 1
    }
}
