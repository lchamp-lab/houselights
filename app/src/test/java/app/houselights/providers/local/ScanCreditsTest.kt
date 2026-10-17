package app.houselights.providers.local

import app.houselights.core.Job
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
 * What scans write for the people, sagas, copies and editions screens read: credits from TMDB or the NFO, a
 * saga's films from the details TMDB is asked for once, a film's copies as one title; and what titles known
 * before get at later passes, a few a pass, without touching what the user did.
 */
@RunWith(RobolectricTestRunner::class)
class ScanCreditsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private val nas get() = bench.nas
    private val tmdb get() = bench.tmdb
    private val dao get() = bench.dao

    @Before
    fun open() {
        bench = ScanBench(temp.newFolder("artwork"))
    }

    @After
    fun close() = bench.close()

    private fun FakeRemoteBrowser.text(path: String, text: String) =
        text.toByteArray().let { put(path, size = it.size.toLong(), bytes = it) }

    private val duneCredits = TmdbCredits(
        cast = listOf(
            TmdbCastMember("Timothée Chalamet", "Paul Atreides", "/tc.jpg", order = 0, id = 1190668),
            TmdbCastMember("Rebecca Ferguson", "Lady Jessica", order = 1, id = 933238),
        ),
        crew = listOf(
            TmdbCrewMember("Denis Villeneuve", "Director", 137427),
            TmdbCrewMember("Denis Villeneuve", "Screenplay", 137427),
            TmdbCrewMember("Jon Spaihts", "Screenplay", 1018075),
            TmdbCrewMember("Hans Zimmer", "Original Music Composer", 947),
            TmdbCrewMember("Greig Fraser", "Director of Photography", 56816),
            TmdbCrewMember("Joe Walker", "Editor", 5134),
            TmdbCrewMember("Mary Parent", "Producer", 1),
        ),
    )

    private fun dune(collection: TmdbCollectionRef? = null) =
        tmdb.movie(438631, "Dune", 2021, collection = collection).copy(credits = duneCredits, voteAverage = 7.8, voteCount = 12_000)
            .also { tmdb.movies[it.id] = it }

    private fun jobs(itemId: Long) = runBlockingCredits(itemId).groupBy({ it.job }, { it.name })

    private fun runBlockingCredits(itemId: Long) = kotlinx.coroutines.runBlocking { dao.creditsOf(itemId) }

    @Test
    fun aFilmTmdbAnswersHasItsCastAndCrewKeyedByTheirTmdbIds() = runTest {
        val films = bench.folder("Films")
        dune()
        nas.put("Dune (2021).mkv")
        bench.scan()

        val film = bench.item(films, "Dune (2021).mkv")
        assertNotNull(film.creditsAt)
        assertEquals(12_000, film.votes)
        assertEquals(
            mapOf(
                Job.CAST to listOf("Timothée Chalamet", "Rebecca Ferguson"),
                Job.DIRECTOR to listOf("Denis Villeneuve"),
                Job.WRITER to listOf("Denis Villeneuve", "Jon Spaihts"),
                Job.COMPOSER to listOf("Hans Zimmer"),
                Job.CINEMATOGRAPHY to listOf("Greig Fraser"),
                Job.EDITING to listOf("Joe Walker"),
            ),
            jobs(film.id),
        )
        val chalamet = dao.creditsOf(film.id).first { it.job == Job.CAST }
        assertEquals("tmdb:1190668", chalamet.personKey)
        assertEquals("Paul Atreides" to "/tc.jpg", chalamet.character to chalamet.profilePath)
    }

    /** The NFO's cast and direction are the user's media manager's: they stay, with TMDB's ids for the people TMDB names. */
    @Test
    fun theNfosPeopleStayAndTakeTmdbsIds() = runTest {
        val films = bench.folder("Films")
        dune()
        nas.put("Dune (2021).mkv")
        nas.text(
            "Dune (2021).nfo",
            "<movie><title>Dune</title><uniqueid type=\"tmdb\">438631</uniqueid><director>Denis Villeneuve</director>" +
                "<credits>Eric Roth</credits><actor><name>Timothée Chalamet</name><role>Paul</role></actor>" +
                "<actor><name>Zendaya</name><role>Chani</role><tmdbid>505710</tmdbid></actor></movie>",
        )
        bench.scan()

        val film = bench.item(films, "Dune (2021).mkv")
        val credits = dao.creditsOf(film.id)
        val cast = credits.filter { it.job == Job.CAST }.map { it.name to it.personKey }
        assertEquals(listOf("Timothée Chalamet" to "tmdb:1190668", "Zendaya" to "tmdb:505710"), cast)
        assertEquals("TMDB's portrait where the NFO has none", "/tc.jpg", credits.first { it.job == Job.CAST }.profilePath)
        assertEquals(listOf("Eric Roth" to "name:eric roth"), credits.filter { it.job == Job.WRITER }.map { it.name to it.personKey })
        assertEquals(listOf("Hans Zimmer"), credits.filter { it.job == Job.COMPOSER }.map { it.name })
    }

    /** A show's credits are its cast, its creators and its composers; its NFO, without TMDB, gives its cast. */
    @Test
    fun aShowsCreditsAreItsCastCreatorsAndComposers() = runTest {
        bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(1, "Severance", 2022).copy(
            createdBy = listOf(TmdbCreator("Dan Erickson", 2213217)),
            credits = TmdbCredits(
                cast = listOf(TmdbCastMember("Adam Scott", "Mark", order = 0, id = 55536)),
                crew = listOf(
                    TmdbCrewMember("Theodore Shapiro", "Original Music Composer", 7),
                    TmdbCrewMember("Ben Stiller", "Director", 7399),
                ),
            ),
        ).also { tmdb.shows[1] = it }
        tmdb.season(1, 1, 1)
        bench.showsNas.put("Severance (2022)/Season 1/Severance.S01E01.mkv")
        bench.scan()

        val series = checkNotNull(dao.seriesByKey(FilenameParser.showKey("Severance", 2022)))
        assertEquals(
            mapOf(Job.CAST to listOf("Adam Scott"), Job.CREATOR to listOf("Dan Erickson"), Job.COMPOSER to listOf("Theodore Shapiro")),
            jobs(series.id),
        )
    }

    /** A saga's films come with the details TMDB is asked for once a saga, never once a film. */
    @Test
    fun aSagasFilmsComeWithItsDetailsAskedOnce() = runTest {
        val films = bench.folder("Films")
        val saga = TmdbCollectionRef(726871, "Dune - Saga")
        dune(saga)
        tmdb.movie(693134, "Dune Part Two", 2024, collection = saga)
        tmdb.collections[726871] = TmdbCollection(
            726871, "Dune - Saga",
            parts = listOf(
                TmdbSearchResult(693134, title = "Dune Part Two", releaseDate = "2024-02-27"),
                TmdbSearchResult(438631, title = "Dune", releaseDate = "2021-09-15"),
                TmdbSearchResult(1170608, title = "Dune Messiah"),
            ),
        )
        nas.put("Dune (2021).mkv")
        nas.put("Dune Part Two (2024).mkv")
        bench.scan()

        assertEquals(1, tmdb.requests.count { it.startsWith("collection/") })
        val collectionId = checkNotNull(bench.item(films, "Dune (2021).mkv").collectionId)
        val parts = dao.sagaParts(collectionId)
        assertEquals(listOf("Dune", "Dune Part Two", "Dune Messiah"), parts.map { it.part.title })
        assertEquals(listOf(true, true, false), parts.map { it.itemId != null })
        assertEquals(2, dao.collection(collectionId)?.partCount)
    }

    @Test
    fun aFilmsEditionComesFromItsNfoElseItsName() = runTest {
        val films = bench.folder("Films")
        nas.put("Aliens (1986) Special Edition 1080p.mkv")
        nas.put("Blade Runner (1982) {edition-The Final Cut}.mkv")
        nas.put("Amadeus (1984).mkv")
        nas.text("Amadeus (1984).nfo", "<movie><title>Amadeus</title><edition>DIRECTORS_CUT</edition></movie>")
        nas.put("Director's Cut (2016).mkv")
        bench.scan()

        assertEquals("Special Edition", bench.item(films, "Aliens (1986) Special Edition 1080p.mkv").edition)
        assertEquals("Final Cut", bench.item(films, "Blade Runner (1982) {edition-The Final Cut}.mkv").edition)
        assertEquals("Director's Cut", bench.item(films, "Amadeus (1984).mkv").edition)
        assertNull("a film's own title", bench.item(films, "Director's Cut (2016).mkv").edition)
    }

    /** Two files of one film, in two folders, are one title after the scan: the first found holds it. */
    @Test
    fun twoFilesOfOneFilmAreOneTitle() = runTest {
        val films = bench.folder("Films")
        val uhd = FakeRemoteBrowser()
        val films4k = bench.folder("Films 4K", on = uhd)
        dune()
        nas.put("Dune (2021).mkv")
        // A folder found empty keeps its titles, as a NAS's that stopped answering would: it holds another.
        nas.put("Heat (1995).mkv")
        uhd.put("Dune (2021) 2160p.mkv")
        bench.scan()

        val first = bench.item(films, "Dune (2021).mkv")
        val copy = bench.item(films4k, "Dune (2021) 2160p.mkv")
        assertNull(first.versionOf)
        assertEquals(first.id, copy.versionOf)
        assertEquals(listOf(first.id), dao.latest(ItemKind.MOVIE, 10).filter { it.tmdbId == 438631 }.map { it.id })

        // The first goes: the copy is a title of its own again.
        nas.remove("Dune (2021).mkv")
        bench.scan()
        bench.now += MissingFiles.BRIEF_HOLD_MS
        bench.scan()
        assertNull(bench.item(films4k, "Dune (2021) 2160p.mkv").versionOf)
    }

    /** Titles as version 11 left them: matched, their credits never written, nor their sagas' films. */
    private fun asBeforeVersion12() {
        bench.db.openHelper.writableDatabase.execSQL("UPDATE items SET creditsAt = NULL, votes = NULL")
        bench.db.openHelper.writableDatabase.execSQL("DELETE FROM credits")
        bench.db.openHelper.writableDatabase.execSQL("DELETE FROM saga_parts")
        bench.db.openHelper.writableDatabase.execSQL("UPDATE collections SET fetchedAt = NULL")
        nas.reads.clear()
        tmdb.requests.clear()
    }

    @Test
    fun titlesKnownBeforeGetTheirCreditsAFewAPassWithoutTouchingWhatTheUserDid() = runTest {
        val films = bench.folder("Films")
        val count = Backfill.TITLES_PER_PASS + 5
        repeat(count) { i ->
            val film = tmdb.movie(1000 + i, "Film $i", 2000)
            tmdb.movies[film.id] = film.copy(credits = duneCredits, voteAverage = 7.0, voteCount = 300)
            nas.put("Film $i (2000).mkv")
        }
        bench.scan()
        val film = bench.item(films, "Film 0 (2000).mkv")
        dao.setFavorite(film.id, true)
        dao.savePlayback(film.id, positionMs = 600_000, durationMs = 7_000_000, played = false, lastPlayedAt = 5)
        dao.setUserPoster(film.id, "file:/mine.jpg")
        asBeforeVersion12()

        bench.autoScan()
        assertEquals("nothing read on the NAS", emptyList<String>(), nas.reads)
        assertEquals(Backfill.TITLES_PER_PASS, tmdb.requests.count { it.startsWith("movie/") })
        assertEquals(Backfill.TITLES_PER_PASS, dao.filesOf(films.id).count { it.creditsAt != null })
        val backfilled = checkNotNull(dao.item(film.id))
        assertEquals(6, dao.creditsOf(film.id).map { it.job }.distinct().size)
        assertEquals(300, backfilled.votes)
        assertEquals(true to 600_000L, backfilled.favorite to backfilled.positionMs)
        assertEquals(7_000_000L to 5L, backfilled.durationMs to backfilled.lastPlayedAt)
        assertEquals("file:/mine.jpg", backfilled.userPosterPath)

        tmdb.requests.clear()
        bench.autoScan()
        assertEquals(5, tmdb.requests.count { it.startsWith("movie/") })
        assertTrue(dao.filesOf(films.id).all { it.creditsAt != null })

        tmdb.requests.clear()
        bench.autoScan()
        assertEquals("all given theirs, TMDB is asked no more", emptyList<String>(), tmdb.requests)
    }

    @Test
    fun sagasKnownBeforeGetTheirFilmsAtTheNextPass() = runTest {
        val films = bench.folder("Films")
        val saga = TmdbCollectionRef(726871, "Dune - Saga")
        dune(saga)
        val parts = listOf(TmdbSearchResult(438631, title = "Dune", releaseDate = "2021-09-15"))
        tmdb.collections[726871] = TmdbCollection(726871, "Dune - Saga", parts = parts)
        nas.put("Dune (2021).mkv")
        bench.scan()
        asBeforeVersion12()

        bench.autoScan()
        val collectionId = checkNotNull(bench.item(films, "Dune (2021).mkv").collectionId)
        assertEquals(listOf("Dune"), dao.sagaParts(collectionId).map { it.part.title })
        assertNotNull(dao.collection(collectionId)?.fetchedAt)
        assertEquals(1, tmdb.requests.count { it.startsWith("collection/") })
    }

    /** TMDB out of reach: the backfill asks three times, then waits for a later pass; the NFO's titles get theirs all the same. */
    @Test
    fun tmdbOutOfReachLeavesTheBackfillToALaterPass() = runTest {
        val films = bench.folder("Films")
        repeat(5) { i ->
            tmdb.movie(1000 + i, "Film $i", 2000).copy(credits = duneCredits).also { tmdb.movies[it.id] = it }
            nas.put("Film $i (2000).mkv")
        }
        nas.put("Mon film (2001).mkv")
        nas.text("Mon film (2001).nfo", "<movie><title>Mon film</title><director>Moi</director></movie>")
        bench.scan()
        asBeforeVersion12()
        tmdb.failure = IOException("Network is unreachable")

        bench.scan()
        val mine = bench.item(films, "Mon film (2001).mkv")
        assertEquals(listOf("Moi"), dao.creditsOf(mine.id).map { it.name })
        assertTrue(dao.filesOf(films.id).filter { it.tmdbId != null }.all { it.creditsAt == null })

        tmdb.failure = null
        ShadowSystemClock.advanceBy(Duration.ofHours(1))
        bench.scan()
        assertTrue(dao.filesOf(films.id).all { it.creditsAt != null })
    }
}
