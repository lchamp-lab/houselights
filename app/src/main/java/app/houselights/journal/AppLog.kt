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
 * The app's own lines of the system log, as `logcat` gives them to the process that asks for its own (Android 9
 * needs no permission for it): the last [MAX_LINES] at most, within [MAX_BYTES], each masked ([Redact.text]).
 */
class AppLog(
    private val pid: Int = Process.myPid(),
    /** Starts the command and gives what it prints; logcat's own by default. */
    private val run: (List<String>) -> java.lang.Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
) {
    /** The lines, oldest first; none when logcat cannot be read. */
    suspend fun read(): List<String> = coroutineScope {
        val process = withContext(Dispatchers.IO) {
            try {
                run(command(pid))
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
            withContext(Dispatchers.IO) { keep(process.inputStream) }
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

        /** Fewer lines than this is almost nothing: the app restarted since, or Android dropped them. */
        const val FEW_LINES = 20

        private const val READ_LIMIT_MS = 10_000L

        /** Dumps the process's lines and exits, with each line's date, time, thread and level. */
        internal fun command(pid: Int) = listOf("logcat", "-d", "-v", "threadtime", "--pid=$pid", "-t", "$MAX_LINES")

        /** The last lines of [input], each masked, within [MAX_LINES] and [MAX_BYTES]; what was read if it breaks off. */
        internal fun keep(input: InputStream): List<String> {
            val kept = Tail()
            try {
                input.bufferedReader().useLines { lines -> lines.forEach(kept::add) }
            } catch (e: IOException) {
                Log.w(TAG, "logcat stopped answering", e)
            }
            return kept.lines.toList()
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
