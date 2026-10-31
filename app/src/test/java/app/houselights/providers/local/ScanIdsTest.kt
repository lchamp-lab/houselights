package app.houselights.providers.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Ids in file and folder names ([NameIds]) identify titles before their NFO's and the search, the user's choice
 * excepted: once, and again when a name gives another id than its last lookup tried, a few titles per pass.
 */
@RunWith(RobolectricTestRunner::class)
class ScanIdsTest {
    @get:Rule
    val scan = ScanBenchRule()

    private val bench get() = scan.bench
    private val nas get() = bench.nas
    private val showsNas get() = bench.showsNas
    private val tmdb get() = bench.tmdb
    private val dao get() = bench.dao

    @Test
    fun aTmdbIdInTheFileNameIdentifiesTheFilm() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(DUNE, "Dune", 2021)
        nas.put("Dune (2021) {tmdb-438631}.mkv")
        val pass = bench.pass()

        val dune = bench.item(films, "Dune (2021) {tmdb-438631}.mkv")
        assertEquals(DUNE, dune.tmdbId)
        assertEquals("Dune" to 2021, dune.title to dune.year)
        assertEquals("tmdb:438631", dune.hintKey)
        assertEquals(MatchState.MATCHED, dune.matchState)
        assertEquals("never searched", listOf("movie/$DUNE"), pass.requests)
    }

    @Test
    fun aTmdbIdInTheFolderNameIdentifiesTheFilm() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(DUNE, "Dune", 2021)
        nas.put("Dune (2021) {tmdb-438631}/Dune.2021.2160p.UHD.BluRay.mkv")
        nas.put("Dune (2021) {tmdb-438631}/Dune (2021) 1080p.mkv")
        val pass = bench.pass()

        val copies = dao.filesOf(films.id)
        assertEquals("both copies", listOf(DUNE, DUNE), copies.map { it.tmdbId })
        // Once would do: each copy asks today, as a film's details are not kept for the pass (a season's are).
        assertTrue("never searched: ${pass.requests}", pass.requests.all { it == "movie/$DUNE" })
        assertTrue("at most once a copy: ${pass.requests}", pass.requests.size in 1..2)
    }

    @Test
    fun anImdbIdInTheNameIsFoundOnTmdb() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(DUNE, "Dune", 2021, imdbId = "tt1160419")
        nas.put("Dune (2021) [imdbid-tt1160419].mkv")
        val pass = bench.pass()

        val dune = bench.item(films, "Dune (2021) [imdbid-tt1160419].mkv")
        assertEquals(DUNE, dune.tmdbId)
        assertEquals("imdb:tt1160419", dune.hintKey)
        assertEquals(listOf("find/tt1160419", "movie/$DUNE"), pass.requests)
    }

    @Test
    fun theUsersChoiceOutranksTheNamesId() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(DUNE, "Dune", 2021)
        tmdb.movie(LYNCH, "Dune", 1984)
        nas.put("Dune (2021) {tmdb-438631}.mkv")
        bench.scan()
        val dune = bench.item(films, "Dune (2021) {tmdb-438631}.mkv")
        bench.scanner.identify(dune.id, LYNCH)

        bench.later(NameIds.RETRY_MS)
        assertEquals("the name's id is not asked", emptyList<String>(), bench.pass().requests)
        bench.scan(refreshMetadata = true)
        assertEquals(LYNCH, bench.item(films, "Dune (2021) {tmdb-438631}.mkv").tmdbId)
    }

    @Test
    fun theNamesTmdbIdOutranksTheNfosAndSetsItAside() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(DUNE, "Dune", 2021)
        tmdb.movie(PART_TWO, "Dune : Deuxième partie", 2024)
        nas.put("Dune (2021) {tmdb-438631}.mkv")
        nas.putText(
            "Dune (2021) {tmdb-438631}.nfo",
            "<movie><title>Dune : Deuxième partie</title><plot>Paul s'allie</plot><uniqueid type=\"tmdb\">$PART_TWO</uniqueid></movie>",
        )
        val pass = bench.pass()

        val dune = bench.item(films, "Dune (2021) {tmdb-438631}.mkv")
        assertEquals(DUNE, dune.tmdbId)
        assertEquals("the NFO describes another film", "Dune" to "About Dune", dune.title to dune.overview)
        assertEquals(listOf("movie/$DUNE"), pass.requests)
    }

    @Test
    fun aTvdbIdInTheShowFolderIdentifiesTheShow() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(SEVERANCE, "Severance", 2022, tvdbId = SEVERANCE_TVDB)
        tmdb.season(SEVERANCE, 1, episodes = 2)
        val pilot = "Severance (2022) {tvdb-371980}/Season 1/Severance - S01E01.mkv"
        showsNas.put(pilot)
        val pass = bench.pass()

        val show = bench.showOf(shows, pilot)
        assertEquals(SEVERANCE, show.tmdbId)
        assertEquals("tvdb:371980", show.hintKey)
        assertEquals("Episode 1.1", bench.item(shows, pilot).title)
        assertEquals(listOf("find/371980", "tv/$SEVERANCE", "tv/$SEVERANCE/episode_groups", "tv/$SEVERANCE/season/1"), pass.requests)
    }

    @Test
    fun aShowNfoWithOnlyATvdbIdIsFoundByIt() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(SEVERANCE, "Severance", 2022, tvdbId = SEVERANCE_TVDB)
        tmdb.season(SEVERANCE, 1, episodes = 1)
        showsNas.put("Severance/Season 1/Severance - S01E01.mkv")
        showsNas.putText("Severance/tvshow.nfo", "<tvshow><title>Severance</title><uniqueid type=\"tvdb\">371980</uniqueid></tvshow>")
        val pass = bench.pass()

        assertEquals(SEVERANCE, bench.showOf(shows, "Severance/Season 1/Severance - S01E01.mkv").tmdbId)
        assertEquals("never searched", emptyList<String>(), pass.requests.filter { it.startsWith("search/") })
        assertTrue(pass.requests.containsAll(listOf("find/371980", "tv/$SEVERANCE")))
    }

    @Test
    fun anIdTmdbNoLongerHasFallsBackToTheTitle() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(DUNE, "Dune", 2021)
        nas.put("Dune (2021) {tmdb-999999}.mkv")
        val pass = bench.pass()

        val dune = bench.item(films, "Dune (2021) {tmdb-999999}.mkv")
        assertEquals(DUNE, dune.tmdbId)
        assertEquals("tried", "tmdb:999999", dune.hintKey)
        assertEquals("movie/999999", pass.requests.first())
        assertTrue(pass.requests.any { it.startsWith("search/movie") })
        bench.later(NameIds.RETRY_MS)
        bench.assertQuiet()
    }

    /**
     * An id whose title has nothing of the name (a show's id, a typo) is not trusted, with no year to tell them apart:
     * nor its alternative titles holding the name's, the title is searched for.
     */
    @Test
    fun aNameIdOfAnotherTitleIsNotTrusted() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(DUNE, "Dune", 2021)
        tmdb.movie(COURGETTE, "Ma vie de Courgette", 2016)
        nas.put("Dune {tmdb-1399}.mkv")
        val pass = bench.pass()

        val dune = bench.item(films, "Dune {tmdb-1399}.mkv")
        assertEquals(DUNE, dune.tmdbId)
        assertEquals("tried, not asked by again", "tmdb:1399", dune.hintKey)
        assertEquals(listOf("movie/$COURGETTE", "movie/$COURGETTE/alternative_titles", "search/movie", "movie/$DUNE"), pass.requests)
    }

    /** A name's id not trusted, the film found by its NFO's: that NFO, rewritten in place since, is still the film's. */
    @Test
    fun anNfoBesideANameIdNotTrustedIsReadAgainWhenRewritten() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(DUNE, "Dune", 2021)
        tmdb.movie(LYNCH, "Dune", 1984)
        val name = "Dune (2021) {tmdb-841}"
        fun nfo(plot: String) = nas.putText("$name.nfo", "<movie><title>Dune</title><plot>$plot</plot><tmdbid>$DUNE</tmdbid></movie>")
        nas.put("$name.mkv")
        nfo("Paul")
        bench.scan()
        assertEquals(DUNE to "Paul", bench.item(films, "$name.mkv").let { it.tmdbId to it.overview })

        nfo("Paul Atréides")
        val pass = bench.pass()
        assertEquals("Paul Atréides", bench.item(films, "$name.mkv").overview)
        assertEquals("nothing asked of TMDB", emptyList<String>(), pass.requests)
    }

    /**
     * A folder's IMDb id beside an NFO giving the film's TMDB id (Radarr's naming, tinyMediaManager's NFO): asked once,
     * as every lookup tries the name's ids first, and not again.
     */
    @Test
    fun aFolderIdBesideAnNfoIdIsAskedOnce() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(DUNE, "Dune", 2021, imdbId = "tt1160419")
        val folder = "Dune (2021) [imdbid-tt1160419]"
        nas.put("$folder/Dune (2021).mkv")
        nas.putText("$folder/movie.nfo", "<movie><title>Dune</title><tmdbid>$DUNE</tmdbid></movie>")
        val pass = bench.pass()

        val dune = bench.item(films, "$folder/Dune (2021).mkv")
        assertEquals(DUNE to "imdb:tt1160419", dune.tmdbId to dune.hintKey)
        assertEquals(listOf("find/tt1160419", "movie/$DUNE"), pass.requests)
        bench.later(NameIds.RETRY_MS)
        bench.assertQuiet()
    }

    /** Sonarr's folder id TMDB does not know, its tvshow.nfo naming the show: found by the NFO, never asked again. */
    @Test
    fun aShowFolderIdBesideAnNfoIdIsAskedOnce() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(SEVERANCE, "Severance", 2022)
        tmdb.season(SEVERANCE, 1, episodes = 1)
        val folder = "Severance (2022) [tvdbid-371980]"
        showsNas.put("$folder/Season 1/Severance - S01E01.mkv")
        showsNas.putText("$folder/tvshow.nfo", "<tvshow><title>Severance</title><tmdbid>$SEVERANCE</tmdbid></tvshow>")
        val pass = bench.pass()

        val show = bench.showOf(shows, "$folder/Season 1/Severance - S01E01.mkv")
        assertEquals(SEVERANCE to "tvdb:371980", show.tmdbId to show.hintKey)
        assertEquals("find/371980", pass.requests.first())
        bench.later(NameIds.RETRY_MS)
        bench.assertQuiet()
    }

    /** The original's id in a remake's name ("Dune (2021) {tmdb-841}"): a match 0.3.0 made right stays, the id tried once. */
    @Test
    fun aRemakesIdInANameLeavesTheFilmItWasMatchedTo() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(DUNE, "Dune", 2021)
        tmdb.movie(LYNCH, "Dune", 1984)
        nas.put("Dune (2021).mkv")
        bench.scan()

        nas.rename("Dune (2021).mkv", "Dune (2021) {tmdb-841}.mkv")
        val pass = bench.pass()
        assertEquals(DUNE, bench.item(films, "Dune (2021) {tmdb-841}.mkv").tmdbId)
        assertEquals("as for an id TMDB has no film for", listOf("movie/$LYNCH", "movie/$DUNE"), pass.requests)
        bench.later(NameIds.RETRY_MS)
        bench.assertQuiet()
    }

    /** A film named in English, TMDB's titles French and Japanese: its alternative titles hold the name's. */
    @Test
    fun anIdOfATitleNamedOtherwiseIsTrustedByItsAlternativeTitles() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(CHIHIRO, "Le Voyage de Chihiro", 2001, originalTitle = "千と千尋の神隠し")
        tmdb.movieAlternatives[CHIHIRO] = listOf("Sen to Chihiro no kamikakushi", "Spirited Away")
        nas.put("Spirited Away (2001) {tmdb-129}.mkv")
        val pass = bench.pass()

        assertEquals("Le Voyage de Chihiro", bench.item(films, "Spirited Away (2001) {tmdb-129}.mkv").title)
        assertEquals(listOf("movie/$CHIHIRO", "movie/$CHIHIRO/alternative_titles"), pass.requests)
    }

    /** A name's id years off its film's ("Blade Runner (2007)", the Final Cut's year) holds when the NFO names it too. */
    @Test
    fun anIdYearsApartIsTrustedWhenTheNfoAgrees() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(BLADE_RUNNER, "Blade Runner", 1982, imdbId = "tt0083658")
        nas.put("Blade Runner (2007) {tmdb-78}.mkv")
        val nfo = "<movie><title>Blade Runner</title><uniqueid type=\"imdb\">tt0083658</uniqueid></movie>"
        nas.putText("Blade Runner (2007) {tmdb-78}.nfo", nfo)
        val pass = bench.pass()

        assertEquals(BLADE_RUNNER, bench.item(films, "Blade Runner (2007) {tmdb-78}.mkv").tmdbId)
        assertEquals(listOf("movie/$BLADE_RUNNER"), pass.requests)
    }

    @Test
    fun aMatchedFilmWhoseNameCarriesItsOwnIdAsksTmdbNothing() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(DUNE, "Dune", 2021)
        nas.put("Dune (2021).mkv")
        bench.scan()
        val dune = bench.item(films, "Dune (2021).mkv")

        nas.rename("Dune (2021).mkv", "Dune (2021) {tmdb-438631}.mkv")
        assertEquals(emptyList<String>(), bench.pass().requests)
        bench.later(NameIds.RETRY_MS)
        assertEquals("nor a day later", emptyList<String>(), bench.pass().requests)
        assertEquals(dune.id to DUNE, bench.item(films, "Dune (2021) {tmdb-438631}.mkv").let { it.id to it.tmdbId })
    }

    /** Radarr renamed a film TMDB's search took for another: the id in its name sets it right, with nothing of the other. */
    @Test
    fun aMatchedFilmWhoseNameNamesAnotherIdTakesIt() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(DUNE_DRIFTER, "Dune", 2021)
        tmdb.editMovie(DUNE_DRIFTER) { it.copy(tagline = "Stranded", runtime = 79) }
        tmdb.movie(DUNE, "Dune : Première partie", 2021)
        nas.put("Dune (2021).mkv")
        bench.scan()
        assertEquals(DUNE_DRIFTER, bench.item(films, "Dune (2021).mkv").tmdbId)

        nas.rename("Dune (2021).mkv", "Dune (2021) {tmdb-438631}.mkv")
        val pass = bench.pass()
        val dune = bench.item(films, "Dune (2021) {tmdb-438631}.mkv")
        assertEquals(DUNE, dune.tmdbId)
        assertEquals("Dune : Première partie", dune.title)
        assertEquals("nothing of the other film", null to null, dune.tagline to dune.runtimeMin)
        assertEquals(listOf("movie/$DUNE"), pass.requests)
    }

    @Test
    fun aFileRenamedToCarryItsIdKeepsItsRowAndIsReadAtOnce() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(DUNE, "Dune : Première partie", 2021)
        nas.put("Dune (2021).mkv")
        bench.scan()
        val dune = bench.item(films, "Dune (2021).mkv")
        assertNull(dune.tmdbId)
        dao.setFavorite(dune.id, true)

        nas.rename("Dune (2021).mkv", "Dune (2021) {tmdb-438631}.mkv")
        bench.scan()
        val renamed = bench.item(films, "Dune (2021) {tmdb-438631}.mkv")
        assertEquals(dune.id, renamed.id)
        assertEquals(DUNE, renamed.tmdbId)
        assertTrue(renamed.favorite)
    }

    @Test
    fun anIdTmdbDoesNotKnowIsAskedAgainADayLater() = runTest {
        val films = bench.folder("Films")
        nas.put("Dune (2021) {tmdb-438631}.mkv")
        bench.scan()
        assertEquals(MatchState.NOT_FOUND, bench.item(films, "Dune (2021) {tmdb-438631}.mkv").matchState)
        bench.assertQuiet()

        // Listed on TMDB since: asked again as a title to identify ([Reidentify]), by its name's id first.
        tmdb.movie(DUNE, "Dune", 2021)
        bench.later(NameIds.RETRY_MS - 1)
        assertEquals("not within the day", emptyList<String>(), bench.pass(auto = true).requests)
        bench.later(1)
        val pass = bench.pass(auto = true)
        assertEquals("movie/$DUNE", pass.requests.first())
        assertEquals("never the NAS", emptyList<String>(), pass.reads)
        assertEquals(DUNE, bench.item(films, "Dune (2021) {tmdb-438631}.mkv").tmdbId)
    }

    /** A film a name's id found but did not trust is the first candidate offered when TMDB's search finds no other. */
    @Test
    fun aNameIdNotTrustedIsTheFirstCandidateOffered() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(AVATAR, "Avatar", 2009)
        nas.put("Vacances à Biarritz (2019) {tmdb-19995}.mkv")
        bench.scan()

        val holidays = bench.item(films, "Vacances à Biarritz (2019) {tmdb-19995}.mkv")
        assertEquals(null to MatchState.UNSURE, holidays.tmdbId to holidays.matchState)
        assertEquals(listOf(AVATAR), dao.suggestions(holidays.id).map { it.tmdbId })
    }

    /** A show 0.3.0 matched by its title, its folder's TVDB id (Sonarr's TRaSH naming) read by no version before. */
    @Test
    fun aShowMatchedByItsTitleBeforeTakesItsFoldersTvdbId() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(OTHER_SEVERANCE, "Severance", 2022)
        tmdb.season(OTHER_SEVERANCE, 1, episodes = 2)
        tmdb.editShow(OTHER_SEVERANCE) { it.copy(tagline = "Another show") }
        val dir = "Severance (2022) {tvdb-371980}/Season 1"
        showsNas.put("$dir/Severance - S01E01.mkv")
        showsNas.put("$dir/Severance - S01E02.mkv")
        bench.scan()
        assertEquals(OTHER_SEVERANCE, bench.showOf(shows, "$dir/Severance - S01E01.mkv").tmdbId)
        // As 0.3.0 left it, matched by its title: no version read the id in its folder's name.
        bench.db.openHelper.writableDatabase.execSQL("UPDATE items SET hintKey = NULL")

        tmdb.show(SEVERANCE, "Severance", 2022, tvdbId = SEVERANCE_TVDB)
        tmdb.seasons[SEVERANCE to 1] = TmdbSeason(1, name = "Saison 1", episodes = (1..2).map { TmdbEpisode(it, name = "Bon $it") })
        bench.later(NameIds.RETRY_MS)
        bench.scan()

        val show = bench.showOf(shows, "$dir/Severance - S01E01.mkv")
        assertEquals(SEVERANCE, show.tmdbId)
        assertNull("nothing of the other show", show.tagline)
        assertEquals(listOf("Bon 1", "Bon 2"), dao.episodesOfSeries(show.id).map { it.title })
        assertEquals("Saison 1", dao.season(show.id, 1)?.title)
    }

    /**
     * Sonarr's folder named with its id while TMDB did not list it yet: once it does, the show asked again
     * ([Reidentify]) by its folder's id gives every episode its details, the NAS never read.
     */
    @Test
    fun aShowListedLaterIsMatchedByItsFolderIdWhenAskedAgain() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        val dir = "Severance (2022) {tvdb-371980}/Season 1"
        (1..3).forEach { showsNas.put("$dir/Severance - S01E0$it.mkv") }
        bench.scan()
        val before = bench.showOf(shows, "$dir/Severance - S01E01.mkv")
        assertEquals(null to MatchState.NOT_FOUND, before.tmdbId to before.matchState)

        tmdb.show(SEVERANCE, "Severance", 2022, tvdbId = SEVERANCE_TVDB)
        tmdb.season(SEVERANCE, 1, episodes = 3)
        bench.later(NameIds.RETRY_MS)
        val pass = bench.pass()

        val show = bench.showOf(shows, "$dir/Severance - S01E01.mkv")
        assertEquals(SEVERANCE, show.tmdbId)
        assertEquals(listOf("Episode 1.1", "Episode 1.2", "Episode 1.3"), dao.episodesOfSeries(show.id).map { it.title })
        assertEquals("the season asked once", 1, pass.requests.count { it == "tv/$SEVERANCE/season/1" })
        assertEquals("never the NAS", emptyList<String>(), pass.reads)
        assertEquals("then nothing", emptyList<String>(), bench.pass().requests)
    }

    /**
     * A show TMDB found nothing for, named since by a tvshow.nfo added beside it: the scan reading that NFO matches it
     * and gives every episode its season's details in the transaction saving it, none of them read.
     */
    @Test
    fun aShowNamedByAnNfoAddedSinceGetsAllItsEpisodesInTheScan() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        (1..3).forEach { showsNas.put("Severance/Season 1/Severance - S01E0$it.mkv") }
        bench.scan()
        assertEquals(MatchState.NOT_FOUND, bench.showOf(shows, "Severance/Season 1/Severance - S01E01.mkv").matchState)

        tmdb.show(SEVERANCE, "Severance", 2022)
        tmdb.season(SEVERANCE, 1, episodes = 3)
        showsNas.putText("Severance/tvshow.nfo", "<tvshow><title>Severance</title><tmdbid>$SEVERANCE</tmdbid></tvshow>")
        val pass = bench.pass()

        val show = bench.showOf(shows, "Severance/Season 1/Severance - S01E01.mkv")
        assertEquals(SEVERANCE, show.tmdbId)
        assertEquals(listOf("Episode 1.1", "Episode 1.2", "Episode 1.3"), dao.episodesOfSeries(show.id).map { it.title })
        assertEquals("the season asked once", 1, pass.requests.count { it == "tv/$SEVERANCE/season/1" })
        assertEquals("its NFO alone read", listOf("Severance/tvshow.nfo"), pass.reads)
    }

    /** A show's folder named in English, TMDB's names French: its alternative titles hold the folder's, its id is trusted. */
    @Test
    fun aShowFolderIdOfAShowNamedOtherwiseIsTrustedByItsAlternativeTitles() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(BUREAU, "Le Bureau des légendes", 2015)
        tmdb.showAlternatives[BUREAU] = listOf("The Bureau")
        tmdb.season(BUREAU, 1, episodes = 1)
        val pilot = "The Bureau (2015) {tmdb-62649}/Season 1/The Bureau - S01E01.mkv"
        showsNas.put(pilot)
        val pass = bench.pass()

        assertEquals("Le Bureau des légendes", bench.showOf(shows, pilot).title)
        assertEquals(
            listOf("tv/$BUREAU", "tv/$BUREAU/alternative_titles", "tv/$BUREAU/episode_groups", "tv/$BUREAU/season/1"),
            pass.requests,
        )
    }

    /** A film 0.3.0 took for another by its title, its name's IMDb id (Jellyfin's naming) read by no version before. */
    @Test
    fun aFilmMatchedByItsTitleBeforeTakesItsNamesImdbId() = runTest {
        val films = bench.folder("Films")
        tmdb.movie(DUNE_DRIFTER, "Dune", 2021)
        nas.put("Dune (2021) [imdbid-tt1160419].mkv")
        bench.scan()
        assertEquals(DUNE_DRIFTER, bench.item(films, "Dune (2021) [imdbid-tt1160419].mkv").tmdbId)
        bench.db.openHelper.writableDatabase.execSQL("UPDATE items SET hintKey = NULL")

        tmdb.movie(DUNE, "Dune : Première partie", 2021, imdbId = "tt1160419")
        bench.later(NameIds.RETRY_MS)
        bench.scan()
        val dune = bench.item(films, "Dune (2021) [imdbid-tt1160419].mkv")
        assertEquals(DUNE, dune.tmdbId)
        assertEquals("imdb:tt1160419", dune.hintKey)
    }

    /** After an upgrade, every film named with an id it was not found by is due: a few per pass, then none. */
    @Test
    fun aLibraryNamedWithIdsIsAskedAFewTitlesAPass() = runTest {
        bench.folder("Films")
        repeat(TITLES) { i ->
            tmdb.movie(FIRST + i, "Film $i", 2020, imdbId = imdb(i))
            nas.put("Film $i (2020) [imdbid-${imdb(i)}].mkv")
        }
        bench.scan()
        // As 0.3.0 left them, matched by their titles: no version read the ids in their names.
        bench.db.openHelper.writableDatabase.execSQL("UPDATE items SET hintKey = NULL")
        bench.later(NameIds.RETRY_MS)

        assertEquals(NameIds.PER_PASS, bench.pass().requests.count { it.startsWith("find/") })
        assertEquals(TITLES - NameIds.PER_PASS, bench.pass().requests.count { it.startsWith("find/") })
        bench.assertQuiet()
    }

    private fun imdb(i: Int) = "tt%07d".format(FIRST + i)

    private companion object {
        const val DUNE = 438631
        const val LYNCH = 841
        const val PART_TWO = 693134
        const val DUNE_DRIFTER = 777
        const val COURGETTE = 1399
        const val BUREAU = 62649
        const val AVATAR = 19995
        const val CHIHIRO = 129
        const val BLADE_RUNNER = 78
        const val SEVERANCE = 95396
        const val OTHER_SEVERANCE = 1
        const val SEVERANCE_TVDB = 371980
        const val TITLES = 45
        const val FIRST = 1000
    }
}
