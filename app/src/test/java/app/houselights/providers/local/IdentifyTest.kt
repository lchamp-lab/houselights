package app.houselights.providers.local

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** An identification asks TMDB everything first, then writes everything: never half a new match. */
@RunWith(RobolectricTestRunner::class)
class IdentifyTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private val tmdb get() = bench.tmdb

    @Before
    fun open() {
        bench = ScanBench(temp.newFolder("artwork"))
    }

    @After
    fun close() = bench.close()

    private suspend fun identifyFails(itemId: Long, tmdbId: Int) {
        try {
            bench.scanner.identify(itemId, tmdbId)
        } catch (_: TmdbUnreachableException) {
            return
        }
        fail("TMDB out of reach should throw")
    }

    @Test
    fun aShowIsAskedWholeBeforeAnythingChanges() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(1, "Severance", 2022)
        tmdb.season(1, 1, episodes = 2)
        tmdb.season(1, 2, episodes = 2)
        bench.showsNas.put("Severance/Season 1/Severance - S01E01.mkv")
        bench.showsNas.put("Severance/Season 2/Severance - S02E01.mkv")
        bench.scan()
        val series = checkNotNull(bench.dao.seriesByKey(FilenameParser.showKey("Severance")))
        assertEquals(1, series.tmdbId)

        // Another show of the name, whose second season TMDB does not answer.
        tmdb.show(2, "Severance (UK)", 2024)
        tmdb.seasons[2 to 1] = TmdbSeason(1, name = "Series 1", episodes = listOf(TmdbEpisode(1, name = "Pilot")))
        tmdb.seasons[2 to 2] = TmdbSeason(2, name = "Series 2", episodes = listOf(TmdbEpisode(1, name = "Return")))
        tmdb.failingPath = "tv/2/season/2"
        identifyFails(series.id, 2)

        val kept = checkNotNull(bench.dao.item(series.id))
        assertNull(kept.userTmdbId)
        assertEquals(1, kept.tmdbId)
        assertEquals("Severance", kept.title)
        assertEquals("Season 1", bench.dao.season(series.id, 1)?.title)
        assertEquals("Episode 1.1", bench.item(shows, "Severance/Season 1/Severance - S01E01.mkv").title)

        tmdb.failingPath = null
        bench.scanner.identify(series.id, 2)
        val matched = checkNotNull(bench.dao.item(series.id))
        assertEquals(2, matched.userTmdbId)
        assertEquals("Severance (UK)", matched.title)
        assertEquals("Series 2", bench.dao.season(series.id, 2)?.title)
        assertEquals("Pilot", bench.item(shows, "Severance/Season 1/Severance - S01E01.mkv").title)
        assertEquals("Return", bench.item(shows, "Severance/Season 2/Severance - S02E01.mkv").title)
    }

    @Test
    fun aFilmWhoseCollectionCannotBeAskedStaysAsItWas() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(1, "Dune", 2021)
        bench.nas.put("Dune (2021).mkv")
        bench.scan()
        val dune = bench.item(films, "Dune (2021).mkv")
        assertEquals(1, dune.tmdbId)

        tmdb.movie(2, "Dune: Part Two", 2024, collection = TmdbCollectionRef(726871, "Dune Collection"))
        tmdb.collections[726871] = TmdbCollection(726871, "Dune Collection", overview = "Arrakis")
        tmdb.failingPath = "collection/"
        identifyFails(dune.id, 2)
        val kept = checkNotNull(bench.dao.item(dune.id))
        assertNull(kept.userTmdbId)
        assertEquals(1, kept.tmdbId)
        assertEquals("Dune", kept.title)

        tmdb.failingPath = null
        bench.scanner.identify(dune.id, 2)
        val matched = checkNotNull(bench.dao.item(dune.id))
        assertEquals(2, matched.userTmdbId)
        assertEquals("Dune: Part Two", matched.title)
        assertNotNull(bench.dao.collection(checkNotNull(matched.collectionId))?.fetchedAt)
    }
}
