package app.houselights.servers

import app.houselights.core.NfoParser
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.ScanFiles
import app.houselights.providers.local.TextRead
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * NfsServerTest's NAS as unfsd answers by itself (nfs-fragments, tools/test-servers/README.md): each reply above 64 KB
 * in record fragments of 64 KB (RFC 5531, section 11), as NFS servers on libtirpc send them, where a NAS's kernel nfsd
 * sends one. Every test of NfsServerTest and NasServerTest runs here again, a film's tracks probed as a scan probes
 * them among them, whose first read takes 256 KB at once; these add a read of 256 KB, compared with the same bytes read
 * in pieces that each come in one fragment, and an NFO over 64 KB read as a scan reads it. Through nfs-client 1.1.0's
 * own decoder, each of those reads failed with « tcp IO error », and the NAS was taken for unreachable (issue #88).
 */
@RunWith(RobolectricTestRunner::class)
class NfsFragmentsServerTest : NfsServerTest() {
    override val host: String get() = checkNotNull(TestServers.fragmentsHost)

    /** Skipped, as with the servers down, when they are on another machine and nfs-fragments' address there not given. */
    @Before
    fun fragmentsNasKnown() = TestServers.assumeFragmentsHostKnown()

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun aReadOf256KbAtOnceGivesWhatSmallerReadsGive() = runBlocking {
        val root = video()
        val browser = browsers.of(root)
        val atOnce = browser.readAt(root, Samples.DUNE, 0, PROBE_BYTES)
        assertEquals(PROBE_BYTES, atOnce.size)
        val inPieces = (0 until PROBE_BYTES step PIECE_BYTES).map { at ->
            browser.readAt(root, Samples.DUNE, at.toLong(), minOf(PIECE_BYTES, PROBE_BYTES - at))
        }
        assertArrayEquals(inPieces.reduce { read, piece -> read + piece }, atOnce)
    }

    @Test
    fun anNfoOver64KbIsReadAsAScanReadsIt() = runBlocking {
        val films = folder("volume1/media", "Films")
        val browser = browsers.of(films)
        val nfo = browser.list(films).sidecars.single { it.relativePath == Samples.INTERSTELLAR_NFO }
        assertTrue("${nfo.size} bytes", nfo.size > FRAGMENT_BYTES)
        val read = ScanFiles(ArtworkCache(temp.newFolder())) { browser }.readText(films, nfo)
        assertTrue("$read", read is TextRead.Text)
        val info = NfoParser.parse((read as TextRead.Text).text)
        assertEquals("Interstellar" to 2014, info?.title to info?.year)
        assertEquals(Samples.INTERSTELLAR_ACTORS, info?.actors?.size)
        assertEquals("its end read too", "Acteur ${Samples.INTERSTELLAR_ACTORS}", info?.actors?.lastOrNull()?.name)
    }

    private companion object {
        /** What a scan's probe of a film reads at once (MediaProbe). */
        const val PROBE_BYTES = 256 * 1024

        /** The size of unfsd's fragments. */
        const val FRAGMENT_BYTES = 64 * 1024

        /** Reads whose reply, its header with them, comes in one fragment. */
        const val PIECE_BYTES = 60_000
    }
}
