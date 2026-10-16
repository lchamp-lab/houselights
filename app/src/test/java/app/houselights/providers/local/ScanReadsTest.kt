package app.houselights.providers.local

import app.houselights.core.NfoParser
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * A read that fails is not the file's last word: what it would have said is read again at the next
 * scan, and what was read before stays. A NAS that stops answering ends the pass, whatever is read.
 */
@RunWith(RobolectricTestRunner::class)
class ScanReadsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private lateinit var films: SourceEntity
    private val nas get() = bench.nas

    @Before
    fun open() = runTest {
        bench = ScanBench(temp.newFolder("artwork"), tmdbKey = false)
        films = bench.folder("Films")
    }

    @After
    fun close() = bench.close()

    /** A lock: the file is being written, and is read again later. */
    private fun locked() = IOException("STATUS_SHARING_VIOLATION (0xc0000043)")

    /** A refusal, as smbj words it: the NAS's last word on the file, until its rights change. */
    private fun refused() = IOException("STATUS_ACCESS_DENIED (0xc0000022): Create failed")

    private fun nfo(title: String) = "<movie><title>$title</title><year>2021</year></movie>".toByteArray()

    private fun filmWithItsNfo() {
        nas.put("Dune (2021).mkv")
        nas.put("Dune (2021).nfo", size = nfo("Dune: Part One").size.toLong(), bytes = nfo("Dune: Part One"))
        nas.put("Dune (2021)-poster.jpg", size = 100, bytes = ByteArray(100))
    }

    @Test
    fun aVideoThatCouldNotBeReadIsReadAgain() = runTest {
        nas.put("Dune (2021).mkv", size = 80_000_000_000, bytes = MkvProbeTest().sampleFile())
        nas.onRead = { _, _, _ -> throw locked() }
        bench.scan()
        val first = bench.item(films, "Dune (2021).mkv")
        assertEquals("MKV", MediaProbe.decode(first.mediaInfo)?.container)
        assertTrue("not kept as the file's description", MediaProbe.isStale(first.mediaInfo))

        nas.onRead = null
        nas.reads.clear()
        bench.scan()
        assertEquals(listOf("Dune (2021).mkv"), nas.reads)
        assertEquals(REMUX_BADGES, bench.item(films, "Dune (2021).mkv").badges)
    }

    /** What a video holds is kept with the probe that read it and its length, in columns the scan and the cards read. */
    @Test
    fun aVideoReadKeepsItsProbeAndItsLengthBesideWhatItHolds() = runTest {
        nas.put("Dune (2021).mkv", size = 80_000_000_000, bytes = MkvProbeTest().sampleFile())
        bench.scan()
        val dune = bench.item(films, "Dune (2021).mkv")
        assertEquals(MediaProbe.VERSION, MediaProbe.decode(dune.mediaInfo)?.probedWith)
        assertEquals(MediaProbe.VERSION to 7_094_000L, dune.probeVersion to dune.fileDurationMs)
    }

    /** An NFO an older media manager wrote in Latin-1 is read as it says, not as UTF-8. */
    @Test
    fun anNfoIsReadInTheEncodingItSays() = runTest {
        val declaration = """<?xml version="1.0" encoding="ISO-8859-1"?>"""
        val nfo = "$declaration<movie><title>Amélie</title><plot>Une serveuse à Montmartre.</plot></movie>".toByteArray(Charsets.ISO_8859_1)
        nas.put("Amélie (2001).mkv")
        nas.put("Amélie (2001).nfo", size = nfo.size.toLong(), bytes = nfo)
        bench.scan()
        val amelie = bench.item(films, "Amélie (2001).mkv")
        assertEquals("Amélie", amelie.title)
        assertEquals("Une serveuse à Montmartre.", amelie.overview)
        assertEquals("kept as text, read again as it is", "Amélie", amelie.nfo?.let(NfoParser::parse)?.title)
    }

    @Test
    fun aVideoTheNasRefusesIsNotReadAgain() = runTest {
        // Written by a download client under its own account, with rights the device's has not.
        nas.put("Dune (2021).mkv", size = 80_000_000_000, bytes = MkvProbeTest().sampleFile())
        nas.onRead = { _, _, _ -> throw refused() }
        bench.scan()
        val dune = bench.item(films, "Dune (2021).mkv")
        assertEquals("MKV", MediaProbe.decode(dune.mediaInfo)?.container)
        assertFalse("the NAS's last word", MediaProbe.isStale(dune.mediaInfo))

        nas.reads.clear()
        assertEquals(0, bench.changesDuring { bench.scan() })
        assertEquals(emptyList<String>(), nas.reads)
    }

    @Test
    fun aVideoRenamedBeforeItIsReadIsReadUnderItsNewName() = runTest {
        // Radarr renames the film between the listing and the read: the read finds it gone.
        nas.put("Dune.2021.mkv", size = 80_000_000_000, bytes = MkvProbeTest().sampleFile())
        nas.onRead = { path, _, _ -> if (path == "Dune.2021.mkv") nas.rename(path, "Dune (2021).mkv") }
        bench.scan()
        assertTrue("not the file's last word", MediaProbe.isStale(bench.item(films, "Dune.2021.mkv").mediaInfo))

        // The row follows the file, of the same size, and the file is read there.
        nas.onRead = null
        bench.scan()
        assertEquals(REMUX_BADGES, bench.item(films, "Dune (2021).mkv").badges)
    }

    @Test
    fun anNfoTheNasRefusesIsTriedAgainWithoutTellingTheScreens() = runTest {
        filmWithItsNfo()
        nas.onRead = { path, _, _ -> if (path.endsWith(".nfo")) throw refused() }
        bench.scan()

        nas.reads.clear()
        assertEquals("nothing written, nothing to reload", 0, bench.changesDuring { bench.scan() })
        assertEquals("tried again", listOf("Dune (2021).nfo"), nas.reads)

        // The right given back on the NAS.
        nas.onRead = null
        bench.scan()
        assertEquals("Dune: Part One", bench.item(films, "Dune (2021).mkv").title)
    }

    @Test
    fun aReadFailingPastTheTracksKeepsThem() = runTest {
        // Tracks in the first read, their first frames further in, read next.
        nas.put("Dune (2021).mkv", size = 80_000_000_000, bytes = MkvProbeTest().sampleFile() + ByteArray(FIRST_READ))
        nas.onRead = { _, _, length -> if (length > FIRST_READ) throw locked() }
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

    @Test
    fun anNfoReadBeforeKeepsItsTextWhenReadingItAgainFails() = runTest {
        filmWithItsNfo()
        bench.scan()

        nas.onRead = { path, _, _ -> if (path.endsWith(".nfo")) throw locked() }
        bench.scan(refreshMetadata = true)
        val dune = bench.item(films, "Dune (2021).mkv")
        assertEquals("Dune (2021).nfo", dune.nfoPath)
        assertTrue(dune.nfo.orEmpty().contains("Dune: Part One"))
        assertEquals("Dune: Part One", dune.title)
    }

    @Test
    fun aNasThatStopsAnsweringWhileImagesAreCopiedEndsThePass() = runTest {
        // Files the probe does not open, without NFO: only their posters are read.
        repeat(5) {
            nas.put("New $it (2024).mp4")
            nas.put("New $it (2024)-poster.jpg", size = 100, bytes = ByteArray(100))
        }
        nas.readFailure = SocketTimeoutException("timeout")
        bench.scan()

        assertEquals(ScanErrorKind.UNREACHABLE, bench.error(films))
        assertEquals("gives up after three files, each with long timeouts", 3, nas.reads.size)
    }

    private companion object {
        val REMUX_BADGES = listOf("4K", "Dolby Vision", "HDR10", "TrueHD Atmos", "7.1")
        const val FIRST_READ = 256 shl 10
    }
}
