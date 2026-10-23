package app.houselights.journal

import android.os.Process
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The journal reads the app's lines, by its uid whatever the process that wrote them, the last of them within its
 * bounds, each in logcat's threadtime form and masked before it is kept.
 */
@RunWith(RobolectricTestRunner::class)
class AppLogTest {
    private val me = Process.myUid()
    private val other = me + 1

    @Test
    fun itAsksLogcatForEachLinesUidNotForOneProcess() {
        var asked: List<String>? = null
        runBlocking {
            AppLog(run = { command ->
                asked = command
                FakeProcess("")
            }).read()
        }
        assertEquals(listOf("logcat", "-d", "-v", "threadtime", "-v", "uid", "-t", "${AppLog.MAX_LINES}"), asked)
    }

    /** The crash of a process the app had before this one is kept; another uid's lines and logcat's dividers are not. */
    @Test
    fun theAppsLinesAreKeptWhateverTheirProcessACrashIncluded() {
        val lines = read(
            listOf(
                "--------- beginning of crash",
                "$TIME $me  4321  4350 E AndroidRuntime: FATAL EXCEPTION: main",
                "$TIME $me  4321  4350 E AndroidRuntime: Process: app.houselights, PID: 4321",
                "--------- beginning of main",
                "$TIME $other  4400  4400 I Other   : Started",
                "$TIME  root   123   123 I init    : Started",
                "$TIME  1000  1856  1870 I GnssLocationProvider: Fix",
                "$TIME $other $me  4401 W Other   : Its process has the app's uid as number",
                "$TIME ${me}0  4402  4402 I Other   : Its uid starts as the app's",
                "--------- beginning of system",
                "$TIME $me  5678  5678 I Scan    : Pass done",
                "$TIME $me  5678  5678 I Scan    : Film\u2028Name.mkv",
            ),
        )
        assertEquals(
            listOf(
                "$TIME  4321  4350 E AndroidRuntime: FATAL EXCEPTION: main",
                "$TIME  4321  4350 E AndroidRuntime: Process: app.houselights, PID: 4321",
                "$TIME  5678  5678 I Scan    : Pass done",
                "$TIME  5678  5678 I Scan    : Film\u2028Name.mkv",
            ),
            lines,
        )
    }

    @Test
    fun eachLineIsMaskedBeforeItIsKept() {
        val lines = read(
            listOf(
                "$TIME $me  4321  4350 I Playback: Launch -> smb://zidoo:p4ss@nas/film.mkv",
                "$TIME $other  4400  4400 I Other   : smb://guest:s3cret@nas/elsewhere.mkv",
                "$TIME $me  4321  4350 D Jellyfin: GET /Items?api_key=0123abcd",
            ),
        )
        assertEquals(
            listOf(
                "$TIME  4321  4350 I Playback: Launch -> smb://•••@nas/film.mkv",
                "$TIME  4321  4350 D Jellyfin: GET /Items?api_key=•••",
            ),
            lines,
        )
        listOf("p4ss", "0123abcd", "s3cret").forEach { assertFalse(it, lines.joinToString().contains(it)) }
    }

    /** Another uid's lines count for nothing in the bounds. */
    @Test
    fun onlyTheAppsLastLinesAreKept() {
        val printed = (1..AppLog.MAX_LINES + 500).flatMap {
            listOf("$TIME $me  4321  4350 I Scan    : line $it", "$TIME $other  4400  4400 I Other   : $it")
        }
        val lines = read(printed)
        assertEquals(AppLog.MAX_LINES, lines.size)
        assertEquals("$TIME  4321  4350 I Scan    : line 501", lines.first())
        assertEquals("$TIME  4321  4350 I Scan    : line ${AppLog.MAX_LINES + 500}", lines.last())
    }

    @Test
    fun theLinesKeptHoldWithinTheirSize() {
        val long = "x".repeat(10_000)
        val lines = read((1..200).map { "$TIME $me  4321  4350 I Scan    : $it $long" })
        assertTrue(lines.sumOf { it.toByteArray().size + 1 } <= AppLog.MAX_BYTES)
        assertTrue(lines.size in 90..100)
        assertTrue(lines.last().contains(": 200 "))
    }

    @Test
    fun whatWasReadIsKeptWhenLogcatBreaksOff() {
        val broken = object : InputStream() {
            private val start = ByteArrayInputStream("$TIME $me  4321  4350 I Scan    : Pass done\n".toByteArray())

            override fun read(): Int = start.read().takeIf { it >= 0 } ?: throw IOException("Gone")
        }
        val lines = runBlocking { AppLog(run = { FakeProcess(broken) }).read() }
        assertEquals(listOf("$TIME  4321  4350 I Scan    : Pass done"), lines)
    }

    @Test
    fun noLinesWhenLogcatCannotStart() {
        val lines = runBlocking { AppLog(run = { throw IOException("No logcat") }).read() }
        assertTrue(lines.isEmpty())
    }

    /** What the journal keeps of [printed], logcat's lines. */
    private fun read(printed: List<String>) =
        runBlocking { AppLog(run = { _ -> FakeProcess(printed.joinToString("") { "$it\n" }) }).read() }

    private companion object {
        const val TIME = "10-23 10:42:11.123"
    }
}
