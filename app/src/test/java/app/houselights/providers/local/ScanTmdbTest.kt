package app.houselights.providers.local

import app.houselights.core.FailureKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Without TMDB, scans index files from their NFO and local artwork, and ask TMDB again later. */
@RunWith(RobolectricTestRunner::class)
class ScanTmdbTest {
    @get:Rule
    val scan = ScanBenchRule()

    private val bench get() = scan.bench
    private val nas get() = bench.nas
    private val tmdb get() = bench.tmdb

    /** A film described by tinyMediaManager: its NFO and its poster beside it. */
    private fun described(name: String, title: String) {
        nas.put("$name.mkv")
        nas.putText("$name.nfo", "<movie><title>$title</title></movie>")
        nas.putImage("$name-poster.jpg")
    }

    private fun issue() = bench.scanner.state.value.tmdbIssue

    @Test
    fun tmdbOutOfReachLeavesFilmsToTheirNfoAndArtwork() = runTest {
        val films = bench.folder("Films")
        repeat(5) { described("Film $it (2020)", "Mon film $it") }
        tmdb.movie(1, "Mon film 0", 2020)
        bench.tmdbOutOfReach()
        bench.scan()

        assertEquals("asked once: the scans' client then pauses", 1, tmdb.requests.size)
        val first = bench.item(films, "Film 0 (2020).mkv")
        assertEquals("Mon film 0", first.title)
        assertTrue(first.posterPath.isCachedArtwork())
        assertNull("asked again at the next scan", first.matchedAt)
        assertEquals(5, bench.dao.filesOf(films.id).size)
        assertEquals(FailureKind.TMDB_UNREACHABLE, issue())

        bench.tmdbBack()
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
        assertEquals(FailureKind.TMDB_KEY_REFUSED, issue())
        assertEquals(4, bench.dao.filesOf(films.id).size)
        assertTrue(bench.dao.filesOf(films.id).all { it.matchedAt == null })
    }

    @Test
    fun titlesARefusedKeyLeftUnmatchedAreNotReadAgainAtTheNextPass() = runTest {
        bench.folder("Films")
        repeat(3) { described("Film $it (2020)", "Mon film $it") }
        tmdb.status = 401
        bench.scan()
        assertEquals(FailureKind.TMDB_KEY_REFUSED, issue())

        bench.assertQuiet(auto = true)
        assertEquals("still said", FailureKind.TMDB_KEY_REFUSED, issue())
    }

    @Test
    fun titlesLeftUnmatchedAreAskedForAgainAnHourLater() = runTest {
        val films = bench.folder("Films")
        repeat(3) { described("Film $it (2020)", "Mon film $it") }
        tmdb.movie(1, "Mon film 0", 2020)
        tmdb.status = 401
        bench.scan()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(59))
        bench.autoScan()
        assertEquals("asked at the first pass only", 1, tmdb.requests.size)

        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        nas.reads.clear()
        tmdb.requests.clear()
        bench.autoScan()
        assertEquals("asked again, and refused", 1, tmdb.requests.size)
        assertEquals("the other titles are not read once TMDB refused", 1, nas.reads.count { it.endsWith(".nfo") })

        bench.tmdbBack()
        bench.scan()
        assertEquals(1, bench.item(films, "Film 0 (2020).mkv").tmdbId)
        assertNull(issue())
    }

    /** "Scan now", once the network is back: what TMDB left unmatched does not wait for the hour. */
    @Test
    fun aScanAskedForAsksAtOnceForWhatTmdbLeftUnmatched() = runTest {
        val films = bench.folder("Films")
        repeat(3) { described("Film $it (2020)", "Mon film $it") }
        tmdb.movie(1, "Mon film 0", 2020)
        bench.tmdbOutOfReach()
        bench.scan()
        assertEquals(FailureKind.TMDB_UNREACHABLE, issue())

        // Past the client's own pause, within the hour.
        bench.tmdbBack()
        assertEquals("the app's own passes wait", emptyList<String>(), bench.pass(auto = true).requests)

        bench.scan()
        assertEquals(1, bench.item(films, "Film 0 (2020).mkv").tmdbId)
        assertNull(issue())
    }

    /** A film TMDB matches late keeps the tracks chosen while it was unmatched. */
    @Test
    fun aFilmMatchedLateKeepsItsChoiceOfTracks() = runTest {
        val films = bench.folder("Films")
        described("Film (2020)", "Mon film")
        tmdb.movie(1, "Mon film", 2020)
        bench.tmdbOutOfReach()
        bench.scan()
        val film = bench.item(films, "Film (2020).mkv")
        assertNull(film.tmdbId)
        val choices = bench.db.trackChoices()
        choices.save(TrackChoiceEntity("local:${film.id}", "kor", null, at = 5))

        bench.tmdbBack()
        bench.scan()
        assertEquals(1, bench.item(films, "Film (2020).mkv").tmdbId)
        assertEquals("kor", choices.choice("local:film:1")?.audio)
    }

    /** TMDB answers for a new file within the hour: it is back, says so, and the others are asked next pass. */
    @Test
    fun tmdbAnsweringANewFileEndsTheWait() = runTest {
        val films = bench.folder("Films")
        repeat(2) { described("Film $it (2020)", "Mon film $it") }
        tmdb.movie(1, "Mon film 0", 2020)
        tmdb.movie(2, "Nouveau", 2021)
        bench.tmdbOutOfReach()
        bench.scan()
        assertEquals(FailureKind.TMDB_UNREACHABLE, issue())

        bench.tmdbBack()
        described("Nouveau (2021)", "Nouveau")
        bench.autoScan()
        assertEquals(2, bench.item(films, "Nouveau (2021).mkv").tmdbId)
        assertNull("TMDB answered", issue())

        bench.autoScan()
        assertEquals("no longer waits the hour", 1, bench.item(films, "Film 0 (2020).mkv").tmdbId)
    }

    /** One failure in a pass that TMDB then answers to the end: TMDB is back, so nothing is said and nothing waits the hour. */
    @Test
    fun aFailureTmdbAnswersAfterIsNotSaidOnceThePassIsOver() = runTest {
        val films = bench.folder("Films")
        repeat(3) {
            described("Film $it (2020)", "Mon film $it")
            tmdb.movie(it + 1, "Mon film $it", 2020)
        }
        tmdb.nextStatuses += 500
        bench.scan()
        suspend fun matched() = (0 until 3).map { bench.item(films, "Film $it (2020).mkv").tmdbId != null }
        assertEquals("the film TMDB failed for is left", 2, matched().count { it })
        assertNull("TMDB answered after", issue())

        bench.autoScan()
        assertEquals("asked again within the hour", listOf(true, true, true), matched())
    }

    @Test
    fun aNewKeyIsAskedAtOnceAndWhatTmdbSaidOfTheOldOneIsForgotten() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(438631, "Dune", 2021)
        nas.put("Dune (2021).mkv")
        tmdb.status = 401
        bench.scan()
        assertEquals(FailureKind.TMDB_KEY_REFUSED, issue())

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
        bench.tmdbOutOfReach()
        bench.scan()
        val new = bench.item(shows, "Severance/Season 1/Severance - S01E02.mkv")
        assertNull(new.matchedAt)

        bench.tmdbBack()
        bench.scan()
        assertEquals("Episode 1.2", bench.item(shows, "Severance/Season 1/Severance - S01E02.mkv").title)
    }

    @Test
    fun aShowFirstScannedWithoutTmdbGetsItsDetailsOnceTmdbAnswers() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(95396, "Severance", 2022)
        tmdb.season(95396, 1, episodes = 3)
        repeat(3) { bench.showsNas.put("Severance/Season 1/Severance - S01E0${it + 1}.mkv") }
        bench.tmdbOutOfReach()
        bench.scan()
        assertEquals(3, bench.dao.filesOf(shows.id).size)

        bench.tmdbBack()
        bench.scan()
        val titles = bench.dao.filesOf(shows.id).sortedBy { it.episodeNumber }.map { it.title }
        assertEquals(listOf("Episode 1.1", "Episode 1.2", "Episode 1.3"), titles)
        val series = bench.series("Severance")
        assertEquals(95396, series.tmdbId)
        assertEquals("Season 1", bench.dao.season(series.id, 1)?.title)
    }

    @Test
    fun aShowSavedFromItsPosterAloneIsLookedUpOnceThereIsAKey() = runTest {
        bench.withTmdb = false
        bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(95396, "Severance", 2022)
        bench.showsNas.putImage("Severance/poster.jpg")
        bench.showsNas.put("Severance/Season 1/Severance - S01E01.mkv")
        bench.scan()
        val unmatched = bench.series("Severance")
        assertTrue(unmatched.posterPath.isCachedArtwork())
        assertNull(unmatched.matchedAt)

        bench.withTmdb = true
        bench.scan()
        assertEquals(95396, bench.series("Severance").tmdbId)
    }

    @Test
    fun aRefreshTmdbDidNotAnswerIsAskedAgainLater() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(438631, "Dune", 2021)
        nas.put("Dune (2021).mkv")
        nas.put("Heat (1995).mkv")
        bench.scan()
        assertNotNull(bench.item(films, "Dune (2021).mkv").matchedAt)

        // TMDB's details changed; the requested refresh finds TMDB unreachable.
        tmdb.editMovie(438631) { it.copy(overview = "Arrakis") }
        bench.tmdbOutOfReach()
        bench.scan(refreshMetadata = true)
        assertNull("asked again at the next scan", bench.item(films, "Dune (2021).mkv").matchedAt)
        assertNull("one TMDB had nothing for, too", bench.item(films, "Heat (1995).mkv").matchedAt)
        assertEquals("what it had stays meanwhile", 438631, bench.item(films, "Dune (2021).mkv").tmdbId)

        bench.tmdbBack()
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

        tmdb.editMovie(438631) { it.copy(overview = "Arrakis") }
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

        tmdb.editShow(95396) { it.copy(overview = "Lumon") }
        bench.tmdbOutOfReach()
        bench.scan(refreshMetadata = true)
        val series = bench.series("Severance")
        assertNull(series.matchedAt)
        assertNull(bench.item(shows, pilot).matchedAt)

        bench.tmdbBack()
        bench.scan()
        val refreshed = bench.series("Severance")
        assertNotNull(refreshed.matchedAt)
        assertEquals("Lumon", refreshed.overview)
        assertNotNull(bench.item(shows, pilot).matchedAt)
    }

    private val moblandId = 247718

    /** MobLand as the NAS holds it, scene names in a short season folder, matched with TMDB in English. */
    private suspend fun mobland(): Long {
        bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(moblandId, "MobLand", 2025)
        tmdb.season(moblandId, 1, episodes = 3)
        (1..3).forEach { bench.showsNas.put("Mobland/S01/Mobland.S01E0$it.MULTi.1080p.WEB.H265-GRP.mkv") }
        bench.scan()
        val series = bench.series("Mobland")
        assertEquals("Season 1", bench.dao.season(series.id, 1)?.title)
        // The device switches to French: TMDB names the season differently.
        tmdb.seasons[moblandId to 1] = tmdb.seasons.getValue(moblandId to 1).copy(name = "Saison 1", overview = "Deux familles de Londres")
        return series.id
    }

    /** "Refresh all": seasons take TMDB's details again too, each requested once for all its episodes. */
    @Test
    fun aRefreshGivesSeasonsTmdbsDetailsAgainAskingOnceASeason() = runTest {
        val seriesId = mobland()
        tmdb.requests.clear()
        bench.scan(refreshMetadata = true)

        val season = checkNotNull(bench.dao.season(seriesId, 1))
        assertEquals("Saison 1", season.title)
        assertEquals("Deux familles de Londres", season.overview)
        assertEquals("asked once for its three episodes", 1, tmdb.requests.count { it == "tv/$moblandId/season/1" })
    }

    @Test
    fun aSeasonRefreshTmdbDidNotAnswerIsAskedAgainLater() = runTest {
        val seriesId = mobland()
        tmdb.failingPath = "tv/$moblandId/season/"
        bench.scan(refreshMetadata = true)
        val waiting = checkNotNull(bench.dao.season(seriesId, 1))
        assertEquals("what it had stays meanwhile", "Season 1", waiting.title)
        assertNull(waiting.matchedAt)

        bench.tmdbBack()
        bench.scan()
        val refreshed = checkNotNull(bench.dao.season(seriesId, 1))
        assertEquals("Saison 1", refreshed.title)
        assertNotNull(refreshed.matchedAt)
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

        bench.tmdbBack()
        bench.scan()
        val again = bench.item(films, "Dune (2021).mkv")
        assertNotNull(again.matchedAt)
        assertEquals("Arrakis", bench.dao.collection(checkNotNull(again.collectionId))?.overview)
    }

    /** "Dune.mkv": two films and a sequel of that name, none clearly ahead: to identify, its three best candidates kept. */
    @Test
    fun aFilmTmdbHesitatesOnIsToIdentifyWithItsThreeSuggestions() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(DUNE, "Dune", 2021, votes = 12_000)
        tmdb.movie(LYNCH, "Dune", 1984, votes = 3_000)
        tmdb.movie(PART_TWO, "Dune : Deuxième partie", 2024, votes = 6_000)
        tmdb.searches["dune"] = listOf(DUNE, LYNCH, PART_TWO)
        nas.put("Dune.mkv")
        bench.scan()

        val dune = bench.item(films, "Dune.mkv")
        assertEquals(null to MatchState.UNSURE, dune.tmdbId to dune.matchState)
        val offered = bench.dao.suggestions(dune.id)
        assertEquals("the best first", listOf(DUNE, LYNCH, PART_TWO), offered.map { it.tmdbId })
        assertEquals(listOf(0, 1, 2), offered.map { it.rank })
        assertEquals("Dune" to 1984, offered[1].title to offered[1].year)
        assertEquals("/poster-$LYNCH.jpg", offered[1].posterPath)
        assertEquals(listOf(dune.id), bench.dao.toIdentify().map { it.id })
    }

    /** A title matched before keeps its match: a refresh asks TMDB by its id, never by its name, whatever TMDB holds since. */
    @Test
    fun aFilmMatchedBeforeKeepsItsMatch() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(DUNE, "Dune", 2021, votes = 12_000)
        nas.put("Dune.mkv")
        bench.scan()
        assertEquals(DUNE, bench.item(films, "Dune.mkv").tmdbId)

        // The film of 1984 on TMDB too: "Dune.mkv" would now be to identify.
        tmdb.movie(LYNCH, "Dune", 1984, votes = 3_000)
        bench.later(DAY_MS)
        assertEquals("nothing asked at the next passes", emptyList<String>(), bench.pass().requests)
        val refresh = bench.pass(refresh = true)
        assertEquals("never searched", 0, refresh.requests.count { it.startsWith("search/") })
        val dune = bench.item(films, "Dune.mkv")
        assertEquals(DUNE to MatchState.MATCHED, dune.tmdbId to dune.matchState)
    }

    /** "Dune (2020).mkv": TMDB's first answer of 2020 is another film, which 0.3.0 took; Dune of 2021 is the film. */
    @Test
    fun aFirstResultOfAnotherTitleIsNotMatched() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(DRIFTER, "Dune Drifter", 2020, votes = 100)
        tmdb.movie(DUNE, "Dune", 2021, votes = 12_000)
        tmdb.movie(LYNCH, "Dune", 1984, votes = 3_000)
        tmdb.searches["dune"] = listOf(DRIFTER, DUNE, LYNCH)
        nas.put("Dune (2020).mkv")
        bench.scan()

        assertEquals(DUNE, bench.item(films, "Dune (2020).mkv").tmdbId)
        assertEquals("its year, then without", listOf("Dune (2020)", "Dune"), tmdb.searched)
    }

    /** "Wonder Woman 1984.mkv": its bare year is its title's, so the film of 2020, not the film of 2017 TMDB puts first. */
    @Test
    fun aBareYearIsReadAsPartOfTheTitle() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(WONDER_WOMAN, "Wonder Woman", 2017, votes = 20_000)
        tmdb.movie(WW84, "Wonder Woman 1984", 2020, votes = 9_000)
        tmdb.searches["wonder woman"] = listOf(WONDER_WOMAN, WW84)
        nas.put("Wonder Woman 1984.mkv")
        bench.scan()

        assertEquals(WW84, bench.item(films, "Wonder Woman 1984.mkv").tmdbId)
    }

    private companion object {
        const val DUNE = 438631
        const val LYNCH = 841
        const val PART_TWO = 693134
        const val DRIFTER = 1
        const val WONDER_WOMAN = 297762
        const val WW84 = 464052
        const val DAY_MS = 24 * 60 * 60_000L
    }
}
