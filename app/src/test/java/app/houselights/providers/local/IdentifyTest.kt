package app.houselights.providers.local

import app.houselights.core.FailureKind
import app.houselights.core.TmdbUnreachableException
import app.houselights.core.failureKind
import app.houselights.core.suspendRunCatching
import app.houselights.providers.local.FakeRemoteBrowser.Companion.T1
import app.houselights.providers.local.FakeRemoteBrowser.Companion.T2
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

/**
 * An identification fetches everything from TMDB first, then writes everything: never half a new match. The user's
 * match then outlasts the NFO beside the film or show, read again, rewritten, or while TMDB does not answer.
 */
@RunWith(RobolectricTestRunner::class)
class IdentifyTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private lateinit var films: SourceEntity
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
        val series = bench.series("Severance")
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

    @Test
    fun aResultTmdbNoLongerHasIsTmdbsNotTheLibrarys() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(1, "Dune", 2021)
        bench.nas.put("Dune (2021).mkv")
        bench.scan()
        val dune = bench.item(films, "Dune (2021).mkv")

        val failure = suspendRunCatching { bench.scanner.identify(dune.id, 2) }.exceptionOrNull()
        assertEquals(FailureKind.TMDB_NOT_FOUND, failure?.failureKind())
        val kept = checkNotNull(bench.dao.item(dune.id))
        assertNull(kept.userTmdbId)
        assertEquals(1, kept.tmdbId)
    }

    /** The Office (UK) as Sonarr describes it (TVDB and IMDb ids, no TMDB id), beside TMDB's American namesake. */
    private suspend fun theOffice(): ItemEntity {
        bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(OFFICE_UK, "The Office (UK)", 2001)
        tmdb.editShow(OFFICE_UK) { it.copy(externalIds = TmdbExternalIds("tt0290978", 78107)) }
        tmdb.show(OFFICE_US, "The Office", 2005)
        tmdb.editShow(OFFICE_US) { it.copy(externalIds = TmdbExternalIds("tt0386676", 73244)) }
        val nfo = """
            <?xml version="1.0" encoding="utf-8" standalone="yes"?>
            <tvshow>
              <title>The Office (UK)</title>
              <plot>Slough, a paper merchant's office and its manager.</plot>
              <id>78107</id>
              <uniqueid type="tvdb" default="true">78107</uniqueid>
              <uniqueid type="imdb">tt0290978</uniqueid>
              <genre>Comedy</genre>
              <actor><name>Ricky Gervais</name><role>David Brent</role></actor>
            </tvshow>
        """.trimIndent()
        bench.showsNas.putText("The Office/tvshow.nfo", nfo)
        bench.showsNas.put("The Office/S01/The.Office.S01E01.MULTi.1080p.WEB.H264-GRP.mkv")
        bench.scan()
        return bench.series("The Office")
    }

    /** Disclosure Day beside an NFO naming Disclosure by its IMDb id, matched by the user with Disclosure Day. */
    private suspend fun disclosureDay(nfo: String): ItemEntity {
        films = bench.folder("Films")
        tmdb.movie(DISCLOSURE, "Disclosure", 1994, imdbId = "tt0109635")
        tmdb.movie(DISCLOSURE_DAY, "Disclosure Day", 2026, imdbId = "tt31227572")
        bench.nas.put(FILM)
        bench.nas.putText("Disclosure Day.nfo", nfo, lastModified = T1)
        bench.scan()
        val film = bench.item(films, FILM)
        assertEquals(DISCLOSURE, film.tmdbId)
        bench.scanner.identify(film.id, DISCLOSURE_DAY)
        return bench.item(films, FILM)
    }

    /** Read again, the NFO names by IMDb id a film only TMDB tells apart from the user's match. */
    @Test
    fun aFilmIdentifiedAsAnotherKeepsItWhenItsNfoIsReadAgain() = runTest {
        val nfo = "<movie><title>Disclosure</title><plot>Seattle, 1994.</plot><id>tt0109635</id></movie>"
        assertEquals("Disclosure Day", disclosureDay(nfo).title)

        bench.nas.putText("Disclosure Day.nfo", nfo, lastModified = T2)
        bench.scan()
        val read = bench.item(films, FILM)
        assertEquals("Disclosure Day", read.title)
        assertEquals("About Disclosure Day", read.overview)
        assertEquals(DISCLOSURE_DAY, read.tmdbId)
    }

    @Test
    fun aShowIdentifiedAsAnotherLeavesItsNfoAside() = runTest {
        val series = theOffice()
        assertEquals("The Office (UK)", series.title)

        bench.scanner.identify(series.id, OFFICE_US)
        val matched = checkNotNull(bench.dao.item(series.id))
        assertEquals("The Office", matched.title)
        assertEquals("About The Office", matched.overview)
        assertEquals("the NFO's cast goes too", emptyList<String>(), bench.dao.cast(series.id).map { it.name })

        // Likewise at every later refresh.
        bench.scan(refreshMetadata = true)
        assertEquals("The Office", bench.dao.item(series.id)?.title)
    }

    @Test
    fun aShowIdentifiedAsTheOneItsNfoNamesKeepsIt() = runTest {
        val series = theOffice()
        bench.scanner.identify(series.id, OFFICE_UK)
        val matched = checkNotNull(bench.dao.item(series.id))
        assertEquals(OFFICE_UK, matched.tmdbId)
        assertEquals("Slough, a paper merchant's office and its manager.", matched.overview)
        assertEquals(listOf("Ricky Gervais"), bench.dao.cast(series.id).map { it.name })
    }

    @Test
    fun aFilmIdentifiedAsAnotherKeepsItWhileTmdbDoesNotAnswer() = runTest {
        val film = disclosureDay(
            "<movie><title>Disclosure</title><plot>Seattle, 1994.</plot><id>tt0109635</id><actor><name>Demi Moore</name></actor></movie>",
        )

        tmdb.failingPath = "movie/$DISCLOSURE_DAY"
        bench.scan(refreshMetadata = true)
        val waiting = bench.item(films, FILM)
        assertEquals("Disclosure Day", waiting.title)
        assertEquals("About Disclosure Day", waiting.overview)
        assertEquals(emptyList<String>(), bench.dao.cast(film.id).map { it.name })
        assertNull("asked again at a later pass", waiting.matchedAt)
    }

    @Test
    fun aShowIdentifiedAsAnotherKeepsItWhileTmdbDoesNotAnswer() = runTest {
        val series = theOffice()
        bench.scanner.identify(series.id, OFFICE_US)

        tmdb.failingPath = "tv/$OFFICE_US"
        bench.scan(refreshMetadata = true)
        val waiting = checkNotNull(bench.dao.item(series.id))
        assertEquals("The Office", waiting.title)
        assertEquals("About The Office", waiting.overview)
        assertEquals(emptyList<String>(), bench.dao.cast(series.id).map { it.name })
        assertNull("asked again at a later pass", waiting.matchedAt)
    }

    @Test
    fun aFilmNfoRewrittenWhileTmdbIsOutOfReachIsDecidedOnceItAnswers() = runTest {
        disclosureDay("<movie><title>Disclosure</title><id>tt0109635</id></movie>")

        // Its media manager, now matching the user's choice, rewrites it with Disclosure Day's IMDb id.
        bench.tmdbOutOfReach()
        val rewritten = "<movie><title>Disclosure Day</title><plot>Director's cut.</plot><id>tt31227572</id></movie>"
        bench.nas.putText("Disclosure Day.nfo", rewritten, lastModified = T2)
        bench.scan()
        assertEquals("waits for TMDB", "About Disclosure Day", bench.item(films, FILM).overview)

        bench.tmdbBack()
        bench.scan()
        val read = bench.item(films, FILM)
        assertEquals("Director's cut.", read.overview)
        assertEquals(DISCLOSURE_DAY, read.tmdbId)
        assertEquals("read once", emptyList<String>(), bench.pass().reads)
    }

    @Test
    fun aShowNfoRewrittenWhileTmdbIsOutOfReachIsDecidedOnceItAnswers() = runTest {
        val series = theOffice()
        bench.scanner.identify(series.id, OFFICE_US)

        bench.tmdbOutOfReach()
        val nfo = """
            <tvshow>
              <title>The Office (US)</title>
              <plot>Scranton, a paper company's branch and its manager.</plot>
              <uniqueid type="tvdb" default="true">73244</uniqueid>
              <uniqueid type="imdb">tt0386676</uniqueid>
            </tvshow>
        """.trimIndent()
        bench.showsNas.putText("The Office/tvshow.nfo", nfo, lastModified = T2)
        bench.scan()
        assertEquals("waits for TMDB", "About The Office", bench.dao.item(series.id)?.overview)

        bench.tmdbBack()
        bench.scan()
        val read = checkNotNull(bench.dao.item(series.id))
        assertEquals("Scranton, a paper company's branch and its manager.", read.overview)
        assertEquals(OFFICE_US, read.tmdbId)
    }

    private companion object {
        const val OFFICE_UK = 2996
        const val OFFICE_US = 2316
        const val DISCLOSURE = 1
        const val DISCLOSURE_DAY = 2
        const val FILM = "Disclosure Day.mkv"
    }
}
