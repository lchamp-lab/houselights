package app.houselights.providers.local

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

/**
 * The files beside a video (NFO, artwork), as a scan sees them from one pass to the next: by role, path,
 * size and date ([SidecarKey]). What was read is not read again; what changed, even in place, is.
 */
@RunWith(RobolectricTestRunner::class)
class ScanSidecarsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private lateinit var films: SourceEntity
    private lateinit var shows: SourceEntity
    private val nas get() = bench.nas
    private val tmdb get() = bench.tmdb

    @Before
    fun open() = runTest {
        bench = ScanBench(temp.newFolder("artwork"))
        films = bench.folder("Films")
        shows = bench.folder("Séries", SourceKind.SHOWS)
    }

    @After
    fun close() = bench.close()

    private fun FakeRemoteBrowser.text(path: String, text: String, lastModified: Long) =
        text.toByteArray().let { put(path, size = it.size.toLong(), bytes = it, lastModified = lastModified) }

    /** What the NAS and TMDB are asked, and screens told, during a pass. */
    private suspend fun pass(): Triple<List<String>, List<String>, Int> {
        nas.reads.clear()
        bench.showsNas.reads.clear()
        tmdb.requests.clear()
        val changes = bench.changesDuring { bench.scan() }
        return Triple(nas.reads + bench.showsNas.reads, tmdb.requests.toList(), changes)
    }

    /**
     * Each of these was read again at every pass, ten minutes apart: an NFO no parser reads (a scene
     * release's) beside a film already identified, an empty poster the cache does not hold (beside an NFO,
     * with a TMDB request each time), and an episode alone in its season's folder beside a folder.jpg
     * (Jellyfin), taken for its poster.
     */
    @Test
    fun sidecarsReadOnceAreNotReadAgain() = runTest {
        nas.put("Bugonia.mkv")
        nas.put("Colony.mkv")
        nas.text("Colony.nfo", "<movie><title>Colony</title><year>2021</year></movie>", lastModified = T1)
        nas.put("Colony-poster.jpg", size = 0, lastModified = T1)
        val episode = "Mobland/S01/Mobland.S01E01.MULTi.1080p.WEB.H265-GRP.mkv"
        bench.showsNas.put(episode)
        bench.showsNas.text(episode.replace(".mkv", ".nfo"), "<episodedetails><title>Pilot</title></episodedetails>", lastModified = T1)
        bench.showsNas.put("Mobland/S01/folder.jpg", size = 100, bytes = ByteArray(100), lastModified = T1)
        tmdb.movie(1, "Colony", 2021)
        tmdb.show(247718, "MobLand", 2025)
        tmdb.season(247718, 1, episodes = 10)
        bench.scan()
        assertEquals("Pilot", bench.item(shows, episode).title)
        nas.text("Bugonia.nfo", "   -=[ GRP ]=-\n  Bugonia.2025.MULTi.1080p.WEB.H265\n  Size .....: 4.21 GB\n", lastModified = T1)
        assertEquals("a new sidecar is read", listOf("Bugonia.nfo"), pass().first)

        val (reads, requests, changes) = pass()
        assertEquals("nothing read on the NAS", emptyList<String>(), reads)
        assertEquals("nothing asked of TMDB", emptyList<String>(), requests)
        assertEquals("screens left as they are", 0, changes)
    }

    /**
     * tinyMediaManager rewrites movie.nfo under its name, often to the same size: its date tells. The NFO
     * is read again, not the video; TMDB is not asked, as the NFO names the same film and the same set.
     */
    @Test
    fun anNfoRewrittenInPlaceIsReadAgainWithoutTheVideoNorTmdb() = runTest {
        tmdb.movie(361743, "Top Gun: Maverick", 2022, collection = TmdbCollectionRef(531241, "Top Gun - Saga"))
        tmdb.collections[531241] = TmdbCollection(531241, "Top Gun - Saga")
        fun nfo(title: String) = "<movie><title>$title</title><uniqueid type=\"tmdb\">361743</uniqueid>" +
            "<set><name>Top Gun Collection</name></set></movie>"
        nas.put("Top Gun Maverick/Top Gun Maverick.mkv")
        nas.text("Top Gun Maverick/movie.nfo", nfo("Top Gun: Maverick"), lastModified = T1)
        bench.scan()
        val first = bench.item(films, "Top Gun Maverick/Top Gun Maverick.mkv")
        assertEquals("tmdb:531241", bench.dao.collection(checkNotNull(first.collectionId))?.matchKey)

        nas.text("Top Gun Maverick/movie.nfo", nfo("TOP GUN: MAVERICK"), lastModified = T2)
        val (reads, requests, changes) = pass()
        assertEquals(listOf("Top Gun Maverick/movie.nfo"), reads)
        assertEquals(emptyList<String>(), requests)
        assertTrue("screens told", changes > 0)
        val read = bench.item(films, "Top Gun Maverick/Top Gun Maverick.mkv")
        assertEquals("TOP GUN: MAVERICK", read.title)
        assertEquals("still in its saga", first.collectionId, read.collectionId)
        assertEquals("read once", emptyList<String>(), pass().first)
    }

    /** The NFO rewritten to name another film (a match corrected in a media manager): TMDB is asked for it. */
    @Test
    fun anNfoNamingAnotherFilmIsAskedOfTmdb() = runTest {
        tmdb.movie(1, "Disclosure", 1994)
        tmdb.movie(2, "Disclosure Day", 2026)
        nas.put("Disclosure Day.mkv")
        nas.text("Disclosure Day.nfo", "<movie><uniqueid type=\"tmdb\">1</uniqueid></movie>", lastModified = T1)
        bench.scan()
        assertEquals(1, bench.item(films, "Disclosure Day.mkv").tmdbId)

        nas.text("Disclosure Day.nfo", "<movie><uniqueid type=\"tmdb\">2</uniqueid></movie>", lastModified = T2)
        val (_, requests, _) = pass()
        assertTrue(requests.toString(), "movie/2" in requests)
        assertEquals(2, bench.item(films, "Disclosure Day.mkv").tmdbId)
    }

    /**
     * An NFO naming its film by IMDb id alone, beside a film the user matched with another title: only
     * TMDB's answer tells it describes another film. Read again, it is asked, and its details stay off.
     */
    @Test
    fun anNfoReadAgainStaysOffAFilmTheUserMatchedWithAnother() = runTest {
        tmdb.movie(1, "Disclosure", 1994, imdbId = "tt0109635")
        tmdb.movie(2, "Disclosure Day", 2026, imdbId = "tt31227572")
        val nfo = "<movie><title>Disclosure</title><plot>Seattle, 1994.</plot><id>tt0109635</id></movie>"
        nas.put("Disclosure Day.mkv")
        nas.text("Disclosure Day.nfo", nfo, lastModified = T1)
        bench.scan()
        val film = bench.item(films, "Disclosure Day.mkv")
        assertEquals(1, film.tmdbId)
        bench.scanner.identify(film.id, 2)
        assertEquals("Disclosure Day", bench.item(films, "Disclosure Day.mkv").title)

        nas.text("Disclosure Day.nfo", nfo, lastModified = T2)
        bench.scan()
        val read = bench.item(films, "Disclosure Day.mkv")
        assertEquals("Disclosure Day", read.title)
        assertEquals("About Disclosure Day", read.overview)
        assertEquals(2, read.tmdbId)
    }

    /** Without TMDB's answer, nothing tells whose film the NFO's IMDb id names: the film the user matched keeps what it had meanwhile. */
    @Test
    fun aFilmTheUserMatchedWithAnotherKeepsItWhileTmdbDoesNotAnswer() = runTest {
        tmdb.movie(1, "Disclosure", 1994, imdbId = "tt0109635")
        tmdb.movie(2, "Disclosure Day", 2026, imdbId = "tt31227572")
        nas.put("Disclosure Day.mkv")
        nas.text(
            "Disclosure Day.nfo",
            "<movie><title>Disclosure</title><plot>Seattle, 1994.</plot><id>tt0109635</id><actor><name>Demi Moore</name></actor></movie>",
            lastModified = T1,
        )
        bench.scan()
        val film = bench.item(films, "Disclosure Day.mkv")
        bench.scanner.identify(film.id, 2)

        tmdb.failingPath = "movie/2"
        bench.scan(refreshMetadata = true)
        val waiting = bench.item(films, "Disclosure Day.mkv")
        assertEquals("Disclosure Day", waiting.title)
        assertEquals("About Disclosure Day", waiting.overview)
        assertEquals(emptyList<String>(), bench.dao.cast(film.id).map { it.name })
        assertEquals("asked again at a later pass", null, waiting.matchedAt)
    }

    /**
     * Version 10 takes the films of sagas whose names gave no key out of them, their sidecars to read again:
     * their NFO's set gives each its own, even with TMDB not asked (no key, or the films matched already).
     */
    @Test
    fun filmsOutOfAMergedSagaFindTheirOwnFromTheirNfo() = runTest {
        val sets = mapOf("Ghost in the Shell (1995).mkv" to "攻殻機動隊", "Akira (1988).mkv" to "アキラ")
        tmdb.movie(9323, "Ghost in the Shell", 1995)
        tmdb.movie(149, "Akira", 1988)
        for ((video, set) in sets) {
            nas.put(video)
            nas.text(video.replace(".mkv", ".nfo"), "<movie><set><name>$set</name></set></movie>", lastModified = T1)
        }
        bench.scan()
        for (video in sets.keys) bench.item(films, video).let { bench.dao.saveScanned(it.copy(collectionId = null, sidecarKey = null)) }

        val (_, requests, _) = pass()
        assertEquals(emptyList<String>(), requests)
        val keys = sets.keys.map { bench.dao.collection(checkNotNull(bench.item(films, it).collectionId))?.matchKey }
        assertEquals(sets.values.map { "name:$it" }, keys)
    }

    /** Sonarr rewrites an episode's NFO in place: it is read again, not the video, and TMDB's season is not asked again. */
    @Test
    fun anEpisodeNfoRewrittenInPlaceIsReadAgainWithoutTmdb() = runTest {
        tmdb.show(247718, "MobLand", 2025)
        tmdb.season(247718, 1, episodes = 10)
        val episode = "Mobland/S01/Mobland.S01E02.MULTi.1080p.WEB.H265-GRP.mkv"
        val nfo = episode.replace(".mkv", ".nfo")
        bench.showsNas.put(episode)
        bench.showsNas.text(nfo, "<episodedetails><title>Hostages</title></episodedetails>", lastModified = T1)
        bench.scan()
        assertEquals("Hostages", bench.item(shows, episode).title)

        bench.showsNas.text(nfo, "<episodedetails><title>HOSTAGES</title></episodedetails>", lastModified = T2)
        val (reads, requests, _) = pass()
        assertEquals(listOf(nfo), reads)
        assertEquals(emptyList<String>(), requests)
        assertEquals("HOSTAGES", bench.item(shows, episode).title)
    }

    private companion object {
        /** Two dates a file was written, as SMB or NFS list them. */
        const val T1 = 1_760_000_000_000L
        const val T2 = T1 + 60_000L
    }
}
