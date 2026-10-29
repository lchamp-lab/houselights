package app.houselights.providers.local

import androidx.test.core.app.ApplicationProvider
import app.houselights.core.ArtworkKind
import app.houselights.core.ArtworkOrigin
import app.houselights.core.FailureKind
import app.houselights.core.MatchCandidate
import app.houselights.core.MatchDoubt
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.TmdbKeyRefusedException
import app.houselights.core.ToIdentify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.util.Locale

/** The artwork a title can be given: when TMDB cannot list it, the reason shows in the choices and the log. */
@RunWith(RobolectricTestRunner::class)
class LocalEditorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench

    @Before
    fun open() {
        bench = ScanBench(temp.newFolder("artwork"))
    }

    @After
    fun close() = bench.close()

    private suspend fun film(path: String): MediaItem {
        val films = bench.folder("Films")
        bench.nas.put(path)
        bench.scan()
        val row = bench.item(films, path)
        return MediaItem(row.id.toString(), MediaKind.MOVIE, row.title)
    }

    private suspend fun show(path: String): MediaItem {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        bench.showsNas.put(path)
        bench.scan()
        val row = bench.showOf(shows, path)
        return MediaItem(row.id.toString(), MediaKind.SERIES, row.title)
    }

    private fun editor(tmdb: TmdbClient? = bench.tmdb.client()) = LocalEditor(bench.dao, bench.scanner) { tmdb }

    private companion object {
        const val DUNE = 438631
        const val LYNCH = 841
        const val KAAMELOTT = 2167
        const val PILOT = "Kaamelott/Livre 1/Kaamelott - S01E01.mkv"
        const val HOUR_MS = 60 * 60_000L

        /** TMDB's pictures of a language in a test, more than the editor offers of one. */
        const val PICTURES = 40
        const val PER_LANGUAGE = 30
    }

    /** With the key refused, the screen can say the key needs changing, not the connection. */
    @Test
    fun imagesTmdbDoesNotListSayWhy() = runTest {
        bench.tmdb.movie(949, "Heat", 1995)
        val heat = film("Heat (1995)/Heat.mkv")
        bench.tmdb.status = 401
        ShadowLog.clear()

        val choices = editor().artwork(heat, ArtworkKind.POSTER)
        assertFalse(choices.onlineAvailable)
        assertEquals(FailureKind.TMDB_KEY_REFUSED, choices.onlineError)
        val told = ShadowLog.getLogsForTag("LocalEditor").single()
        assertTrue(told.msg, heat.id in told.msg && "949" in told.msg)
        assertTrue(told.throwable is TmdbKeyRefusedException)
    }

    @Test
    fun withoutAKeyTheChoicesSaySo() = runTest {
        bench.tmdb.movie(949, "Heat", 1995)
        val heat = film("Heat (1995)/Heat.mkv")
        val choices = editor(tmdb = null).artwork(heat, ArtworkKind.BACKDROP)
        assertFalse(choices.onlineAvailable)
        assertEquals(FailureKind.NO_TMDB_KEY, choices.onlineError)
    }

    /**
     * A film's posters as its contact sheet offers them: after Automatique, the viewer's language, English, then those
     * without text, each language its 30 best rated, best first, and 80 in all, the last language cut there.
     */
    @Test
    fun postersOfferTheThirtyBestRatedOfEachLanguageEightyInAll() = runTest {
        bench.tmdb.movie(949, "Heat", 1995)
        val heat = film("Heat (1995)/Heat.mkv")
        bench.tmdb.imageSets[949] = TmdbImageSet(posters = pictures(null) + pictures("en") + pictures("fr"))

        val choices = editor(french()).artwork(heat, ArtworkKind.POSTER)
        assertEquals(80, choices.options.size)
        assertEquals("Automatique first", "/poster-949.jpg", choices.options.first().value)
        val offered = choices.options.drop(1)
        assertEquals(List(PER_LANGUAGE) { "fr" } + List(PER_LANGUAGE) { "en" } + List(19) { null }, offered.map { it.language })
        val best = (PICTURES - 1 downTo PICTURES - PER_LANGUAGE).map { "/fr-$it.jpg" }
        assertEquals("the best rated of each, the best first", best, offered.take(PER_LANGUAGE).map { it.value })
        assertTrue(offered.all { it.origin == ArtworkOrigin.ONLINE })
    }

    /** Backdrops are offered without text first, then in the viewer's language and English, each its 30 best. */
    @Test
    fun backdropsOfferThoseWithoutTextFirst() = runTest {
        bench.tmdb.movie(949, "Heat", 1995)
        val heat = film("Heat (1995)/Heat.mkv")
        bench.tmdb.imageSets[949] = TmdbImageSet(backdrops = pictures("en") + pictures("fr") + pictures(null))

        val choices = editor(french()).artwork(heat, ArtworkKind.BACKDROP)
        val languages = choices.options.map { it.language }
        assertEquals(List(PER_LANGUAGE) { null } + List(PER_LANGUAGE) { "fr" } + List(20) { "en" }, languages)
    }

    /** [PICTURES] of TMDB's pictures in [language] (none: without text), rated from 0 up as they come. */
    private fun pictures(language: String?) = (0 until PICTURES).map { n ->
        TmdbImage("/${language ?: "none"}-$n.jpg", language, voteAverage = n / 10.0, width = 2000, height = 3000)
    }

    /** TMDB as the editor asks it in French: the viewer's language. */
    private fun french() = TmdbClient("key", transport = bench.tmdb, locale = Locale.FRANCE)

    /**
     * The titles to identify, the latest added first, each with why, where it lies (its folder, then its file or its
     * show's folder) and, for one TMDB hesitated on, the titles it may be, the best first.
     */
    @Test
    fun theTitlesToIdentifySayWhyWhereTheyLieAndWhatTheyMayBe() = runTest {
        bench.folder("Films")
        bench.folder("Séries", SourceKind.SHOWS)
        bench.tmdb.movie(DUNE, "Dune", 2021, votes = 12_000)
        bench.tmdb.movie(LYNCH, "Dune", 1984, votes = 3_000)
        bench.tmdb.searches["dune"] = listOf(DUNE, LYNCH)
        bench.nas.put("Dune.mkv")
        bench.scan()
        bench.later(HOUR_MS)
        bench.showsNas.put("Kaamelott/Season 1/Kaamelott - S01E01.mkv")
        bench.scan()
        bench.later(HOUR_MS)
        bench.nas.put("Vacances/Biarritz (2019).mkv")
        bench.scan()

        val listed = editor().toIdentify()
        assertEquals("the latest first", listOf("Biarritz", "Kaamelott", "Dune"), listed.map { it.item.title })
        assertEquals(listOf("Films/Vacances/Biarritz (2019).mkv", "Séries/Kaamelott", "Films/Dune.mkv"), listed.map { it.place })
        assertEquals(listOf(MatchDoubt.NOT_FOUND, MatchDoubt.NOT_FOUND, MatchDoubt.UNSURE), listed.map { it.doubt })
        assertEquals(listOf(MediaKind.MOVIE, MediaKind.SERIES, MediaKind.MOVIE), listed.map { it.item.kind })
        assertEquals(listed.map { it.doubt }, listed.map { it.item.matchDoubt })
        val dune = listed.last()
        assertEquals(listOf("$DUNE" to 2021, "$LYNCH" to 1984), dune.suggestions.map { it.id to it.year })
        assertEquals("Dune", dune.best?.title)
        assertEquals(emptyList<MatchCandidate>(), listed.first().suggestions)
    }

    /** A title TMDB hesitated on holds its suggestions, the best first, to offer without a search; the next follows the list. */
    @Test
    fun theIdentityOfATitleToIdentifyHoldsItsSuggestionsAndTheNextFollowsTheList() = runTest {
        bench.folder("Films")
        bench.tmdb.movie(DUNE, "Dune", 2021, votes = 12_000)
        bench.tmdb.movie(LYNCH, "Dune", 1984, votes = 3_000)
        bench.tmdb.searches["dune"] = listOf(DUNE, LYNCH)
        bench.nas.put("Dune.mkv")
        bench.scan()
        bench.later(HOUR_MS)
        bench.nas.put("Vacances.mkv")
        bench.nas.put("Concert.mkv")
        bench.scan()

        val editor = editor()
        val listed = editor.toIdentify()
        val dune = listed.single { it.item.title == "Dune" }.item
        bench.tmdb.requests.clear()
        val identity = editor.identity(dune)
        assertEquals(listOf("$DUNE", "$LYNCH"), identity.suggestions.map { it.id })
        assertEquals("nothing asked of TMDB", emptyList<String>(), bench.tmdb.requests.toList())
        // The list's order, whatever leaves it: the latest added first, the last followed by none.
        assertEquals(listed.drop(1).map { it.item.id } + listOf(null), listed.map { editor.nextToIdentify(it.item) })
        editor.keepUnidentified(listed[1].item)
        assertEquals(listed[2].item.id, editor.nextToIdentify(listed[0].item))
        assertEquals(emptyList<MatchCandidate>(), editor.identity(listed.first().item).suggestions)
    }

    /**
     * Held while its file is missing, a title to identify still says why to its page and in Identifier, its suggestions
     * kept, though « À identifier » leaves it out until its file is back.
     */
    @Test
    fun aTitleHeldStillSaysWhyItIsToIdentifyOutOfTheList() = runTest {
        bench.tmdb.movie(DUNE, "Dune", 2021, votes = 12_000)
        bench.tmdb.movie(LYNCH, "Dune", 1984, votes = 3_000)
        bench.tmdb.searches["dune"] = listOf(DUNE, LYNCH)
        val dune = film("Dune.mkv")
        val editor = editor()
        val pages = LocalProvider(ApplicationProvider.getApplicationContext(), bench.dao, editor)
        bench.dao.setMissingSince(listOf(dune.id.toLong()), HOUR_MS)

        assertEquals(emptyList<ToIdentify>(), editor.toIdentify())
        val identity = editor.identity(dune)
        assertEquals(MatchDoubt.UNSURE, identity.doubt)
        assertEquals(listOf("$DUNE", "$LYNCH"), identity.suggestions.map { it.id })
        assertEquals(MatchDoubt.UNSURE to true, pages.item(dune.id).let { it.matchDoubt to it.held })

        bench.dao.setMissingSince(listOf(dune.id.toLong()), null)
        assertEquals(listOf(dune.id), editor.toIdentify().map { it.item.id })
    }

    /**
     * A show to identify whose every episode is held says it is held to its page, why it is to identify kept, and waits
     * outside « À identifier », as a film held does, until an episode is back.
     */
    @Test
    fun aShowEveryEpisodeOfWhichIsHeldWaitsOutOfTheList() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        val files = listOf(PILOT, "Kaamelott/Livre 1/Kaamelott - S01E02.mkv")
        files.forEach { bench.showsNas.put(it) }
        bench.scan()
        val episodes = files.map { bench.item(shows, it).id }
        val row = bench.showOf(shows, PILOT)
        val show = MediaItem(row.id.toString(), MediaKind.SERIES, row.title)
        val editor = editor()
        val pages = LocalProvider(ApplicationProvider.getApplicationContext(), bench.dao, editor)
        assertEquals(listOf(show.id), editor.toIdentify().map { it.item.id })

        bench.dao.setMissingSince(episodes, HOUR_MS)
        assertEquals(emptyList<ToIdentify>(), editor.toIdentify())
        assertEquals(MatchDoubt.NOT_FOUND, editor.identity(show).doubt)
        assertEquals(MatchDoubt.NOT_FOUND to true, pages.item(show.id).let { it.matchDoubt to it.held })

        bench.dao.setMissingSince(episodes.take(1), null)
        assertEquals(listOf(show.id), editor.toIdentify().map { it.item.id })
        assertFalse(pages.item(show.id).held)
    }

    /**
     * Left as it is, a title says so, in Identifier and to its page (Modifier), and is no longer to identify; back to
     * automatic, it is to identify again.
     */
    @Test
    fun aTitleLeftAsItIsSaysSoUntilPutBackToAutomatic() = runTest {
        val holidays = film("Vacances à Biarritz (2019).mkv")
        val editor = editor()
        val pages = LocalProvider(ApplicationProvider.getApplicationContext(), bench.dao, editor)
        assertEquals(MatchDoubt.NOT_FOUND, editor.identity(holidays).doubt)
        assertFalse(editor.identity(holidays).kept)

        editor.keepUnidentified(holidays)
        val kept = editor.identity(holidays)
        assertTrue(kept.kept)
        assertNull("no longer to identify", kept.doubt)
        assertEquals(emptyList<ToIdentify>(), editor.toIdentify())
        assertEquals(null to true, pages.item(holidays.id).let { it.matchDoubt to it.keptUnidentified })

        editor.identify(holidays, null)
        val back = editor.identity(holidays)
        assertFalse(back.kept)
        assertEquals(MatchDoubt.NOT_FOUND, back.doubt)
        assertEquals(listOf(holidays.id), editor.toIdentify().map { it.item.id })
        assertEquals(MatchDoubt.NOT_FOUND to false, pages.item(holidays.id).let { it.matchDoubt to it.keptUnidentified })
    }

    /** Choosing a title undoes leaving it as it is. */
    @Test
    fun aTitleLeftAsItIsAndThenChosenIsMatched() = runTest {
        bench.tmdb.movie(949, "Heat", 1995)
        val holidays = film("Vacances à Biarritz (2019).mkv")
        val editor = editor()
        editor.keepUnidentified(holidays)
        editor.identify(holidays, editor.search(holidays, "Heat", 1995).single())
        val row = checkNotNull(bench.dao.item(holidays.id.toLong()))
        assertEquals(949 to false, row.tmdbId to row.userNoMatch)
        assertFalse(editor.identity(holidays).kept)
    }

    /** A show left as it is, put back to automatic, is to identify again, as a film is. */
    @Test
    fun aShowLeftAsItIsSaysSoUntilPutBackToAutomatic() = runTest {
        val kaamelott = show(PILOT)
        val editor = editor()
        editor.keepUnidentified(kaamelott)
        assertTrue(editor.identity(kaamelott).kept)
        assertEquals(emptyList<ToIdentify>(), editor.toIdentify())

        editor.identify(kaamelott, null)
        val back = editor.identity(kaamelott)
        assertFalse(back.kept)
        assertEquals(MatchDoubt.NOT_FOUND, back.doubt)
        assertEquals(listOf(kaamelott.id), editor.toIdentify().map { it.item.id })
    }

    /** Choosing a show undoes leaving it as it is. */
    @Test
    fun aShowLeftAsItIsAndThenChosenIsMatched() = runTest {
        val kaamelott = show(PILOT)
        val editor = editor()
        editor.keepUnidentified(kaamelott)
        bench.tmdb.show(KAAMELOTT, "Kaamelott", 2005)
        bench.tmdb.season(KAAMELOTT, 1, episodes = 1)
        editor.identify(kaamelott, editor.search(kaamelott, "Kaamelott", null).single())
        val row = checkNotNull(bench.dao.item(kaamelott.id.toLong()))
        assertEquals(KAAMELOTT to false, row.tmdbId to row.userNoMatch)
        assertFalse(editor.identity(kaamelott).kept)
    }

    /** Nothing failed: there is nothing to list before the title is identified. */
    @Test
    fun aTitleNotIdentifiedHasNoImagesToList() = runTest {
        val film = film("Unknown (2001)/Unknown.mkv")
        val choices = editor().artwork(film, ArtworkKind.POSTER)
        assertFalse(choices.onlineAvailable)
        assertNull(choices.onlineError)
    }
}
