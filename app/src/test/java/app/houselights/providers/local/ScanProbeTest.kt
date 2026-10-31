package app.houselights.providers.local

import app.houselights.core.MediaInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A newer probe ([MediaProbe.VERSION]) rereads file headers only, not NFOs or TMDB, keeping the user's data. */
@RunWith(RobolectricTestRunner::class)
class ScanProbeTest {
    @get:Rule
    val scan = ScanBenchRule()

    private val bench get() = scan.bench
    private val nas get() = bench.nas
    private val tmdb get() = bench.tmdb

    /** The library as the previous probe version left it; [read] is what it made of each file. */
    private suspend fun readByAnOlderProbe(read: (MediaInfo) -> MediaInfo = { it }) = readBy(MediaProbe.VERSION - 1, read)

    /** The library as probe [version] left it; [read] is what it made of each file. */
    private suspend fun readBy(version: Int, read: (MediaInfo) -> MediaInfo = { it }) {
        for (source in bench.dao.sources()) {
            for (file in bench.dao.knownFiles(source.id)) {
                val row = checkNotNull(bench.dao.item(file.id))
                val info = checkNotNull(MediaProbe.decode(row.mediaInfo)) { "${row.filePath} never read" }
                bench.dao.saveScanned(row.withMedia(read(info).copy(probedWith = version)))
            }
        }
    }

    /** Dune, matched by the user, beside an NFO naming a film by IMDb id: a full read would ask TMDB each time. */
    private suspend fun duneMatchedByTheUser(): SourceEntity {
        val films = bench.folder("Films")
        nas.put("Dune (2021).mkv", size = DUNE_SIZE, bytes = Matroska.sample())
        nas.putText("Dune (2021).nfo", "<movie><title>Dune : Première partie</title><id>tt1160419</id></movie>")
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

        val pass = bench.pass()
        assertEquals("the video alone, as one probe reads it", probed("Dune (2021).mkv", DUNE_SIZE), pass.reads)
        assertEquals(emptyList<String>(), pass.requests)
        assertEquals("read the same: nothing to reload", 0, pass.changes)
        val dune = bench.item(films, "Dune (2021).mkv")
        assertEquals(MediaProbe.VERSION, dune.probeVersion)
        assertEquals(before.copy(mediaInfo = null, probeVersion = 0), dune.copy(mediaInfo = null, probeVersion = 0))
        assertEquals(Triple(438631, true, 600_000L), Triple(dune.userTmdbId, dune.favorite, dune.positionMs))

        assertEquals("read once", emptyList<String>(), bench.pass().reads)
    }

    @Test
    fun aNewerProbeReadsAnEpisodesHeadersAlone() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        val pilot = "Severance/Season 1/Severance - S01E01.mkv"
        tmdb.show(95396, "Severance", 2022)
        tmdb.season(95396, 1, episodes = 2)
        bench.showsNas.put(pilot, size = EPISODE_SIZE, bytes = Matroska.sample())
        val episodeNfo = "<episodedetails><title>Bonne nouvelle</title></episodedetails>"
        bench.showsNas.putText("Severance/Season 1/Severance - S01E01.nfo", episodeNfo)
        bench.showsNas.putText("Severance/tvshow.nfo", "<tvshow><title>Severance</title></tvshow>")
        bench.scan()
        readByAnOlderProbe()

        val pass = bench.pass()
        assertEquals(0, pass.changes)
        assertEquals(probed(pilot, EPISODE_SIZE), pass.reads)
        assertEquals(emptyList<String>(), pass.requests)
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
        bench.showsNas.put(pilot, size = EPISODE_SIZE, bytes = Matroska.sample())
        val nfo = { title: String -> "<tvshow><title>$title</title><uniqueid type=\"tmdb\">95396</uniqueid></tvshow>" }
        bench.showsNas.putText("Severance/tvshow.nfo", nfo("Severance"))
        bench.scan()
        readByAnOlderProbe()
        bench.showsNas.putText("Severance/tvshow.nfo", nfo("Severance : la série"))

        assertEquals("each once", (probed(pilot, EPISODE_SIZE) + "Severance/tvshow.nfo").sorted(), bench.pass().reads.sorted())
        assertEquals(MediaProbe.VERSION, bench.item(shows, pilot).probeVersion)
        assertEquals("Severance : la série", bench.showOf(shows, pilot).title)
    }

    /** A file the probe cannot open keeps the stream details its NFO gives, as stored in its row. */
    @Test
    fun aFileTheProbeDoesNotOpenKeepsWhatItsNfoSays() = runTest {
        val films = bench.folder("Films")
        nas.put("Heat (1995).avi")
        val streams = "<fileinfo><streamdetails><video><codec>xvid</codec><width>720</width></video>" +
            "<audio><codec>ac3</codec><channels>6</channels></audio></streamdetails></fileinfo>"
        nas.putText("Heat (1995).nfo", "<movie><title>Heat</title>$streams</movie>")
        bench.scan()
        val before = bench.item(films, "Heat (1995).avi")
        assertEquals("MPEG-4", MediaProbe.decode(before.mediaInfo)?.video?.codec)
        readByAnOlderProbe()

        val pass = bench.pass()
        assertEquals(0, pass.changes)
        assertEquals("not even the NFO", emptyList<String>(), pass.reads)
        val heat = bench.item(films, "Heat (1995).avi")
        assertEquals(MediaProbe.decode(before.mediaInfo), MediaProbe.decode(heat.mediaInfo))
        assertEquals(before.badges, heat.badges)
    }

    /** Probe 4, the released one, read the tracks in the file's order already: a library it read is not read again. */
    @Test
    fun aLibraryTheReleasedProbeReadIsNotReadAgainForTheTracksOrder() = runTest {
        bench.folder("Films")
        nas.put("Dune (2021).mkv", size = DUNE_SIZE, bytes = Matroska.sample())
        nas.put("Heat (1995).mkv", size = FILM_SIZE, bytes = Matroska.sample())
        bench.scan()
        readBy(RELEASED_PROBE) { it.copy(tracksInOrder = false) }

        bench.assertQuiet()
    }

    /** Tracks found in the file's order change nothing screens show: no reload while a pass reads them. */
    @Test
    fun tracksFoundInTheFilesOrderReloadNothing() = runTest {
        val films = bench.folder("Films")
        nas.put("Dune (2021).mkv", size = DUNE_SIZE, bytes = Matroska.sample())
        bench.scan()
        readByAnOlderProbe { it.copy(tracksInOrder = false) }

        val pass = bench.pass()
        assertEquals(probed("Dune (2021).mkv", DUNE_SIZE), pass.reads)
        assertEquals(0, pass.changes)
        assertTrue(MediaProbe.decode(bench.item(films, "Dune (2021).mkv").mediaInfo)?.tracksInOrder == true)
    }

    /** A new film is read before the files a newer probe reads again, which may take a whole pass. */
    @Test
    fun aNewFileIsReadBeforeTheFilesReadAgain() = runTest {
        val films = bench.folder("Films")
        nas.put("Alien (1979).mkv", size = FILM_SIZE, bytes = Matroska.sample())
        nas.put("Blade Runner (1982).mkv", size = FILM_SIZE, bytes = Matroska.sample())
        bench.scan()
        readByAnOlderProbe()
        nas.put("Zodiac (2007).mkv", size = FILM_SIZE, bytes = Matroska.sample())

        val reads = bench.pass().reads
        val each = listOf("Zodiac (2007).mkv", "Alien (1979).mkv", "Blade Runner (1982).mkv").flatMap { probed(it, FILM_SIZE) }
        assertEquals(each, reads)
        assertEquals(MediaProbe.VERSION, bench.item(films, "Alien (1979).mkv").probeVersion)
    }

    /** A file the newer probe reads differently (say, VC-1 that is really XviD) is shown anew. */
    @Test
    fun aFileReadOtherwiseIsShownAnew() = runTest {
        val films = bench.folder("Films")
        nas.put("Dune (2021).mkv", size = DUNE_SIZE, bytes = Matroska.sample())
        bench.scan()
        readByAnOlderProbe { it.copy(video = it.video?.copy(codec = "VC-1")) }

        assertTrue(bench.changesDuring { bench.scan() } > 0)
        assertEquals("HEVC", MediaProbe.decode(bench.item(films, "Dune (2021).mkv").mediaInfo)?.video?.codec)
    }

    /** What one probe reads of the Matroska file at [path] of [size]. */
    private suspend fun probed(path: String, size: Long) = oneProbesReads(path, size, Matroska.sample())

    private companion object {
        const val DUNE_SIZE = 80_000_000_000
        const val EPISODE_SIZE = 4_000_000_000
        const val FILM_SIZE = 30_000_000_000

        /** The probe version of the libraries a released app left. */
        const val RELEASED_PROBE = 4
    }
}
