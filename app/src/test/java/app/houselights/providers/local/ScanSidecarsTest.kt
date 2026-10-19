package app.houselights.providers.local

import app.houselights.providers.local.FakeRemoteBrowser.Companion.T1
import app.houselights.providers.local.FakeRemoteBrowser.Companion.T2
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

/** Sidecars, keyed by role, path, size and date ([SidecarKey]), are read once and again only when changed. */
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

    /** Neither an unparsable NFO, an empty poster, nor a Jellyfin folder.jpg is read again at each pass. */
    @Test
    fun sidecarsReadOnceAreNotReadAgain() = runTest {
        nas.put("Bugonia.mkv")
        nas.put("Colony.mkv")
        nas.putText("Colony.nfo", "<movie><title>Colony</title><year>2021</year></movie>", lastModified = T1)
        nas.putImage("Colony-poster.jpg", size = 0, lastModified = T1)
        val episode = "Mobland/S01/Mobland.S01E01.MULTi.1080p.WEB.H265-GRP.mkv"
        bench.showsNas.put(episode)
        bench.showsNas.putText(episode.replace(".mkv", ".nfo"), "<episodedetails><title>Pilot</title></episodedetails>", lastModified = T1)
        bench.showsNas.putImage("Mobland/S01/folder.jpg", lastModified = T1)
        tmdb.movie(1, "Colony", 2021)
        tmdb.show(247718, "MobLand", 2025)
        tmdb.season(247718, 1, episodes = 10)
        bench.scan()
        assertEquals("Pilot", bench.item(shows, episode).title)
        nas.putText("Bugonia.nfo", "   -=[ GRP ]=-\n  Bugonia.2025.MULTi.1080p.WEB.H265\n  Size .....: 4.21 GB\n", lastModified = T1)
        assertEquals("a new sidecar is read", listOf("Bugonia.nfo"), bench.pass().reads)
        bench.assertQuiet()
    }

    /** tinyMediaManager rewrites movie.nfo in place, same size, new date: only the NFO is read again, no TMDB. */
    @Test
    fun anNfoRewrittenInPlaceIsReadAgainWithoutTheVideoNorTmdb() = runTest {
        tmdb.movie(361743, "Top Gun: Maverick", 2022, collection = TmdbCollectionRef(531241, "Top Gun - Saga"))
        tmdb.collections[531241] = TmdbCollection(531241, "Top Gun - Saga")
        fun nfo(title: String) = "<movie><title>$title</title><uniqueid type=\"tmdb\">361743</uniqueid>" +
            "<set><name>Top Gun Collection</name></set></movie>"
        nas.put("Top Gun Maverick/Top Gun Maverick.mkv")
        nas.putText("Top Gun Maverick/movie.nfo", nfo("Top Gun: Maverick"), lastModified = T1)
        bench.scan()
        val first = bench.item(films, "Top Gun Maverick/Top Gun Maverick.mkv")
        assertEquals("tmdb:531241", bench.dao.collection(checkNotNull(first.collectionId))?.matchKey)

        nas.putText("Top Gun Maverick/movie.nfo", nfo("TOP GUN: MAVERICK"), lastModified = T2)
        val pass = bench.pass()
        assertEquals(listOf("Top Gun Maverick/movie.nfo"), pass.reads)
        assertEquals(emptyList<String>(), pass.requests)
        assertTrue("screens told", pass.changes > 0)
        val read = bench.item(films, "Top Gun Maverick/Top Gun Maverick.mkv")
        assertEquals("TOP GUN: MAVERICK", read.title)
        assertEquals("still in its saga", first.collectionId, read.collectionId)
        assertEquals("read once", emptyList<String>(), bench.pass().reads)
    }

    /** The NFO rewritten to name another film (a match corrected in a media manager): TMDB is asked for it. */
    @Test
    fun anNfoNamingAnotherFilmIsAskedOfTmdb() = runTest {
        tmdb.movie(1, "Disclosure", 1994)
        tmdb.movie(2, "Disclosure Day", 2026)
        nas.put("Disclosure Day.mkv")
        nas.putText("Disclosure Day.nfo", "<movie><uniqueid type=\"tmdb\">1</uniqueid></movie>", lastModified = T1)
        bench.scan()
        assertEquals(1, bench.item(films, "Disclosure Day.mkv").tmdbId)

        nas.putText("Disclosure Day.nfo", "<movie><uniqueid type=\"tmdb\">2</uniqueid></movie>", lastModified = T2)
        val requests = bench.pass().requests
        assertTrue(requests.toString(), "movie/2" in requests)
        assertEquals(2, bench.item(films, "Disclosure Day.mkv").tmdbId)
    }

    @Test
    fun aFanartOrALogoRemovedIsNoLongerShown() = runTest {
        tmdb.movie(438631, "Dune", 2021)
        tmdb.editMovie(438631) { it.copy(backdropPath = "/dune-backdrop.jpg") }
        nas.put("Dune (2021).mkv")
        nas.putImage("Dune (2021)-fanart.jpg", lastModified = T1)
        nas.putImage("Dune (2021)-clearlogo.png", lastModified = T1)
        bench.scan()
        val dune = bench.item(films, "Dune (2021).mkv")
        assertTrue(dune.backdropPath.isCachedArtwork() && dune.logoPath.isCachedArtwork())

        nas.remove("Dune (2021)-fanart.jpg")
        bench.scan()
        val withoutFanart = bench.item(films, "Dune (2021).mkv")
        assertEquals("/dune-backdrop.jpg", withoutFanart.backdropPath)
        assertTrue("its logo is still beside it", withoutFanart.logoPath.isCachedArtwork())
        assertEquals("Dune", withoutFanart.title)

        nas.remove("Dune (2021)-clearlogo.png")
        bench.scan()
        val withoutLogo = bench.item(films, "Dune (2021).mkv")
        assertEquals("TMDB has none", null, withoutLogo.logoPath)
        assertEquals("/dune-backdrop.jpg", withoutLogo.backdropPath)
    }

    /** Films detached by version 10 from keyless sagas find theirs through the NFO's set, even without TMDB. */
    @Test
    fun filmsOutOfAMergedSagaFindTheirOwnFromTheirNfo() = runTest {
        val sets = mapOf("Ghost in the Shell (1995).mkv" to "攻殻機動隊", "Akira (1988).mkv" to "アキラ")
        tmdb.movie(9323, "Ghost in the Shell", 1995)
        tmdb.movie(149, "Akira", 1988)
        for ((video, set) in sets) {
            nas.put(video)
            nas.putText(video.replace(".mkv", ".nfo"), "<movie><set><name>$set</name></set></movie>", lastModified = T1)
        }
        bench.scan()
        for (video in sets.keys) bench.item(films, video).let { bench.dao.saveScanned(it.copy(collectionId = null, sidecarKey = null)) }

        assertEquals(emptyList<String>(), bench.pass().requests)
        val keys = sets.keys.map { bench.dao.collection(checkNotNull(bench.item(films, it).collectionId))?.matchKey }
        assertEquals(sets.values.map { "name:$it" }, keys)
    }

    @Test
    fun anEpisodeNfoRewrittenInPlaceIsReadAgainWithoutTmdb() = runTest {
        tmdb.show(247718, "MobLand", 2025)
        tmdb.season(247718, 1, episodes = 10)
        val episode = "Mobland/S01/Mobland.S01E02.MULTi.1080p.WEB.H265-GRP.mkv"
        val nfo = episode.replace(".mkv", ".nfo")
        bench.showsNas.put(episode)
        bench.showsNas.putText(nfo, "<episodedetails><title>Hostages</title></episodedetails>", lastModified = T1)
        bench.scan()
        assertEquals("Hostages", bench.item(shows, episode).title)

        bench.showsNas.putText(nfo, "<episodedetails><title>HOSTAGES</title></episodedetails>", lastModified = T2)
        val pass = bench.pass()
        assertEquals(listOf(nfo), pass.reads)
        assertEquals(emptyList<String>(), pass.requests)
        assertEquals("HOSTAGES", bench.item(shows, episode).title)
    }
}
