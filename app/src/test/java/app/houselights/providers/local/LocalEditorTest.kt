package app.houselights.providers.local

import app.houselights.core.ArtworkKind
import app.houselights.core.FailureKind
import app.houselights.core.MatchCandidate
import app.houselights.core.MatchDoubt
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.ToIdentify
import app.houselights.core.TmdbKeyRefusedException
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

    private fun editor(tmdb: TmdbClient? = bench.tmdb.client()) = LocalEditor(bench.dao, bench.scanner) { tmdb }

    private companion object {
        const val DUNE = 438631
        const val LYNCH = 841
        const val HOUR_MS = 60 * 60_000L
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

    /** Left as it is, a title says so and is no longer to identify; back to automatic, it is to identify again. */
    @Test
    fun aTitleLeftAsItIsSaysSoUntilPutBackToAutomatic() = runTest {
        val holidays = film("Vacances à Biarritz (2019).mkv")
        val editor = editor()
        assertEquals(MatchDoubt.NOT_FOUND, editor.identity(holidays).doubt)
        assertFalse(editor.identity(holidays).kept)

        editor.keepUnidentified(holidays)
        val kept = editor.identity(holidays)
        assertTrue(kept.kept)
        assertNull("no longer to identify", kept.doubt)
        assertEquals(emptyList<ToIdentify>(), editor.toIdentify())

        editor.identify(holidays, null)
        val back = editor.identity(holidays)
        assertFalse(back.kept)
        assertEquals(MatchDoubt.NOT_FOUND, back.doubt)
        assertEquals(listOf(holidays.id), editor.toIdentify().map { it.item.id })
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

    /** Nothing failed: there is nothing to list before the title is identified. */
    @Test
    fun aTitleNotIdentifiedHasNoImagesToList() = runTest {
        val film = film("Unknown (2001)/Unknown.mkv")
        val choices = editor().artwork(film, ArtworkKind.POSTER)
        assertFalse(choices.onlineAvailable)
        assertNull(choices.onlineError)
    }
}
