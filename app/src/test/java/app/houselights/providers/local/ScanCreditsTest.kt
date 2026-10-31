package app.houselights.providers.local

import app.houselights.core.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What scans write for the people, saga, copy and edition screens, and the backfill of known titles, a few
 * per pass, that leaves the user's changes alone.
 */
@RunWith(RobolectricTestRunner::class)
class ScanCreditsTest {
    @get:Rule
    val scan = ScanBenchRule()

    private val bench get() = scan.bench
    private val nas get() = bench.nas
    private val tmdb get() = bench.tmdb
    private val dao get() = bench.dao

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

    private fun dune(collection: TmdbCollectionRef? = null) {
        tmdb.movie(438631, "Dune", 2021, collection = collection)
        tmdb.editMovie(438631) { it.copy(credits = duneCredits, voteAverage = 7.8, voteCount = 12_000) }
    }

    private suspend fun jobs(itemId: Long) = dao.creditsOf(itemId).groupBy({ it.job }, { it.name })

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

    /** The NFO's cast and direction come from the user's media manager: they stay, and take TMDB's ids. */
    @Test
    fun theNfosPeopleStayAndTakeTmdbsIds() = runTest {
        val films = bench.folder("Films")
        dune()
        nas.put("Dune (2021).mkv")
        nas.putText(
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

    /** An NFO read again without asking TMDB replaces what it lists, never what TMDB alone credits. */
    @Test
    fun anNfoReadAgainKeepsWhatTmdbAloneCredits() = runTest {
        val films = bench.folder("Films")
        dune()
        nas.put("Dune (2021).mkv")
        nas.putText("Dune (2021).nfo", "<movie><title>Dune</title><uniqueid type=\"tmdb\">438631</uniqueid></movie>")
        bench.scan()
        // tinyMediaManager rewrites the NFO, for the same film, with its cast.
        val withCast = "<movie><title>Dune</title><uniqueid type=\"tmdb\">438631</uniqueid><actor><name>Zendaya</name></actor></movie>"
        nas.putText("Dune (2021).nfo", withCast)
        tmdb.requests.clear()
        bench.scan()

        assertEquals("TMDB not asked", emptyList<String>(), tmdb.requests.filter { it.startsWith("movie/") })
        val credits = jobs(bench.item(films, "Dune (2021).mkv").id)
        assertEquals(listOf("Zendaya"), credits[Job.CAST])
        assertEquals(listOf("Hans Zimmer"), credits[Job.COMPOSER])
        assertEquals(listOf("Joe Walker"), credits[Job.EDITING])
    }

    /** A show's credits are its cast, creators and composers; without TMDB, its NFO gives its cast. */
    @Test
    fun aShowsCreditsAreItsCastCreatorsAndComposers() = runTest {
        bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(1, "Severance", 2022)
        tmdb.editShow(1) {
            it.copy(
                createdBy = listOf(TmdbCreator("Dan Erickson", 2213217)),
                credits = TmdbCredits(
                    cast = listOf(TmdbCastMember("Adam Scott", "Mark", order = 0, id = 55536)),
                    crew = listOf(
                        TmdbCrewMember("Theodore Shapiro", "Original Music Composer", 7),
                        TmdbCrewMember("Ben Stiller", "Director", 7399),
                    ),
                ),
            )
        }
        tmdb.season(1, 1, 1)
        bench.showsNas.put("Severance (2022)/Season 1/Severance.S01E01.mkv")
        bench.scan()

        val series = bench.series("Severance", 2022)
        assertEquals(
            mapOf(Job.CAST to listOf("Adam Scott"), Job.CREATOR to listOf("Dan Erickson"), Job.COMPOSER to listOf("Theodore Shapiro")),
            jobs(series.id),
        )
    }

    /** Writing the credits fails at once (the device full as they are written), as a trigger stands in for here. */
    private suspend fun creditsRefusedDuring(block: suspend () -> Unit) {
        val db = bench.db.openHelper.writableDatabase
        db.execSQL("CREATE TRIGGER refused BEFORE INSERT ON credits BEGIN SELECT RAISE(ABORT, 'refused'); END")
        try {
            block()
        } finally {
            db.execSQL("DROP TRIGGER refused")
        }
    }

    /** A film is never saved as matched without its credits, which no later pass would then write. */
    @Test
    fun aFilmWhoseCreditsCouldNotBeWrittenIsReadAgain() = runTest {
        val films = bench.folder("Films")
        dune(TmdbCollectionRef(726871, "Dune - Saga"))
        nas.put("Dune (2021).mkv")
        creditsRefusedDuring { bench.scan() }
        assertEquals("nothing half saved", emptyList<ItemEntity>(), dao.filesOf(films.id).filter { it.matchedAt != null })
        assertEquals("nor its saga", null, dao.collectionByKey(CollectionRules.key(726871, "Dune - Saga")))

        bench.scan()
        val film = bench.item(films, "Dune (2021).mkv")
        assertEquals(listOf("Timothée Chalamet", "Rebecca Ferguson"), jobs(film.id)[Job.CAST])
        assertNotNull(film.collectionId)
    }

    /** A show saved while TMDB was out of reach, matched once it answers: never without its credits. */
    @Test
    fun aShowWhoseCreditsCouldNotBeWrittenIsLookedUpAgain() = runTest {
        bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(1, "Severance", 2022)
        tmdb.editShow(1) { it.copy(credits = TmdbCredits(cast = listOf(TmdbCastMember("Adam Scott", "Mark", order = 0, id = 55536)))) }
        tmdb.season(1, 1, 1)
        bench.showsNas.put("Severance (2022)/Season 1/Severance.S01E01.mkv")
        bench.tmdbOutOfReach()
        bench.scan()
        assertNull(bench.series("Severance", 2022).matchedAt)

        bench.tmdbBack()
        creditsRefusedDuring { bench.scan() }
        assertNull("not matched without its credits", bench.series("Severance", 2022).matchedAt)

        bench.scan()
        assertEquals(listOf("Adam Scott"), jobs(bench.series("Severance", 2022).id)[Job.CAST])
    }

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
        nas.putText("Amadeus (1984).nfo", "<movie><title>Amadeus</title><edition>DIRECTORS_CUT</edition></movie>")
        nas.put("Director's Cut (2016).mkv")
        bench.scan()

        assertEquals("Special Edition", bench.item(films, "Aliens (1986) Special Edition 1080p.mkv").edition)
        assertEquals("Final Cut", bench.item(films, "Blade Runner (1982) {edition-The Final Cut}.mkv").edition)
        assertEquals("Director's Cut", bench.item(films, "Amadeus (1984).mkv").edition)
        assertNull("a film's own title", bench.item(films, "Director's Cut (2016).mkv").edition)
    }

    @Test
    fun twoFilesOfOneFilmAreOneTitle() = runTest {
        val films = bench.folder("Films")
        val uhd = FakeRemoteBrowser()
        val films4k = bench.folder("Films 4K", on = uhd)
        dune()
        nas.put("Dune (2021).mkv")
        // A second film: a folder listed empty keeps its titles, as when its NAS stops answering.
        nas.put("Heat (1995).mkv")
        uhd.put("Dune (2021) 2160p.mkv")
        bench.scan()

        val first = bench.item(films, "Dune (2021).mkv")
        val copy = bench.item(films4k, "Dune (2021) 2160p.mkv")
        assertNull(first.versionOf)
        assertEquals(first.id, copy.versionOf)
        assertEquals(listOf(first.id), dao.latest(ItemKind.MOVIE, 10).filter { it.tmdbId == 438631 }.map { it.id })

        // The first file goes: the copy is a title of its own again.
        nas.remove("Dune (2021).mkv")
        bench.scan()
        assertNull(bench.item(films4k, "Dune (2021) 2160p.mkv").versionOf)
    }

    /** Titles as version 11 left them: matched, without credits or their sagas' films. */
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
            tmdb.movie(1000 + i, "Film $i", 2000)
            tmdb.editMovie(1000 + i) { it.copy(credits = duneCredits, voteAverage = 7.0, voteCount = 300) }
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

    /** TMDB out of reach: the backfill waits for a pass TMDB answers; NFO titles still get theirs. */
    @Test
    fun tmdbOutOfReachLeavesTheBackfillToALaterPass() = runTest {
        val films = bench.folder("Films")
        repeat(5) { i ->
            tmdb.movie(1000 + i, "Film $i", 2000)
            tmdb.editMovie(1000 + i) { it.copy(credits = duneCredits) }
            nas.put("Film $i (2000).mkv")
        }
        nas.put("Mon film (2001).mkv")
        nas.putText("Mon film (2001).nfo", "<movie><title>Mon film</title><director>Moi</director></movie>")
        bench.scan()
        asBeforeVersion12()
        bench.tmdbOutOfReach()

        bench.scan()
        val mine = bench.item(films, "Mon film (2001).mkv")
        assertEquals(listOf("Moi"), dao.creditsOf(mine.id).map { it.name })
        assertTrue(dao.filesOf(films.id).filter { it.tmdbId != null }.all { it.creditsAt == null })

        bench.tmdbBack()
        bench.scan()
        assertTrue(dao.filesOf(films.id).all { it.creditsAt != null })
    }
}
