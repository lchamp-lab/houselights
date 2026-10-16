package app.houselights.providers.local

import app.houselights.ui.util.UiError
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.io.IOException
import java.time.Duration

/**
 * TMDB is optional to a scan: out of reach or refusing the key, files are indexed from their NFO and
 * local artwork, new episodes still come in, and what TMDB did not answer is asked again later.
 */
@RunWith(RobolectricTestRunner::class)
class ScanTmdbTest {
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

    private fun nfo(title: String) = "<movie><title>$title</title></movie>".toByteArray()

    /** A film described by tinyMediaManager: its NFO and its poster beside it. */
    private fun described(name: String, title: String) {
        nas.put("$name.mkv")
        nas.put("$name.nfo", size = nfo(title).size.toLong(), bytes = nfo(title))
        nas.put("$name-poster.jpg", size = 100, bytes = ByteArray(100))
    }

    /** TMDB back, and an hour gone: the scans' pause is over, and what TMDB left unmatched is asked for again. */
    private fun tmdbBack() {
        tmdb.failure = null
        tmdb.status = null
        tmdb.failingPath = null
        ShadowSystemClock.advanceBy(Duration.ofHours(1))
    }

    private fun issue() = bench.scanner.state.value.tmdbIssue

    @Test
    fun tmdbOutOfReachLeavesFilmsToTheirNfoAndArtwork() = runTest {
        val films = bench.folder("Films")
        repeat(5) { described("Film $it (2020)", "Mon film $it") }
        tmdb.movie(1, "Mon film 0", 2020)
        tmdb.failure = IOException("Network is unreachable")
        bench.scan()

        val first = bench.item(films, "Film 0 (2020).mkv")
        assertEquals("Mon film 0", first.title)
        assertTrue(first.posterPath.isCachedArtwork())
        assertNull("asked again at the next scan", first.matchedAt)
        assertEquals(5, bench.dao.filesOf(films.id).size)
        assertEquals(UiError.TMDB_OFFLINE, issue())

        tmdbBack()
        bench.scan()
        val matched = bench.item(films, "Film 0 (2020).mkv")
        assertEquals(1, matched.tmdbId)
        assertNotNull(matched.matchedAt)
        assertNull("TMDB answered", issue())
    }

    @Test
    fun aKeyRefusedEndsTmdbForThePassAndIsSaid() = runTest {
        val films = bench.folder("Films")
        repeat(4) { nas.put("Film $it (2020).mkv") }
        tmdb.status = 401
        bench.scan()

        assertEquals("asked once", 1, tmdb.requests.size)
        assertEquals(UiError.TMDB_KEY_REFUSED, issue())
        assertEquals(4, bench.dao.filesOf(films.id).size)
        assertTrue(bench.dao.filesOf(films.id).all { it.matchedAt == null })
    }

    @Test
    fun titlesARefusedKeyLeftUnmatchedAreNotReadAgainAtTheNextPass() = runTest {
        bench.folder("Films")
        repeat(3) { described("Film $it (2020)", "Mon film $it") }
        tmdb.status = 401
        bench.scan()
        assertEquals(UiError.TMDB_KEY_REFUSED, issue())

        nas.reads.clear()
        tmdb.requests.clear()
        val changes = bench.changesDuring { bench.scan() }
        assertEquals("no NFO read", emptyList<String>(), nas.reads)
        assertEquals("TMDB not asked", emptyList<String>(), tmdb.requests)
        assertEquals("screens left as they are", 0, changes)
        assertEquals("still said", UiError.TMDB_KEY_REFUSED, issue())
    }

    @Test
    fun titlesLeftUnmatchedAreAskedForAgainAnHourLater() = runTest {
        val films = bench.folder("Films")
        repeat(3) { described("Film $it (2020)", "Mon film $it") }
        tmdb.movie(1, "Mon film 0", 2020)
        tmdb.status = 401
        bench.scan()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(59))
        bench.scan()
        assertEquals("asked at the first pass only", 1, tmdb.requests.size)

        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        nas.reads.clear()
        tmdb.requests.clear()
        bench.scan()
        assertEquals("asked again, and refused", 1, tmdb.requests.size)
        assertEquals("the other titles are not read once TMDB refused", 1, nas.reads.count { it.endsWith(".nfo") })

        tmdbBack()
        bench.scan()
        assertEquals(1, bench.item(films, "Film 0 (2020).mkv").tmdbId)
        assertNull(issue())
    }

    @Test
    fun aNewKeyIsAskedAtOnceAndWhatTmdbSaidOfTheOldOneIsForgotten() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(438631, "Dune", 2021)
        nas.put("Dune (2021).mkv")
        tmdb.status = 401
        bench.scan()
        assertEquals(UiError.TMDB_KEY_REFUSED, issue())

        // A key TMDB accepts, saved in the settings: no hour to wait.
        tmdb.status = null
        bench.scanner.tmdbKeyChanged()
        assertNull("not said of the new key", issue())
        bench.awaitScans()
        assertEquals(438631, bench.item(films, "Dune (2021).mkv").tmdbId)
        assertNull(issue())
    }

    @Test
    fun aNewEpisodeOfAShowKnownComesInWithoutTmdb() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(95396, "Severance", 2022)
        tmdb.season(95396, 1, episodes = 3)
        bench.showsNas.put("Severance/Season 1/Severance - S01E01.mkv")
        bench.scan()
        assertEquals("Episode 1.1", bench.item(shows, "Severance/Season 1/Severance - S01E01.mkv").title)

        bench.showsNas.put("Severance/Season 1/Severance - S01E02.mkv")
        tmdb.failure = IOException("Network is unreachable")
        bench.scan()
        val new = bench.item(shows, "Severance/Season 1/Severance - S01E02.mkv")
        assertNull(new.matchedAt)

        tmdbBack()
        bench.scan()
        assertEquals("Episode 1.2", bench.item(shows, "Severance/Season 1/Severance - S01E02.mkv").title)
    }

    @Test
    fun aShowFirstScannedWithoutTmdbGetsItsDetailsOnceTmdbAnswers() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(95396, "Severance", 2022)
        tmdb.season(95396, 1, episodes = 3)
        repeat(3) { bench.showsNas.put("Severance/Season 1/Severance - S01E0${it + 1}.mkv") }
        tmdb.failure = IOException("timeout")
        bench.scan()
        assertEquals(3, bench.dao.filesOf(shows.id).size)

        tmdbBack()
        bench.scan()
        val titles = bench.dao.filesOf(shows.id).sortedBy { it.episodeNumber }.map { it.title }
        assertEquals(listOf("Episode 1.1", "Episode 1.2", "Episode 1.3"), titles)
        val series = checkNotNull(bench.dao.seriesByKey(FilenameParser.showKey("Severance")))
        assertEquals(95396, series.tmdbId)
        assertEquals("Season 1", bench.dao.season(series.id, 1)?.title)
    }

    @Test
    fun aShowSavedFromItsPosterAloneIsLookedUpOnceThereIsAKey() = runTest {
        bench.withTmdb = false
        bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(95396, "Severance", 2022)
        bench.showsNas.put("Severance/poster.jpg", size = 100, bytes = ByteArray(100))
        bench.showsNas.put("Severance/Season 1/Severance - S01E01.mkv")
        bench.scan()
        val unmatched = checkNotNull(bench.dao.seriesByKey(FilenameParser.showKey("Severance")))
        assertTrue(unmatched.posterPath.isCachedArtwork())
        assertNull(unmatched.matchedAt)

        bench.withTmdb = true
        bench.scan()
        assertEquals(95396, bench.dao.seriesByKey(FilenameParser.showKey("Severance"))?.tmdbId)
    }

    @Test
    fun aRefreshTmdbDidNotAnswerIsAskedAgainLater() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(438631, "Dune", 2021)
        nas.put("Dune (2021).mkv")
        nas.put("Heat (1995).mkv")
        bench.scan()
        assertNotNull(bench.item(films, "Dune (2021).mkv").matchedAt)

        // TMDB's details changed; the refresh asked for them finds TMDB out of reach.
        tmdb.movies[438631] = checkNotNull(tmdb.movies[438631]).copy(overview = "Arrakis")
        tmdb.failure = IOException("Network is unreachable")
        bench.scan(refreshMetadata = true)
        assertNull("asked again at the next scan", bench.item(films, "Dune (2021).mkv").matchedAt)
        assertNull("one TMDB had nothing for, too", bench.item(films, "Heat (1995).mkv").matchedAt)
        assertEquals("what it had stays meanwhile", 438631, bench.item(films, "Dune (2021).mkv").tmdbId)

        tmdbBack()
        bench.scan()
        val refreshed = bench.item(films, "Dune (2021).mkv")
        assertNotNull(refreshed.matchedAt)
        assertEquals("Arrakis", refreshed.overview)
    }

    @Test
    fun aRefreshWithoutAKeyIsAskedOnceThereIsOne() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(438631, "Dune", 2021)
        nas.put("Dune (2021).mkv")
        bench.scan()

        tmdb.movies[438631] = checkNotNull(tmdb.movies[438631]).copy(overview = "Arrakis")
        bench.withTmdb = false
        bench.scan(refreshMetadata = true)
        assertNull(bench.item(films, "Dune (2021).mkv").matchedAt)

        bench.withTmdb = true
        bench.scan()
        assertEquals("Arrakis", bench.item(films, "Dune (2021).mkv").overview)
    }

    @Test
    fun aShowRefreshTmdbDidNotAnswerIsAskedAgainLater() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(95396, "Severance", 2022)
        tmdb.season(95396, 1, episodes = 2)
        val pilot = "Severance/Season 1/Severance - S01E01.mkv"
        bench.showsNas.put(pilot)
        bench.scan()

        tmdb.shows[95396] = checkNotNull(tmdb.shows[95396]).copy(overview = "Lumon")
        tmdb.failure = IOException("Network is unreachable")
        bench.scan(refreshMetadata = true)
        val series = checkNotNull(bench.dao.seriesByKey(FilenameParser.showKey("Severance")))
        assertNull(series.matchedAt)
        assertNull(bench.item(shows, pilot).matchedAt)

        tmdbBack()
        bench.scan()
        val refreshed = checkNotNull(bench.dao.seriesByKey(FilenameParser.showKey("Severance")))
        assertNotNull(refreshed.matchedAt)
        assertEquals("Lumon", refreshed.overview)
        assertNotNull(bench.item(shows, pilot).matchedAt)
    }

    @Test
    fun aCollectionTmdbDidNotAnswerIsAskedAgain() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(438631, "Dune", 2021, collection = TmdbCollectionRef(726871, "Dune Collection"))
        tmdb.collections[726871] = TmdbCollection(726871, "Dune Collection", overview = "Arrakis")
        nas.put("Dune (2021).mkv")
        tmdb.failingPath = "collection/"
        bench.scan()
        val dune = bench.item(films, "Dune (2021).mkv")
        assertEquals("the film's details stay", 438631, dune.tmdbId)
        assertNull("asked again at the next scan", dune.matchedAt)
        assertNull(bench.dao.collection(checkNotNull(dune.collectionId))?.fetchedAt)

        tmdbBack()
        bench.scan()
        val again = bench.item(films, "Dune (2021).mkv")
        assertNotNull(again.matchedAt)
        assertEquals("Arrakis", bench.dao.collection(checkNotNull(again.collectionId))?.overview)
    }
}
