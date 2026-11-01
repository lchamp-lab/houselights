package app.houselights.providers.local

import android.util.Log
import app.houselights.core.NfoParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.IOException

/**
 * A failed read is retried at the next scan, keeping what was read before; a silent NAS ends the pass. What fails at
 * every pass is logged whole once, then counted.
 */
@RunWith(RobolectricTestRunner::class)
class ScanReadsTest {
    @get:Rule
    val scan = ScanBenchRule(tmdbKey = false)

    private val bench get() = scan.bench
    private lateinit var films: SourceEntity
    private val nas get() = bench.nas

    @Before
    fun open() = runTest {
        films = bench.folder("Films")
    }

    /** A lock: the file is being written, and is read again later. */
    private fun locked() = IOException("STATUS_SHARING_VIOLATION (0xc0000043)")

    /** A refusal, as smbj words it: final for the file until its permissions change. */
    private fun refused() = IOException("STATUS_ACCESS_DENIED (0xc0000022): Create failed")

    private fun filmWithItsNfo() {
        nas.put("Dune (2021).mkv")
        nas.putText("Dune (2021).nfo", "<movie><title>Dune: Part One</title><year>2021</year></movie>")
        nas.putImage("Dune (2021)-poster.jpg")
    }

    /** Warnings of [tag]: each line, and whether it carries its stack. */
    private fun warnings(tag: String) = ShadowLog.getLogsForTag(tag).filter { it.type == Log.WARN }.map { it.msg to (it.throwable != null) }

    /** An NFO locked at every pass (a media manager holding it open): logged whole once, then counted per pass. */
    @Test
    fun anNfoLockedAtEveryPassIsLoggedWholeOnce() = runTest {
        filmWithItsNfo()
        nas.onRead = { path, _, _ -> if (path.endsWith(".nfo")) throw locked() }
        ShadowLog.clear()
        repeat(3) { bench.scan() }
        assertEquals(
            listOf("Could not read Dune (2021).nfo; it is read again at the next scan" to true) +
                List(2) { "Films: files unreadable again, each logged before: 1" to false },
            warnings("ScanFiles"),
        )
    }

    /** A file whose processing fails the same way at every pass, as a row the database refuses: one stack, then counts. */
    @Test
    fun aFileFailingAtEveryPassIsLoggedWholeOnce() = runTest {
        nas.put("Dune (2021).mkv")
        val db = bench.db.openHelper.writableDatabase
        db.execSQL("CREATE TRIGGER refused BEFORE INSERT ON items BEGIN SELECT RAISE(ABORT, 'refused'); END")
        ShadowLog.clear()
        repeat(3) { bench.scan() }
        assertEquals(
            listOf("Failed on Dune (2021).mkv" to true) +
                List(2) { "Films: files failing again, each logged at an earlier pass: 1" to false },
            warnings("LibraryScanner").filter { "Dune" in it.first || "again" in it.first },
        )
    }

    @Test
    fun aVideoThatCouldNotBeReadIsReadAgain() = runTest {
        nas.put("Dune (2021).mkv", size = 80_000_000_000, bytes = Matroska.sample())
        nas.onRead = { _, _, _ -> throw locked() }
        bench.scan()
        val first = bench.item(films, "Dune (2021).mkv")
        assertEquals("MKV", MediaProbe.decode(first.mediaInfo)?.container)
        assertTrue("not kept as the file's description", MediaProbe.isStale(first.mediaInfo))

        nas.onRead = null
        assertEquals(listOf("Dune (2021).mkv"), bench.pass().reads)
        assertEquals(Matroska.SAMPLE_BADGES, bench.item(films, "Dune (2021).mkv").badges)
    }

    @Test
    fun aVideoReadKeepsItsProbeAndItsLengthBesideWhatItHolds() = runTest {
        nas.put("Dune (2021).mkv", size = 80_000_000_000, bytes = Matroska.sample())
        bench.scan()
        val dune = bench.item(films, "Dune (2021).mkv")
        assertEquals(MediaProbe.VERSION, MediaProbe.decode(dune.mediaInfo)?.probedWith)
        assertEquals(MediaProbe.VERSION to 7_094_000L, dune.probeVersion to dune.fileDurationMs)
    }

    @Test
    fun anNfoIsReadInTheEncodingItSays() = runTest {
        val declaration = """<?xml version="1.0" encoding="ISO-8859-1"?>"""
        val nfo = "$declaration<movie><title>Amélie</title><plot>Une serveuse à Montmartre.</plot></movie>"
        nas.put("Amélie (2001).mkv")
        nas.putText("Amélie (2001).nfo", nfo, charset = Charsets.ISO_8859_1)
        bench.scan()
        val amelie = bench.item(films, "Amélie (2001).mkv")
        assertEquals("Amélie", amelie.title)
        assertEquals("Une serveuse à Montmartre.", amelie.overview)
        assertEquals("kept as text, read again as it is", "Amélie", bench.dao.nfoText(amelie.id)?.let(NfoParser::parse)?.title)
    }

    @Test
    fun aVideoTheNasRefusesIsNotReadAgain() = runTest {
        // Written by a download client under its own account, with permissions the device's account lacks.
        nas.put("Dune (2021).mkv", size = 80_000_000_000, bytes = Matroska.sample())
        nas.onRead = { _, _, _ -> throw refused() }
        bench.scan()
        val dune = bench.item(films, "Dune (2021).mkv")
        assertEquals("MKV", MediaProbe.decode(dune.mediaInfo)?.container)
        assertFalse("the NAS's last word", MediaProbe.isStale(dune.mediaInfo))

        bench.assertQuiet()
    }

    @Test
    fun aVideoRenamedBeforeItIsReadIsReadUnderItsNewName() = runTest {
        // Radarr renames the film between the listing and the read: the read finds it gone.
        nas.put("Dune.2021.mkv", size = 80_000_000_000, bytes = Matroska.sample())
        nas.onRead = { path, _, _ -> if (path == "Dune.2021.mkv") nas.rename(path, "Dune (2021).mkv") }
        bench.scan()
        assertTrue("not the file's last word", MediaProbe.isStale(bench.item(films, "Dune.2021.mkv").mediaInfo))

        // The row follows the file, of the same size, and the file is read at its new path.
        nas.onRead = null
        bench.scan()
        assertEquals(Matroska.SAMPLE_BADGES, bench.item(films, "Dune (2021).mkv").badges)
    }

    @Test
    fun anNfoTheNasRefusesIsTriedAgainWithoutTellingTheScreens() = runTest {
        filmWithItsNfo()
        nas.onRead = { path, _, _ -> if (path.endsWith(".nfo")) throw refused() }
        bench.scan()

        val pass = bench.pass()
        assertEquals("nothing written, nothing to reload", 0, pass.changes)
        assertEquals("tried again", listOf("Dune (2021).nfo"), pass.reads)

        // The permission is restored on the NAS.
        nas.onRead = null
        bench.scan()
        assertEquals("Dune: Part One", bench.item(films, "Dune (2021).mkv").title)
    }

    @Test
    fun aReadFailingPastTheTracksKeepsThem() = runTest {
        // Tracks in the first read, their first frames further in, read next.
        nas.put("Dune (2021).mkv", size = 80_000_000_000, bytes = Matroska.sample() + ByteArray(Matroska.FIRST_READ))
        nas.onRead = { _, _, length -> if (length > Matroska.FIRST_READ) throw locked() }
        bench.scan()

        val dune = bench.item(films, "Dune (2021).mkv")
        assertEquals("2 reads", 2, nas.reads.size)
        assertTrue(dune.badges.toString(), "4K" in dune.badges)
        assertTrue("read again at the next scan", MediaProbe.isStale(dune.mediaInfo))
    }

    @Test
    fun anNfoThatCouldNotBeReadIsReadAgain() = runTest {
        filmWithItsNfo()
        nas.onRead = { path, _, _ -> if (path.endsWith(".nfo")) throw locked() }
        bench.scan()
        val first = bench.item(films, "Dune (2021).mkv")
        assertNull("no NFO said read without its text", first.nfoPath)
        assertEquals("Dune", first.title)

        nas.onRead = null
        bench.scan()
        val read = bench.item(films, "Dune (2021).mkv")
        assertEquals("Dune (2021).nfo", read.nfoPath)
        assertEquals("Dune: Part One", read.title)
    }

    /**
     * A pass reading nothing of a film, then one reading its header again alone, probed by an older version
     * ([MediaProbe.VERSION]), its sidecars unchanged and not read, keep the NFO text read before.
     */
    @Test
    fun aPassNotReadingTheSidecarsAgainKeepsTheNfoText() = runTest {
        filmWithItsNfo()
        nas.put("Dune (2021).mkv", size = 42, bytes = Matroska.sample())
        bench.scan()
        val id = bench.item(films, "Dune (2021).mkv").id
        val text = bench.dao.nfoText(id)
        assertTrue("$text", text.orEmpty().contains("Dune: Part One"))

        assertEquals(emptyList<String>(), bench.pass().reads)
        assertEquals(text, bench.dao.nfoText(id))

        bench.db.openHelper.writableDatabase.execSQL("UPDATE items SET probeVersion = 0 WHERE id = $id")
        assertEquals("its header alone", listOf("Dune (2021).mkv"), bench.pass().reads)
        val dune = bench.item(films, "Dune (2021).mkv")
        assertEquals(MediaProbe.VERSION, dune.probeVersion)
        assertEquals(text, bench.dao.nfoText(id))
        assertEquals("Dune: Part One", dune.title)
    }

    /** A film's NFO rewritten is kept as it now reads; removed, its text goes with it, the film staying. */
    @Test
    fun anNfoRewrittenOrRemovedIsKeptAsItNowIs() = runTest {
        filmWithItsNfo()
        bench.scan()
        val id = bench.item(films, "Dune (2021).mkv").id
        val rewritten = "<movie><title>Dune : Première partie</title><year>2021</year></movie>"
        nas.putText("Dune (2021).nfo", rewritten)
        bench.scan()
        assertEquals(rewritten, bench.dao.nfoText(id))

        nas.remove("Dune (2021).nfo")
        bench.scan()
        assertNull(bench.dao.nfoText(id))
        assertEquals(id, bench.item(films, "Dune (2021).mkv").id)
    }

    @Test
    fun anNfoReadBeforeKeepsItsTextWhenReadingItAgainFails() = runTest {
        filmWithItsNfo()
        bench.scan()

        nas.onRead = { path, _, _ -> if (path.endsWith(".nfo")) throw locked() }
        bench.scan(refreshMetadata = true)
        val dune = bench.item(films, "Dune (2021).mkv")
        assertEquals("Dune (2021).nfo", dune.nfoPath)
        assertTrue(bench.dao.nfoText(dune.id).orEmpty().contains("Dune: Part One"))
        assertEquals("Dune: Part One", dune.title)
    }
}
