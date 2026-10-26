package app.houselights.servers

import app.houselights.core.Codecs
import app.houselights.providers.local.MediaProbe
import app.houselights.providers.local.ProbeResult
import app.houselights.providers.local.RemoteBrowsers
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.SourceEntity
import app.houselights.servers.TestServers.refusal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the shared folder « video » gives the app on a real NAS, through the app's own browsers whatever the protocol:
 * its test, its listing without what the NAS hides, its files read from anywhere and a film's tracks probed as a scan
 * probes them. On Robolectric, for the clock and the log the browsers use.
 */
abstract class NasServerTest {
    protected val browsers = RemoteBrowsers()

    /** A folder of « video » at [path] ("" for its root), as the folder settings save it. */
    protected abstract fun video(path: String = ""): SourceEntity

    @Before
    fun up() = TestServers.assumeUp()

    @After
    fun closeSessions() = browsers.closeIdle()

    @Test
    fun aFolderPassesItsTestCountingWhatItShows() = runBlocking {
        val root = video()
        assertEquals(Samples.ROOT, browsers.of(root).test(root).counted())
        val films = video("Films")
        assertEquals(Samples.FILMS, browsers.of(films).test(films).counted())
    }

    @Test
    fun aListingFindsFilmsAndShowsAndSkipsWhatTheNasHides() = runBlocking {
        val root = video()
        val listing = browsers.of(root).list(root)
        assertEquals(Samples.videos, listing.videos.map { it.relativePath }.toSet())
        assertEquals(Samples.sidecars, listing.sidecars.map { it.relativePath }.toSet())
        assertEquals(emptyList<String>(), listing.unreadable)
        assertTrue("Sizes and dates: ${listing.videos}", listing.videos.all { it.size > 0 && it.lastModified != null })
    }

    @Test
    fun aSubfolderListsFromItself() = runBlocking {
        val shows = video("Séries")
        val listed = browsers.of(shows).list(shows).videos.map { it.relativePath }.toSet()
        assertEquals(Samples.videos.filter { it.startsWith("Séries/") }.map { it.removePrefix("Séries/") }.toSet(), listed)
    }

    @Test
    fun aFileIsReadFromItsStartAndFromWithin() = runBlocking {
        val root = video()
        val browser = browsers.of(root)
        assertEquals("<?xml", String(browser.readPrefix(root, Samples.DUNE_NFO, XML.length), Charsets.UTF_8))
        val head = browser.readPrefix(root, Samples.DUNE, HEAD_BYTES)
        assertArrayEquals("Matroska's EBML header", EBML, head.copyOf(EBML.size))
        val within = browser.readAt(root, Samples.DUNE, WITHIN.toLong(), WITHIN_BYTES)
        assertArrayEquals(head.copyOfRange(WITHIN, WITHIN + WITHIN_BYTES), within)
    }

    @Test
    fun aFilmsTracksAreReadAsAScanReadsThem() = runBlocking {
        val root = video()
        val browser = browsers.of(root)
        val file = browser.list(root).videos.single { it.relativePath == Samples.DUNE }
        val probed = MediaProbe.probe(root, file, browser)
        assertTrue("$probed", probed is ProbeResult.Read)
        val info = probed.info
        assertEquals(Triple(Codecs.HEVC, 1920, 1080), info.video?.let { Triple(it.codec, it.width, it.height) })
        val audio = info.audio.map { Triple(it.codec, it.channels, it.language) }
        assertEquals(listOf(Triple(Codecs.AC3, 6, "eng"), Triple(Codecs.AAC, 2, "fre")), audio)
        assertEquals(listOf(Codecs.SRT to "fre"), info.subtitles.map { it.format to it.language })
    }

    @Test
    fun aSubfolderThatIsNotThereIsNotFound() = runBlocking {
        val (kind, failure) = browsers.refusal(video("Films2"))
        assertEquals(failure, ScanErrorKind.NOT_FOUND, kind)
    }

    private companion object {
        const val XML = "<?xml"
        const val HEAD_BYTES = 4096
        const val WITHIN = 1000
        const val WITHIN_BYTES = 100
        val EBML = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    }
}
