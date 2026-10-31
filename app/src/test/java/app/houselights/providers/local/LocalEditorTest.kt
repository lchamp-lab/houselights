package app.houselights.providers.local

import androidx.test.core.app.ApplicationProvider
import app.houselights.core.ArtworkKind
import app.houselights.core.ArtworkNote
import app.houselights.core.ArtworkOrigin
import app.houselights.core.FailureKind
import app.houselights.core.MatchCandidate
import app.houselights.core.MatchDoubt
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.NotFoundException
import app.houselights.core.TmdbKeyRefusedException
import app.houselights.core.ToIdentify
import app.houselights.ui.edit.SectionName
import app.houselights.ui.edit.choiceSections
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

    /** Dune's saga as TMDB knows it, its poster and backdrop, and its two films the library holds. */
    private suspend fun duneSaga(): MediaItem {
        val films = bench.folder("Films")
        val saga = TmdbCollectionRef(DUNE_SAGA, "Dune - Saga")
        bench.tmdb.movie(DUNE, "Dune", 2021, collection = saga)
        bench.tmdb.movie(PART_TWO, "Dune Part Two", 2024, collection = saga)
        bench.tmdb.collections[DUNE_SAGA] = TmdbCollection(DUNE_SAGA, "Dune - Saga", posterPath = SAGA_POSTER, backdropPath = SAGA_BACKDROP)
        bench.nas.put("Dune (2021).mkv")
        bench.nas.put("Dune Part Two (2024).mkv")
        bench.scan()
        val key = checkNotNull(bench.item(films, "Dune (2021).mkv").collectionId)
        return MediaItem(LocalId.ofCollection(key), MediaKind.COLLECTION, "Dune")
    }

    /** « Nouvelle Vague », a saga two films' NFO files name, which TMDB does not know: its films are, alone. */
    private suspend fun newWave(): MediaItem {
        val films = bench.folder("Films")
        bench.tmdb.movie(BREATHLESS, "Breathless", 1960)
        bench.tmdb.movie(CONTEMPT, "Contempt", 1963)
        for (video in listOf("Breathless (1960).mkv", "Contempt (1963).mkv")) {
            bench.nas.put(video)
            bench.nas.putText(video.replace(".mkv", ".nfo"), "<movie><set><name>Nouvelle Vague</name></set></movie>")
        }
        bench.scan()
        val key = checkNotNull(bench.item(films, "Breathless (1960).mkv").collectionId)
        return MediaItem(LocalId.ofCollection(key), MediaKind.COLLECTION, "Nouvelle Vague")
    }

    private companion object {
        const val DUNE = 438631
        const val PART_TWO = 693134
        const val DUNE_SAGA = 726871
        const val SAGA_POSTER = "/dune-saga.jpg"
        const val SAGA_BACKDROP = "/dune-saga-wide.jpg"
        const val BREATHLESS = 269
        const val CONTEMPT = 266
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

    /**
     * A film's poster chosen is kept, and Automatique clears it; « Sans logo » over a logo of its own is kept as such; a
     * poster chosen that TMDB no longer lists stays offered, next to Automatique, and selected.
     */
    @Test
    fun aFilmsChoiceIsKeptAutomatiqueClearingIt() = runTest {
        bench.tmdb.movie(949, "Heat", 1995)
        val heat = film("Heat (1995)/Heat.mkv")
        val id = heat.id.toLong()
        bench.tmdb.imageSets[949] = TmdbImageSet(posters = pictures("fr").take(3), logos = pictures("fr").take(1))
        val editor = editor(french())

        val posters = editor.artwork(heat, ArtworkKind.POSTER)
        val chosen = posters.options.last()
        editor.setArtwork(heat, ArtworkKind.POSTER, chosen)
        assertEquals(chosen.value, bench.dao.item(id)?.userPosterPath)
        assertEquals(chosen, editor.artwork(heat, ArtworkKind.POSTER).selected)
        editor.setArtwork(heat, ArtworkKind.POSTER, checkNotNull(posters.automatic))
        assertNull("Automatique clears the choice", bench.dao.item(id)?.userPosterPath)

        bench.dao.update(checkNotNull(bench.dao.item(id)).copy(logoPath = "/logo-949.png"))
        val none = editor.artwork(heat, ArtworkKind.LOGO).options.single { it.origin == ArtworkOrigin.NONE }
        editor.setArtwork(heat, ArtworkKind.LOGO, none)
        assertEquals("", bench.dao.item(id)?.userLogoPath)
        assertEquals(none, editor.artwork(heat, ArtworkKind.LOGO).selected)

        bench.dao.setUserPoster(id, "/gone.jpg")
        val gone = editor.artwork(heat, ArtworkKind.POSTER)
        assertEquals("next to Automatique", "/gone.jpg", gone.options[1].value)
        assertEquals("/gone.jpg", gone.selected?.value)
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

    /**
     * A saga's posters (#48): Automatique, TMDB's poster of the saga, first; then TMDB's pictures of the saga by language,
     * the viewer's, English, none; then each film's, in release order, named by its title and year: the contact sheet's
     * sections, « Ses films » last. A saga's choice is its poster and its backdrop: no logo.
     */
    @Test
    fun aSagasPostersAreAutomatiqueThenTmdbsByLanguageThenOneAFilm() = runTest {
        val saga = duneSaga()
        bench.tmdb.imageSets[DUNE_SAGA] = TmdbImageSet(posters = pictures("en").take(2) + pictures("fr").take(3) + pictures(null).take(1))
        val editor = editor(french())
        assertTrue(editor.canEdit(saga))

        val choices = editor.artwork(saga, ArtworkKind.POSTER)
        assertEquals(SAGA_POSTER, choices.automatic?.value)
        assertEquals("the one in use, nothing chosen", choices.automatic, choices.selected)
        assertEquals(ArtworkNote.SAGA to true, choices.note to choices.onlineAvailable)
        val films = choices.options.takeLast(2)
        assertEquals(listOf("/poster-$DUNE.jpg", "/poster-$PART_TWO.jpg"), films.map { it.value })
        assertEquals(listOf(ArtworkOrigin.FILM, ArtworkOrigin.FILM), films.map { it.origin })
        assertEquals(listOf("Dune" to 2021, "Dune Part Two" to 2024), films.map { it.film to it.filmYear })
        val sections = choiceSections(choices)
        val languages = listOf("fr", "en", null).map { SectionName.Language(it) }
        assertEquals(listOf<SectionName>(SectionName.Head) + languages + SectionName.Films, sections.map { it.name })
        assertEquals(listOf(1, 3, 2, 1, 2), sections.map { it.options.size })
        val logo = runCatching { editor.artwork(saga, ArtworkKind.LOGO) }.exceptionOrNull()
        assertTrue("TMDB gives a collection no logo: $logo", logo is NotFoundException)
    }

    /** A saga's choice keeps « Ses films » whole: TMDB's pictures take what Automatique and its films leave of the 80, 30 a language. */
    @Test
    fun aSagasFilmsAreNeverCutTmdbsTakingWhatTheyLeave() = runTest {
        val saga = duneSaga()
        bench.tmdb.imageSets[DUNE_SAGA] = TmdbImageSet(posters = pictures(null) + pictures("en") + pictures("fr"))

        val choices = editor(french()).artwork(saga, ArtworkKind.POSTER)
        assertEquals(80, choices.options.size)
        val tmdb = choices.options.filter { it.origin == ArtworkOrigin.ONLINE }.drop(1)
        assertEquals(List(PER_LANGUAGE) { "fr" } + List(PER_LANGUAGE) { "en" } + List(17) { null }, tmdb.map { it.language })
        assertEquals(listOf("Dune", "Dune Part Two"), choices.options.takeLast(2).map { it.film })
    }

    /**
     * A picture chosen for a saga is kept by its key, its poster and its backdrop apart, and is the one in use; Automatique
     * clears it, and the saga's row of choices goes once both its pictures are automatic.
     */
    @Test
    fun aSagasChoiceIsKeptByItsKeyAutomatiqueClearingIt() = runTest {
        val saga = duneSaga()
        bench.tmdb.imageSets[DUNE_SAGA] = TmdbImageSet(posters = pictures("fr").take(2), backdrops = pictures(null).take(2))
        val editor = editor(french())
        val key = "tmdb:$DUNE_SAGA"

        val film = editor.artwork(saga, ArtworkKind.POSTER).options.last()
        editor.setArtwork(saga, ArtworkKind.POSTER, film)
        val wide = editor.artwork(saga, ArtworkKind.BACKDROP).options.first { it.language == null && it.value != SAGA_BACKDROP }
        editor.setArtwork(saga, ArtworkKind.BACKDROP, wide)
        assertEquals(SagaChoiceEntity(key, film.value, wide.value), bench.dao.sagaChoice(key))
        val chosen = editor.artwork(saga, ArtworkKind.POSTER)
        assertEquals(film.value to ArtworkOrigin.FILM, chosen.selected?.value to chosen.selected?.origin)

        editor.setArtwork(saga, ArtworkKind.POSTER, checkNotNull(chosen.automatic))
        assertEquals(SagaChoiceEntity(key, userBackdropPath = wide.value), bench.dao.sagaChoice(key))
        editor.setArtwork(saga, ArtworkKind.BACKDROP, checkNotNull(editor.artwork(saga, ArtworkKind.BACKDROP).automatic))
        assertNull(bench.dao.sagaChoice(key))
    }

    /**
     * A saga an NFO's set names and TMDB does not know has its films' pictures alone, Automatique its first film's, named by
     * it, then the others'; nothing asked of TMDB, nothing failed: the choice says why (#48).
     */
    @Test
    fun aSagaTmdbDoesNotKnowHasItsFilmsPicturesAlone() = runTest {
        val saga = newWave()
        bench.tmdb.requests.clear()

        val choices = editor().artwork(saga, ArtworkKind.POSTER)
        assertEquals(ArtworkNote.SAGA_UNKNOWN, choices.note)
        assertEquals(false to null, choices.onlineAvailable to choices.onlineError)
        assertEquals(listOf("/poster-$BREATHLESS.jpg", "/poster-$CONTEMPT.jpg"), choices.options.map { it.value })
        assertEquals(listOf("Breathless" to 1960, "Contempt" to 1963), choices.options.map { it.film to it.filmYear })
        assertEquals("Automatique, its first film's", choices.options.first(), choices.automatic)
        assertEquals(listOf(SectionName.Head, SectionName.Films), choiceSections(choices).map { it.name })
        assertEquals("nothing asked of TMDB", emptyList<String>(), bench.tmdb.requests.toList())
    }

    /** TMDB out of reach, or no key, a saga's choice keeps Automatique and its films, and says why TMDB's are missing. */
    @Test
    fun aSagaTmdbCannotListKeepsItsFilmsAndSaysWhy() = runTest {
        val saga = duneSaga()
        bench.tmdb.failingPath = "collection/"
        val offline = editor().artwork(saga, ArtworkKind.POSTER)
        assertEquals(ArtworkNote.SAGA to false, offline.note to offline.onlineAvailable)
        assertTrue(offline.onlineError != null)
        assertEquals(listOf(SAGA_POSTER, "/poster-$DUNE.jpg", "/poster-$PART_TWO.jpg"), offline.options.map { it.value })

        val keyless = editor(tmdb = null).artwork(saga, ArtworkKind.POSTER)
        assertEquals(FailureKind.NO_TMDB_KEY, keyless.onlineError)
        assertEquals(3, keyless.options.size)
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
