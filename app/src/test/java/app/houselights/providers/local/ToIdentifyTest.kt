package app.houselights.providers.local

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What TMDB made of a title ([MatchState]): matched once it has a TMDB id, not found, so to identify, once TMDB
 * answered without; a title TMDB did not answer for is only waiting, as before. Titles to identify are asked again
 * ([Reidentify]): daily in their first week, then weekly, an hour later on a scan the user asks for, a few per pass,
 * never while TMDB is out of reach, never reading the NAS.
 */
@RunWith(RobolectricTestRunner::class)
class ToIdentifyTest {
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

    @Test
    fun aTitleTmdbHasNotIsToIdentifyNotUnanswered() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(DUNE, "Dune", 2021)
        nas.put("Dune (2021).mkv")
        nas.put("Vacances à Biarritz (2019).mkv")
        bench.scan()

        assertEquals(MatchState.MATCHED, bench.item(films, "Dune (2021).mkv").matchState)
        val holidays = bench.item(films, "Vacances à Biarritz (2019).mkv")
        assertEquals(MatchState.NOT_FOUND, holidays.matchState)
        assertEquals(listOf(holidays.id), dao.toIdentify().map { it.id })
    }

    @Test
    fun aTitleTmdbDoesNotAnswerIsNotToIdentify() = runTest {
        val films = bench.folder("Films")
        nas.put("Vacances à Biarritz (2019).mkv")
        bench.tmdbOutOfReach()
        bench.scan()

        val holidays = bench.item(films, "Vacances à Biarritz (2019).mkv")
        assertNull("still to look up", holidays.matchState)
        assertNull(holidays.matchedAt)
        assertEquals(emptyList<ItemEntity>(), dao.toIdentify())
    }

    /** Named by its NFO while TMDB is out of reach, a film is matched: it has a TMDB id, whatever TMDB says later. */
    @Test
    fun aTitleWithATmdbIdIsMatchedWhetherTmdbAnsweredOrNot() = runTest {
        val films = bench.folder("Films")
        nas.put("Dune (2021).mkv")
        nas.putText("Dune (2021).nfo", "<movie><title>Dune</title><uniqueid type=\"tmdb\">$DUNE</uniqueid></movie>")
        bench.tmdbOutOfReach()
        bench.scan()

        val dune = bench.item(films, "Dune (2021).mkv")
        assertEquals(DUNE to MatchState.MATCHED, dune.tmdbId to dune.matchState)
        assertEquals(emptyList<ItemEntity>(), dao.toIdentify())
    }

    /** A show TMDB has not is to identify; its seasons and episodes never are. */
    @Test
    fun aShowTmdbHasNotIsToIdentifyNotItsEpisodes() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        bench.showsNas.put("Kaamelott/Livre 1/Kaamelott - S01E01.mkv")
        bench.scan()

        val show = bench.showOf(shows, "Kaamelott/Livre 1/Kaamelott - S01E01.mkv")
        assertEquals(MatchState.NOT_FOUND, show.matchState)
        assertNull(bench.item(shows, "Kaamelott/Livre 1/Kaamelott - S01E01.mkv").matchState)
        assertEquals(listOf(show.id), dao.toIdentify().map { it.id })
    }

    /** A film TMDB has not: asked again a day later on the app's own scans, not before, by what its row holds. */
    @Test
    fun aTitleToIdentifyIsAskedAgainADayLaterNotBefore() = runTest {
        val films = bench.folder("Films")
        nas.put(HOLIDAYS)
        bench.scan()

        bench.later(DAY_MS - 1)
        bench.assertQuiet(auto = true)
        bench.later(1)
        val pass = bench.pass(auto = true)
        assertTrue("asked again", pass.requests.any { it.startsWith("search/movie") })
        assertEquals("never the NAS", emptyList<String>(), pass.reads)
        assertEquals("not found still", MatchState.NOT_FOUND, bench.item(films, HOLIDAYS).matchState)
        bench.later(DAY_MS - 1)
        bench.assertQuiet(auto = true)
    }

    /** Past its first week, a title to identify is asked weekly. */
    @Test
    fun anOlderTitleToIdentifyIsAskedWeekly() = runTest {
        bench.folder("Films")
        nas.put(HOLIDAYS)
        bench.scan()
        bench.later(8 * DAY_MS)
        assertTrue(bench.pass(auto = true).requests.isNotEmpty())

        bench.later(DAY_MS)
        bench.assertQuiet(auto = true)
        bench.later(6 * DAY_MS - 1)
        bench.assertQuiet(auto = true)
        bench.later(1)
        assertTrue("a week later", bench.pass(auto = true).requests.isNotEmpty())
    }

    /** TMDB lists the film since: matched at the next check, with its details, its candidates gone. */
    @Test
    fun aFilmTmdbAddsLaterIsMatchedAtTheNextCheck() = runTest {
        val films = bench.folder("Films")
        nas.put(HOLIDAYS)
        bench.scan()

        tmdb.movie(1, "Vacances à Biarritz", 2019)
        bench.later(DAY_MS)
        val pass = bench.pass(auto = true)
        val holidays = bench.item(films, HOLIDAYS)
        assertEquals(1 to MatchState.MATCHED, holidays.tmdbId to holidays.matchState)
        assertEquals("About Vacances à Biarritz", holidays.overview)
        assertEquals(emptyList<ItemEntity>(), dao.toIdentify())
        assertEquals(emptyList<String>(), pass.reads)
        assertTrue("screens told", pass.changes > 0)
    }

    /** A show TMDB lists since: matched with each of its seasons, every episode taking its details, none read again. */
    @Test
    fun aShowToIdentifyFoundLaterGetsItsEpisodes() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        (1..2).forEach { bench.showsNas.put("Severance/Season 1/Severance - S01E0$it.mkv") }
        bench.showsNas.put("Severance/Season 2/Severance - S02E01.mkv")
        bench.scan()

        tmdb.show(SEVERANCE, "Severance", 2022)
        tmdb.season(SEVERANCE, 1, episodes = 2)
        tmdb.season(SEVERANCE, 2, episodes = 1)
        bench.later(DAY_MS)
        val pass = bench.pass(auto = true)
        val show = bench.showOf(shows, "Severance/Season 1/Severance - S01E01.mkv")
        assertEquals(SEVERANCE, show.tmdbId)
        assertEquals(listOf("Episode 1.1", "Episode 1.2", "Episode 2.1"), dao.episodesOfSeries(show.id).map { it.title })
        assertEquals("Season 2", dao.season(show.id, 2)?.title)
        assertEquals("never the NAS", emptyList<String>(), pass.reads)
        bench.assertQuiet(auto = true)
    }

    /** Still not found, a show keeps all it has: its state, candidates and time asked change, never its episodes. */
    @Test
    fun aRecheckWithoutAMatchChangesOnlyWhatTmdbMadeOfIt() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        bench.showsNas.put("Severance/Season 1/Severance - S01E01.mkv")
        bench.scan()
        val episode = bench.item(shows, "Severance/Season 1/Severance - S01E01.mkv")
        val before = bench.showOf(shows, "Severance/Season 1/Severance - S01E01.mkv")

        // Two shows of that name since, neither far more known.
        tmdb.show(SEVERANCE, "Severance", 2022, votes = 2_000)
        tmdb.show(2, "Severance", 2019, votes = 900)
        bench.later(DAY_MS)
        val pass = bench.pass(auto = true)
        val after = bench.showOf(shows, "Severance/Season 1/Severance - S01E01.mkv")
        assertEquals(MatchState.UNSURE, after.matchState)
        assertEquals(listOf(SEVERANCE, 2), dao.suggestions(after.id).map { it.tmdbId })
        assertEquals("all but that", before.copy(matchState = MatchState.UNSURE, matchedAt = after.matchedAt), after)
        assertEquals("its episodes as they were", episode, bench.item(shows, "Severance/Season 1/Severance - S01E01.mkv"))
        assertEquals(emptyList<String>(), pass.reads)
        bench.assertQuiet(auto = true)
    }

    /** On a scan the user asks for, a title to identify is asked again an hour after the last time, not before. */
    @Test
    fun aScanAskedForAsksTitlesToIdentifyAfterAnHour() = runTest {
        bench.folder("Films")
        nas.put(HOLIDAYS)
        bench.scan()

        bench.later(HOUR_MS - 1)
        assertEquals(emptyList<String>(), bench.pass().requests)
        bench.later(1)
        assertTrue(bench.pass().requests.any { it.startsWith("search/movie") })
    }

    /** TMDB out of reach: a title to identify waits, still due, and the hour after asks nothing; TMDB back, it is asked. */
    @Test
    fun titlesToIdentifyWaitWhileTmdbIsDown() = runTest {
        val films = bench.folder("Films")
        nas.put(HOLIDAYS)
        bench.scan()
        val asked = bench.item(films, HOLIDAYS).matchedAt

        bench.later(DAY_MS)
        bench.tmdbOutOfReach()
        assertTrue("one request, the scans' client then pauses", bench.pass(auto = true).requests.size <= 1)
        assertEquals("still due", asked, bench.item(films, HOLIDAYS).matchedAt)
        bench.later(HOUR_MS / 2)
        assertEquals(emptyList<String>(), bench.pass(auto = true).requests)

        bench.tmdbBack()
        tmdb.movie(1, "Vacances à Biarritz", 2019)
        bench.later(HOUR_MS / 2)
        bench.pass(auto = true)
        assertEquals(1, bench.item(films, HOLIDAYS).tmdbId)
    }

    @Test
    fun noMoreThanFortyTitlesAreAskedAgainInAPass() = runTest {
        val films = bench.folder("Films")
        repeat(45) { nas.put("Vacances $it (2019).mkv") }
        bench.scan()
        val first = checkNotNull(dao.filesOf(films.id).first().matchedAt)

        bench.later(DAY_MS)
        bench.pass(auto = true)
        assertEquals(Reidentify.TITLES_PER_PASS, dao.filesOf(films.id).count { it.matchedAt != first })
        bench.pass(auto = true)
        assertEquals("the others at the next pass", 45, dao.filesOf(films.id).count { it.matchedAt != first })
    }

    /** Shows are asked again as their calls allow: a show found is fetched with each of its seasons. */
    @Test
    fun showsAskedAgainCountTheirSeasons() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        repeat(30) { show -> (1..3).forEach { bench.showsNas.put("Série $show/Season $it/Série $show - S0${it}E01.mkv") } }
        bench.scan()
        suspend fun asked() = dao.toIdentify().count { it.matchedAt == bench.now }

        bench.later(DAY_MS)
        bench.pass(auto = true)
        val perShow = MatchPlan.MAX_SEARCHES + 1 + 3
        assertEquals(Reidentify.SHOW_CALLS_PER_PASS / perShow, asked())
        bench.later(1)
        bench.pass(auto = true)
        assertEquals("the others at the next pass", 30 - Reidentify.SHOW_CALLS_PER_PASS / perShow, asked())
    }

    /** A refresh reads a title to identify in full, searching it once: the check after it finds it not due. */
    @Test
    fun aRefreshAsksATitleToIdentifyOnce() = runTest {
        bench.folder("Films")
        nas.put(HOLIDAYS)
        bench.scan()
        bench.later(DAY_MS)
        tmdb.searched.clear()
        bench.pass(refresh = true)
        assertTrue(tmdb.searched.isNotEmpty())
        assertEquals("each search once", tmdb.searched.distinct(), tmdb.searched)
    }

    /** A film with its NFO and poster, asked again: TMDB is asked by what its row holds, nothing is read on the NAS. */
    @Test
    fun aRecheckNeverReadsTheNas() = runTest {
        val films = bench.folder("Films")
        nas.put(HOLIDAYS)
        nas.putText("Vacances à Biarritz (2019).nfo", "<movie><title>Vacances à Biarritz</title><plot>L'été 2019</plot></movie>")
        nas.putImage("Vacances à Biarritz (2019)-poster.jpg")
        bench.scan()
        val before = bench.item(films, HOLIDAYS)

        bench.later(DAY_MS)
        val pass = bench.pass(auto = true)
        assertTrue(pass.requests.isNotEmpty())
        assertEquals(emptyList<String>(), pass.reads)
        val after = bench.item(films, HOLIDAYS)
        assertEquals("L'été 2019" to before.posterPath, after.overview to after.posterPath)
    }

    /**
     * A film the user played and liked leaves, then comes back under a name TMDB hesitates on ("Dune.mkv" where it was
     * "Dune (2021).mkv"): identified through the titles to identify, it takes back what was kept of it.
     */
    @Test
    fun aTitleIdentifiedThroughToIdentifyGetsBackWhatWasKeptForIt() = runTest {
        val films = bench.folder("Films")
        nas.put(HEAT)
        tmdb.movie(949, "Heat", 1995)
        tmdb.movie(DUNE, "Dune", 2021, votes = 12_000)
        nas.put("Dune (2021).mkv")
        bench.scan()
        val dune = bench.item(films, "Dune (2021).mkv")
        dao.setFavorite(dune.id, true)
        dao.savePlayback(dune.id, positionMs = 0, durationMs = 9_000_000, played = true, lastPlayedAt = 5)
        nas.remove("Dune (2021).mkv")
        bench.scan()

        tmdb.movie(LYNCH, "Dune", 1984, votes = 3_000)
        nas.put("Dune.mkv")
        bench.scan()
        val back = bench.item(films, "Dune.mkv")
        assertEquals(MatchState.UNSURE to false, back.matchState to back.favorite)
        assertEquals(listOf(back.id), dao.toIdentify().map { it.id })

        bench.scanner.identify(back.id, DUNE)
        val identified = bench.item(films, "Dune.mkv")
        assertEquals(DUNE to MatchState.MATCHED, identified.tmdbId to identified.matchState)
        assertEquals("what the user did", true to true, identified.favorite to identified.played)
        assertEquals(emptyList<SuggestionEntity>(), dao.suggestions(back.id))
    }

    /**
     * A film TMDB matched leaves, then comes back under the same name, which TMDB now hesitates on ("Dune.mkv", Dune of
     * 1984 listed since): what was kept in its folder lends it its TMDB id, tried before any search.
     */
    @Test
    fun aFilmBackUnderANameTmdbHesitatesOnIsTheFilmKeptInItsFolder() = runTest {
        val films = bench.folder("Films")
        nas.put(HEAT)
        tmdb.movie(DUNE, "Dune", 2021, votes = 12_000)
        nas.put("Dune.mkv")
        bench.scan()
        dao.setFavorite(bench.item(films, "Dune.mkv").id, true)
        nas.remove("Dune.mkv")
        bench.scan()

        tmdb.movie(LYNCH, "Dune", 1984, votes = 3_000)
        nas.put("Dune.mkv")
        val pass = bench.pass()
        val back = bench.item(films, "Dune.mkv")
        assertEquals(DUNE to true, back.tmdbId to back.favorite)
        assertEquals("never searched", 0, pass.requests.count { it.startsWith("search/") })
    }

    /** As above, TMDB out of reach as the film comes back: what was kept lends it its TMDB id at the next pass TMDB answers. */
    @Test
    fun aFilmBackWhileTmdbIsOutOfReachIsTheFilmKeptInItsFolderOnceItAnswers() = runTest {
        val films = bench.folder("Films")
        nas.put(HEAT)
        tmdb.movie(DUNE, "Dune", 2021, votes = 12_000)
        nas.put("Dune.mkv")
        bench.scan()
        dao.setFavorite(bench.item(films, "Dune.mkv").id, true)
        nas.remove("Dune.mkv")
        bench.scan()

        tmdb.movie(LYNCH, "Dune", 1984, votes = 3_000)
        nas.put("Dune.mkv")
        bench.tmdbOutOfReach()
        bench.scan()
        assertNull("not looked up yet", bench.item(films, "Dune.mkv").matchedAt)
        bench.tmdbBack()
        val pass = bench.pass()
        val back = bench.item(films, "Dune.mkv")
        assertEquals(DUNE to true, back.tmdbId to back.favorite)
        assertEquals("never searched", 0, pass.requests.count { it.startsWith("search/") })
    }

    /** A show TMDB matched leaves, then comes back under its folder's name, which TMDB now hesitates on: it lends its id. */
    @Test
    fun aShowBackUnderANameTmdbHesitatesOnIsTheShowKeptUnderIt() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        bench.showsNas.put(KAAMELOTT)
        tmdb.show(SEVERANCE, "Severance", 2022, votes = 5_000)
        tmdb.season(SEVERANCE, 1, episodes = 1)
        bench.showsNas.put(PILOT)
        bench.scan()
        dao.setFavorite(bench.showOf(shows, PILOT).id, true)
        bench.showsNas.remove(PILOT)
        bench.scan()

        tmdb.show(OTHER_SEVERANCE, "Severance", 2019, votes = 900)
        bench.showsNas.put(PILOT)
        val pass = bench.pass()
        val back = bench.showOf(shows, PILOT)
        assertEquals(SEVERANCE to true, back.tmdbId to back.favorite)
        assertEquals("never searched", 0, pass.requests.count { it.startsWith("search/") })
    }

    private companion object {
        const val DUNE = 438631
        const val LYNCH = 841
        const val SEVERANCE = 95396
        const val OTHER_SEVERANCE = 1
        const val PILOT = "Severance/Season 1/Severance - S01E01.mkv"
        const val KAAMELOTT = "Kaamelott/Livre 1/Kaamelott - S01E01.mkv"
        const val HOLIDAYS = "Vacances à Biarritz (2019).mkv"
        const val HEAT = "Heat (1995).mkv"
        const val HOUR_MS = 60 * 60_000L
        const val DAY_MS = 24 * HOUR_MS
    }
}
