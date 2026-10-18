package app.houselights.providers.local

import app.houselights.core.MediaInfo
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A newer probe ([MediaProbe.VERSION]) rereads file headers only, not NFOs or TMDB, keeping the user's data. */
@RunWith(RobolectricTestRunner::class)
class ScanProbeTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private val nas get() = bench.nas
    private val tmdb get() = bench.tmdb

    @Before
    fun open() {
        bench = ScanBench(temp.newFolder("artwork"))
    }

    @After
    fun close() = bench.close()

    private fun put(on: FakeRemoteBrowser, path: String, text: String) {
        val bytes = text.toByteArray()
        on.put(path, size = bytes.size.toLong(), bytes = bytes)
    }

    /** The library as the previous probe version left it; [read] is what it made of each file. */
    private suspend fun readByAnOlderProbe(read: (MediaInfo) -> MediaInfo = { it }) {
        for (source in bench.dao.sources()) {
            for (file in bench.dao.knownFiles(source.id)) {
                val row = checkNotNull(bench.dao.item(file.id))
                val info = checkNotNull(MediaProbe.decode(row.mediaInfo)) { "${row.filePath} never read" }
                bench.dao.saveScanned(row.withMedia(read(info).copy(probedWith = MediaProbe.VERSION - 1)))
            }
        }
    }

    /** Dune, matched by the user, beside an NFO naming a film by IMDb id: a full read would ask TMDB each time. */
    private suspend fun duneMatchedByTheUser(): SourceEntity {
        val films = bench.folder("Films")
        nas.put("Dune (2021).mkv", size = 80_000_000_000, bytes = MkvProbeTest().sampleFile())
        put(nas, "Dune (2021).nfo", "<movie><title>Dune : Première partie</title><id>tt1160419</id></movie>")
        tmdb.movie(438631, "Dune", 2021, imdbId = "tt1160419")
        bench.scan()
        val dune = bench.item(films, "Dune (2021).mkv")
        bench.scanner.identify(dune.id, 438631)
        bench.dao.setFavorite(dune.id, true)
        bench.dao.savePlayback(dune.id, positionMs = 600_000, durationMs = 7_094_000, played = false, lastPlayedAt = 5)
        // Let the match's writes settle.
        bench.scan()
        return films
    }

    @Test
    fun aNewerProbeReadsAFilmsHeadersAloneNeitherItsNfoNorTmdb() = runTest {
        val films = duneMatchedByTheUser()
        val before = bench.item(films, "Dune (2021).mkv")
        readByAnOlderProbe()
        nas.reads.clear()
        tmdb.requests.clear()

        val changes = bench.changesDuring { bench.scan() }
        assertEquals("the video alone", setOf("Dune (2021).mkv"), nas.reads.toSet())
        assertEquals(emptyList<String>(), tmdb.requests.toList())
        assertEquals("read the same: nothing to reload", 0, changes)
        val dune = bench.item(films, "Dune (2021).mkv")
        assertEquals(MediaProbe.VERSION, dune.probeVersion)
        assertEquals(before.copy(mediaInfo = null, probeVersion = 0), dune.copy(mediaInfo = null, probeVersion = 0))
        assertEquals(Triple(438631, true, 600_000L), Triple(dune.userTmdbId, dune.favorite, dune.positionMs))

        nas.reads.clear()
        bench.scan()
        assertEquals("read once", emptyList<String>(), nas.reads.toList())
    }

    @Test
    fun aNewerProbeReadsAnEpisodesHeadersAlone() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        val pilot = "Severance/Season 1/Severance - S01E01.mkv"
        tmdb.show(95396, "Severance", 2022)
        tmdb.season(95396, 1, episodes = 2)
        bench.showsNas.put(pilot, size = 4_000_000_000, bytes = MkvProbeTest().sampleFile())
        put(bench.showsNas, "Severance/Season 1/Severance - S01E01.nfo", "<episodedetails><title>Bonne nouvelle</title></episodedetails>")
        put(bench.showsNas, "Severance/tvshow.nfo", "<tvshow><title>Severance</title></tvshow>")
        bench.scan()
        readByAnOlderProbe()
        bench.showsNas.reads.clear()
        tmdb.requests.clear()

        assertEquals(0, bench.changesDuring { bench.scan() })
        assertEquals(setOf(pilot), bench.showsNas.reads.toSet())
        assertEquals(emptyList<String>(), tmdb.requests.toList())
        val episode = bench.item(shows, pilot)
        assertEquals("Bonne nouvelle" to MediaProbe.VERSION, episode.title to episode.probeVersion)
    }

    /** A probe re-read reads no show, so a show NFO rewritten meanwhile is read in the same pass, once. */
    @Test
    fun aShowsNfoRewrittenIsReadThoughItsEpisodesWaitForANewerProbe() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        val pilot = "Severance/Season 1/Severance - S01E01.mkv"
        tmdb.show(95396, "Severance", 2022)
        tmdb.season(95396, 1, episodes = 2)
        bench.showsNas.put(pilot, size = 4_000_000_000, bytes = MkvProbeTest().sampleFile())
        val nfo = { title: String -> "<tvshow><title>$title</title><uniqueid type=\"tmdb\">95396</uniqueid></tvshow>" }
        put(bench.showsNas, "Severance/tvshow.nfo", nfo("Severance"))
        bench.scan()
        readByAnOlderProbe()
        put(bench.showsNas, "Severance/tvshow.nfo", nfo("Severance : la série"))
        bench.showsNas.reads.clear()

        bench.scan()
        assertEquals("each once", listOf(pilot, "Severance/tvshow.nfo"), bench.showsNas.reads.sorted())
        val episode = bench.item(shows, pilot)
        assertEquals(MediaProbe.VERSION, episode.probeVersion)
        assertEquals("Severance : la série", checkNotNull(bench.dao.item(checkNotNull(episode.seriesId))).title)
    }

    /** A file the probe cannot open keeps the stream details its NFO gives, as stored in its row. */
    @Test
    fun aFileTheProbeDoesNotOpenKeepsWhatItsNfoSays() = runTest {
        val films = bench.folder("Films")
        nas.put("Heat (1995).avi")
        val streams = "<fileinfo><streamdetails><video><codec>xvid</codec><width>720</width></video>" +
            "<audio><codec>ac3</codec><channels>6</channels></audio></streamdetails></fileinfo>"
        put(nas, "Heat (1995).nfo", "<movie><title>Heat</title>$streams</movie>")
        bench.scan()
        val before = bench.item(films, "Heat (1995).avi")
        assertEquals("MPEG-4", MediaProbe.decode(before.mediaInfo)?.video?.codec)
        readByAnOlderProbe()
        nas.reads.clear()

        assertEquals(0, bench.changesDuring { bench.scan() })
        assertEquals("not even the NFO", emptyList<String>(), nas.reads.toList())
        val heat = bench.item(films, "Heat (1995).avi")
        assertEquals(MediaProbe.decode(before.mediaInfo), MediaProbe.decode(heat.mediaInfo))
        assertEquals(before.badges, heat.badges)
    }

    /** A file the newer probe reads differently (say, VC-1 that is really XviD) is shown anew. */
    @Test
    fun aFileReadOtherwiseIsShownAnew() = runTest {
        val films = bench.folder("Films")
        nas.put("Dune (2021).mkv", size = 80_000_000_000, bytes = MkvProbeTest().sampleFile())
        bench.scan()
        readByAnOlderProbe { it.copy(video = it.video?.copy(codec = "VC-1")) }

        assertTrue(bench.changesDuring { bench.scan() } > 0)
        assertEquals("HEVC", MediaProbe.decode(bench.item(films, "Dune (2021).mkv").mediaInfo)?.video?.codec)
    }
}
