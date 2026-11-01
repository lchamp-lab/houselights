package app.houselights.providers.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A pass asks TMDB for a film's details once whatever its copies ([MovieIndexer.FilmCache]), in one folder or two of
 * hundreds of films, and saves a copy read from what it kept as it saves one TMDB answered for.
 */
@RunWith(RobolectricTestRunner::class)
class FilmDetailsOnceTest {
    @get:Rule
    val scan = ScanBenchRule()

    private val bench get() = scan.bench
    private val nas get() = bench.nas
    private val tmdb get() = bench.tmdb
    private val dao get() = bench.dao
    private val nasUhd = FakeRemoteBrowser()

    /** Dune as TMDB gives it in full: cast past the credits saved, crew of every job, logos and release dates of many places. */
    private fun richDune() {
        tmdb.movie(DUNE, "Dune", 2021, imdbId = "tt1160419")
        tmdb.editMovie(DUNE) {
            it.copy(
                tagline = "Beyond fear, destiny awaits",
                runtime = 155,
                voteAverage = 7.8,
                voteCount = 12_000,
                genres = listOf(TmdbGenre("Science Fiction"), TmdbGenre("Adventure")),
                backdropPath = "/dune-backdrop.jpg",
                credits = TmdbCredits(
                    cast = (0 until CAST).map { n ->
                        TmdbCastMember("Actor $n", "Role $n", "/actor-$n.jpg", order = CAST - n, id = ACTORS + n)
                    } + TmdbCastMember("Actor 3", "Second role", null, order = CAST + 1, id = ACTORS + 3),
                    crew = listOf(
                        TmdbCrewMember("Denis Villeneuve", "Director", 137_427, "/villeneuve.jpg"),
                        TmdbCrewMember("Jon Spaihts", "Screenplay", 1_000_001, null),
                        TmdbCrewMember("Hans Zimmer", "Original Music Composer", 947, "/zimmer.jpg"),
                        TmdbCrewMember("Greig Fraser", "Director of Photography", 1_000_002, null),
                        TmdbCrewMember("Joe Walker", "Editor", 1_000_003, null),
                        TmdbCrewMember("Gaffer Person", "Gaffer", 1_000_004, "/gaffer.jpg"),
                        TmdbCrewMember("Grip Person", "Key Grip", 1_000_005, null),
                    ),
                ),
                images = TmdbImages(
                    listOf(
                        TmdbImage("/logo-fr.png", "fr", 9.0),
                        TmdbImage("/logo-en-low.png", "en", 3.0),
                        TmdbImage("/logo-en.png", "en", 5.0),
                        TmdbImage("/logo-plain.png", null, 8.0),
                    ),
                ),
                releaseDates = TmdbReleaseDates(
                    listOf(
                        TmdbCountryReleases("FR", listOf(TmdbReleaseDate("12"))),
                        TmdbCountryReleases("US", listOf(TmdbReleaseDate(""), TmdbReleaseDate("PG-13"), TmdbReleaseDate("R"))),
                        TmdbCountryReleases("DE", listOf(TmdbReleaseDate("12"))),
                    ),
                ),
            )
        }
    }

    /** The details and credits a copy is saved with, without what is its file's own. */
    private suspend fun saved(source: SourceEntity, path: String): Pair<List<Any?>, List<List<String?>>> {
        val row = bench.item(source, path)
        val details = with(row) {
            listOf(
                tmdbId, title, originalTitle, overview, tagline, year, runtimeMin, certification, rating, votes, genres, directors,
                posterPath, backdropPath, logoPath, matchState, matchedAt, creditsAt, collectionId,
            )
        }
        val credits = bench.db.openHelper.readableDatabase.query(
            "SELECT job, ord, personKey, name, foldedName, character, profilePath FROM credits WHERE itemId = ? ORDER BY job, ord",
            arrayOf<Any>(row.id),
        ).use { c -> buildList { while (c.moveToNext()) add((0 until c.columnCount).map(c::getString)) } }
        return details to credits
    }

    @Test
    fun aCopyReadFromWhatThePassKeptIsSavedAsOneTmdbAnsweredFor() = runTest {
        val hd = bench.folder("Films")
        val uhd = bench.folder("Films 4K", on = nasUhd)
        richDune()
        tmdb.movie(ARRIVAL, "Arrival", 2016)
        tmdb.editMovie(ARRIVAL) { it.copy(credits = tmdb.movies.getValue(DUNE).credits) }
        // An NFO's people, completed by TMDB's: one far down its cast, one in a job not kept.
        val nfo = "<movie><title>Dune</title><tmdbid>$DUNE</tmdbid><actor><name>Actor 44</name><role>Pilot</role></actor>" +
            "<actor><name>Nobody</name></actor><director>Denis Villeneuve</director><credits>Gaffer Person</credits></movie>"
        for ((browser, suffix) in listOf(nas to "", nasUhd to " 2160p")) {
            browser.put("Dune (2021)$suffix.mkv")
            browser.putText("Dune (2021)$suffix.nfo", nfo)
            browser.put("Arrival (2016) {tmdb-$ARRIVAL}$suffix.mkv")
        }
        val pass = bench.pass()

        assertEquals("each asked once", listOf("movie/$DUNE", "movie/$ARRIVAL"), pass.requests.filter { it.startsWith("movie/") })
        val dune = saved(hd, "Dune (2021).mkv")
        assertEquals(listOf("PG-13", "/logo-en.png"), dune.first.let { listOf(it[7], it[14]) })
        assertNotNull("completed from TMDB's cast", dune.second.firstOrNull { it[3] == "Actor 44" && it[2] == "tmdb:${ACTORS + 44}" })
        assertEquals(dune, saved(uhd, "Dune (2021) 2160p.mkv"))
        val arrival = saved(hd, "Arrival (2016) {tmdb-$ARRIVAL}.mkv")
        assertEquals("TMDB's cast saved", 30, arrival.second.count { it[0] == "CAST" })
        assertEquals(arrival, saved(uhd, "Arrival (2016) {tmdb-$ARRIVAL} 2160p.mkv"))
    }

    /** HD and 4K folders of more films than a few dozen, read one after the other: each film is asked once. */
    @Test
    fun twoFoldersOfManyFilmsAskEachOnce() = runTest {
        val hd = bench.folder("Films")
        val uhd = bench.folder("Films 4K", on = nasUhd)
        repeat(FILMS) { n ->
            tmdb.movie(FIRST + n, "Film $n", 2000)
            nas.put("Film $n (2000) {tmdb-${FIRST + n}}.mkv")
            nasUhd.put("Film $n (2000) {tmdb-${FIRST + n}} 2160p.mkv")
        }
        val pass = bench.pass()

        val matched = (dao.filesOf(hd.id) + dao.filesOf(uhd.id)).mapNotNull { it.tmdbId }
        assertEquals("every copy", List(2 * FILMS) { FIRST + it % FILMS }.sorted(), matched.sorted())
        assertEquals((0 until FILMS).map { "movie/${FIRST + it}" }.sorted(), pass.requests.sorted())
    }

    private companion object {
        const val DUNE = 438631
        const val ARRIVAL = 329865
        const val ACTORS = 5_000
        const val CAST = 60
        const val FIRST = 10_000

        /** Films in each folder: more than a few dozen. */
        const val FILMS = 80
    }
}
