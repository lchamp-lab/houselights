package app.houselights.journal

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

/** The journal reads the app's own lines only, the last of them within its bounds, each masked before it is kept. */
@RunWith(RobolectricTestRunner::class)
class AppLogTest {
    @Test
    fun itAsksLogcatForTheProcessesOwnLinesOnly() {
        var asked: List<String>? = null
        val lines = runBlocking {
            AppLog(pid = 4321, run = { command ->
                asked = command
                FakeProcess("$LINE I Scan    : Pass done\n$LINE W Trakt   : Answer 502\n")
            }).read()
        }
        assertEquals(listOf("logcat", "-d", "-v", "threadtime", "--pid=4321", "-t", "${AppLog.MAX_LINES}"), asked)
        assertEquals(listOf("$LINE I Scan    : Pass done", "$LINE W Trakt   : Answer 502"), lines)
    }

    @Test
    fun eachLineIsMaskedBeforeItIsKept() {
        val lines = AppLog.keep(
            input("$LINE I Playback: Launch -> smb://zidoo:p4ss@nas/film.mkv\n$LINE D Jellyfin: GET /Items?api_key=0123abcd\n"),
        )
        assertEquals(
            listOf("$LINE I Playback: Launch -> smb://•••@nas/film.mkv", "$LINE D Jellyfin: GET /Items?api_key=•••"),
            lines,
        )
        assertFalse(lines.joinToString().contains("p4ss"))
        assertFalse(lines.joinToString().contains("0123abcd"))
    }

    @Test
    fun onlyTheLastLinesAreKept() {
        val lines = AppLog.keep(input((1..AppLog.MAX_LINES + 500).joinToString("\n") { "$LINE I Scan    : line $it" }))
        assertEquals(AppLog.MAX_LINES, lines.size)
        assertEquals("$LINE I Scan    : line 501", lines.first())
        assertEquals("$LINE I Scan    : line ${AppLog.MAX_LINES + 500}", lines.last())
    }

    @Test
    fun theLinesKeptHoldWithinTheirSize() {
        val long = "x".repeat(10_000)
        val lines = AppLog.keep(input((1..200).joinToString("\n") { "$LINE I Scan    : $it $long" }))
        assertTrue(lines.sumOf { it.toByteArray().size + 1 } <= AppLog.MAX_BYTES)
        assertTrue(lines.size in 90..100)
        assertTrue(lines.last().contains(": 200 "))
    }

    @Test
    fun whatWasReadIsKeptWhenLogcatBreaksOff() {
        val broken = object : InputStream() {
            private val start = ByteArrayInputStream("$LINE I Scan    : Pass done\n".toByteArray())

            override fun read(): Int = start.read().takeIf { it >= 0 } ?: throw IOException("Gone")
        }
        assertEquals(listOf("$LINE I Scan    : Pass done"), AppLog.keep(broken))
    }

    @Test
    fun noLinesWhenLogcatCannotStart() {
        val lines = runBlocking { AppLog(pid = 4321, run = { throw IOException("No logcat") }).read() }
        assertTrue(lines.isEmpty())
    }

    private fun input(text: String) = ByteArrayInputStream(text.toByteArray())

    private companion object {
        const val LINE = "10-23 10:42:11.123  4321  4350"
    }
}
