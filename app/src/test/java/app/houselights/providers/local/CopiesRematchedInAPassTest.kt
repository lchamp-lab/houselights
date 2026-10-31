package app.houselights.providers.local

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the user chose on a film stays the film's when a pass matches its rows anew, whose copies are grouped only at its
 * end: several rows of one film leaving or joining it in one pass, a film found at last, a film left unmatched a while.
 */
@RunWith(RobolectricTestRunner::class)
class CopiesRematchedInAPassTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private val tmdb get() = bench.tmdb
    private val dao get() = bench.dao
    private val nasB = FakeRemoteBrowser()
    private val nasC = FakeRemoteBrowser()

    @Before
    fun open() {
        bench = ScanBench(temp.newFolder("artwork"))
        tmdb.movie(DUNE, "Dune", 2021)
        tmdb.movie(PART_TWO, "Dune: Part Two", 2024)
        tmdb.movie(ARRIVAL, "Arrival", 2016)
    }

    @After
    fun close() = bench.close()

    private fun nfo(id: Int, title: String) = "<movie><title>$title</title><tmdbid>$id</tmdbid></movie>"

    private fun nfoOf(film: String) = film.replace(".mkv", ".nfo")

    /** The film's choices, as held by its main row: a poster, and [copy] to play. */
    private suspend fun choose(main: ItemEntity, copy: Long) {
        dao.setUserPoster(main.id, POSTER)
        dao.setVersionChoice(main.id, copy)
    }

    private suspend fun row(id: Long) = checkNotNull(dao.item(id))

    private suspend fun assertHoldsTheFilm(id: Long, film: Int, choice: Long) {
        val row = row(id)
        assertEquals(film, row.tmdbId)
        assertNull("the film's main row", row.versionOf)
        assertEquals(POSTER, row.userPosterPath)
        assertEquals("the copy chosen is still the one to play", choice, row.versionChoice)
    }

    /** Dune's main row and a copy named other films in one pass: the third copy holds Dune, with what was chosen on it. */
    @Test
    fun twoRowsOfAFilmLeavingInOnePassLeaveItToTheThird() = runTest {
        val a = bench.folder("A")
        val b = bench.folder("B", on = nasB)
        val c = bench.folder("C", on = nasC)
        bench.nas.put(HD)
        nasB.put(UHD)
        nasC.put(THIRD)
        bench.scan()
        val main = bench.item(a, HD)
        val third = bench.item(c, THIRD)
        assertEquals(main.id, third.versionOf)
        choose(main, third.id)

        bench.nas.putText(nfoOf(HD), nfo(PART_TWO, "Dune: Part Two"))
        nasB.putText(nfoOf(UHD), nfo(ARRIVAL, "Arrival"))
        bench.scan()

        assertHoldsTheFilm(third.id, DUNE, choice = third.id)
        assertNull(row(bench.item(b, UHD).id).userPosterPath)
    }

    /**
     * Both rows of Dune named Part Two in one pass, the copy read first: nothing is left of Dune to hold its choices,
     * so its main row keeps them, Part Two's main row now.
     */
    @Test
    fun aFilmWhollyRetaggedKeepsItsChoices() = runTest {
        val b = bench.folder("B", on = nasB)
        nasB.put(HD)
        bench.scan()
        val main = bench.item(b, HD)
        val a = bench.folder("A")
        bench.nas.put(UHD)
        bench.scan()
        val copy = bench.item(a, UHD)
        assertEquals(main.id, copy.versionOf)
        choose(main, copy.id)

        nasB.putText(nfoOf(HD), nfo(PART_TWO, "Dune: Part Two"))
        bench.nas.putText(nfoOf(UHD), nfo(PART_TWO, "Dune: Part Two"))
        bench.scan()

        assertHoldsTheFilm(main.id, PART_TWO, choice = copy.id)
        assertEquals(main.id, row(copy.id).versionOf)
    }

    /**
     * Both rows of Dune named Part Two in one pass, the main row read first: it leaves Dune's choices to the copy, the
     * last of Dune, which brings them back to it in Part Two.
     */
    @Test
    fun aFilmWhollyRetaggedMainRowFirstKeepsItsChoices() = runTest {
        val a = bench.folder("A")
        val b = bench.folder("B", on = nasB)
        bench.nas.put(HD)
        nasB.put(UHD)
        bench.scan()
        val main = bench.item(a, HD)
        val copy = bench.item(b, UHD)
        choose(main, copy.id)

        bench.nas.putText(nfoOf(HD), nfo(PART_TWO, "Dune: Part Two"))
        nasB.putText(nfoOf(UHD), nfo(PART_TWO, "Dune: Part Two"))
        bench.scan()

        assertHoldsTheFilm(main.id, PART_TWO, choice = copy.id)
        assertEquals(main.id, row(copy.id).versionOf)
    }

    /** A film alone of its kind, named another by an NFO written since, keeps the poster chosen on it, as before copies. */
    @Test
    fun aFilmWithoutCopiesNamedAnotherByAScanKeepsItsPoster() = runTest {
        val a = bench.folder("A")
        bench.nas.put(HD)
        bench.scan()
        val film = bench.item(a, HD)
        dao.setUserPoster(film.id, POSTER)

        bench.nas.putText(nfoOf(HD), nfo(PART_TWO, "Dune: Part Two"))
        bench.scan()

        assertEquals(PART_TWO, row(film.id).tmdbId)
        assertEquals(POSTER, row(film.id).userPosterPath)
    }

    /**
     * Arrival's former main row, a copy since an older row joined Arrival, named Dune by a scan: the Arrival poster it
     * held unseen does not come along to Dune, which never showed it.
     */
    @Test
    fun aCopysUnseenChoicesStayOutOfTheFilmItJoins() = runTest {
        val a = bench.folder("A")
        val b = bench.folder("B", on = nasB)
        bench.nas.put(HD)
        bench.nas.put(UNKNOWN)
        nasB.put(ARRIVAL_FILE)
        bench.scan()
        val dune = bench.item(a, HD)
        val older = bench.item(a, UNKNOWN)
        val arrival = bench.item(b, ARRIVAL_FILE)
        dao.setUserPoster(arrival.id, ARRIVAL_POSTER)
        bench.nas.putText(nfoOf(UNKNOWN), nfo(ARRIVAL, "Arrival"))
        bench.scan()
        assertEquals("Arrival's main row now", older.id, row(arrival.id).versionOf)
        assertEquals(ARRIVAL_POSTER, row(older.id).userPosterPath)

        nasB.putText(nfoOf(ARRIVAL_FILE), nfo(DUNE, "Dune"))
        bench.scan()

        assertEquals(dune.id, row(arrival.id).versionOf)
        assertNull(row(dune.id).userPosterPath)
        assertEquals("Arrival keeps it", ARRIVAL_POSTER, row(older.id).userPosterPath)
    }

    /** A lone Arrival named Dune by a scan, Dune having chosen a backdrop: Dune shows its own choices, Arrival's none. */
    @Test
    fun aLoneFilmJoiningAFilmWithChoicesBringsNone() = runTest {
        val a = bench.folder("A")
        val b = bench.folder("B", on = nasB)
        bench.nas.put(HD)
        nasB.put(ARRIVAL_FILE)
        bench.scan()
        val dune = bench.item(a, HD)
        val lone = bench.item(b, ARRIVAL_FILE)
        dao.setUserPoster(lone.id, ARRIVAL_POSTER)
        dao.setUserBackdrop(dune.id, DUNE_BACKDROP)

        nasB.putText(nfoOf(ARRIVAL_FILE), nfo(DUNE, "Dune"))
        bench.scan()

        assertEquals(dune.id, row(lone.id).versionOf)
        assertNull(row(dune.id).userPosterPath)
        assertEquals(DUNE_BACKDROP, row(dune.id).userBackdropPath)
    }

    /** Two rows older than Dune's main row named Dune in one pass: the oldest ends its main row, with Dune's choices. */
    @Test
    fun twoOlderRowsJoiningInOnePassLeaveTheFilmToTheOldest() = runTest {
        val a = bench.folder("A")
        val b = bench.folder("B", on = nasB)
        val c = bench.folder("C", on = nasC)
        bench.nas.put(UNKNOWN)
        nasB.put(UNKNOWN_UHD)
        bench.scan()
        val oldest = bench.item(a, UNKNOWN)
        val older = bench.item(b, UNKNOWN_UHD)
        assertNull("TMDB knows no film of its name", oldest.tmdbId)
        nasC.put(HD)
        bench.scan()
        val main = bench.item(c, HD)
        choose(main, main.id)

        bench.nas.putText(nfoOf(UNKNOWN), nfo(DUNE, "Dune"))
        nasB.putText(nfoOf(UNKNOWN_UHD), nfo(DUNE, "Dune"))
        bench.scan()

        assertHoldsTheFilm(oldest.id, DUNE, choice = main.id)
        assertEquals(oldest.id, row(older.id).versionOf)
        assertEquals(oldest.id, row(main.id).versionOf)
    }

    /** A film TMDB finds at last ([Reidentify]), older than the main row of that film, takes its choices as its main row. */
    @Test
    fun anOlderRowFoundAtLastTakesTheFilmsChoices() = runTest {
        val a = bench.folder("A")
        val b = bench.folder("B", on = nasB)
        val c = bench.folder("C", on = nasC)
        bench.nas.put(HOLIDAYS)
        bench.scan()
        val older = bench.item(a, HOLIDAYS)
        assertNull(older.tmdbId)
        tmdb.movie(HOLIDAYS_ID, "Vacances à Biarritz", 2019)
        nasB.put(HOLIDAYS_HD)
        nasB.putText(nfoOf(HOLIDAYS_HD), nfo(HOLIDAYS_ID, "Vacances à Biarritz"))
        nasC.put(HOLIDAYS_UHD)
        nasC.putText(nfoOf(HOLIDAYS_UHD), nfo(HOLIDAYS_ID, "Vacances à Biarritz"))
        bench.scan()
        val main = bench.item(b, HOLIDAYS_HD)
        val copy = bench.item(c, HOLIDAYS_UHD)
        assertEquals(main.id, copy.versionOf)
        choose(main, copy.id)

        bench.later(DAY_MS)
        bench.autoScan()

        assertHoldsTheFilm(older.id, HOLIDAYS_ID, choice = copy.id)
    }

    /** Dune's main row left unmatched a while (its NFO gone, TMDB out of reach), then Dune again: the film ends as it was. */
    @Test
    fun aMainRowLeftUnmatchedThenBackEndsAsItWas() = runTest {
        val a = bench.folder("A")
        val b = bench.folder("B", on = nasB)
        bench.nas.put(HD)
        bench.nas.putText(nfoOf(HD), nfo(DUNE, "Dune"))
        nasB.put(UHD)
        bench.scan()
        val main = bench.item(a, HD)
        val copy = bench.item(b, UHD)
        choose(main, copy.id)

        bench.nas.remove(nfoOf(HD))
        bench.tmdbOutOfReach()
        bench.scan()
        assertNull("unmatched a while", row(main.id).tmdbId)
        assertEquals("the copy holds Dune meanwhile", POSTER, row(copy.id).userPosterPath)
        bench.tmdbBack()
        bench.scan()

        assertHoldsTheFilm(main.id, DUNE, choice = copy.id)
    }

    /**
     * A row named Dune by a wrong NFO joins Dune, matched by hand on its main row, as Dune's oldest row: it is not
     * matched by hand for that, so its NFO corrected, it follows it out of Dune.
     */
    @Test
    fun anOlderRowJoiningAFilmMatchedByHandFollowsItsNfoOut() = runTest {
        val a = bench.folder("A")
        val b = bench.folder("B", on = nasB)
        bench.nas.put(UNKNOWN)
        bench.scan()
        val older = bench.item(a, UNKNOWN)
        nasB.put(HD)
        bench.scan()
        val main = bench.item(b, HD)
        bench.scanner.identify(main.id, DUNE)
        bench.nas.putText(nfoOf(UNKNOWN), nfo(DUNE, "Dune"))
        bench.scan()
        assertEquals(DUNE, row(older.id).tmdbId)
        assertNull("matched by its NFO, not by hand", row(older.id).userTmdbId)

        bench.nas.putText(nfoOf(UNKNOWN), nfo(PART_TWO, "Dune: Part Two"))
        bench.scan()

        assertEquals(PART_TWO, row(older.id).tmdbId)
        assertEquals("Dune's match by hand stays its own", DUNE, row(main.id).userTmdbId)
    }

    private companion object {
        const val DUNE = 1
        const val PART_TWO = 2
        const val ARRIVAL = 3
        const val HOLIDAYS_ID = 10
        const val POSTER = "/chosen-poster.jpg"
        const val ARRIVAL_POSTER = "/arrival-poster.jpg"
        const val DUNE_BACKDROP = "/dune-backdrop.jpg"
        const val ARRIVAL_FILE = "Arrival (2016).mkv"
        const val HD = "Dune (2021).mkv"
        const val UHD = "Dune (2021) 2160p.mkv"
        const val THIRD = "Dune (2021) 1080p.mkv"
        const val UNKNOWN = "Dune Part One (2021).mkv"
        const val UNKNOWN_UHD = "Dune Part One (2021) 2160p.mkv"
        const val HOLIDAYS = "Vacances à Biarritz (2019).mkv"
        const val HOLIDAYS_HD = "Biarritz HD.mkv"
        const val HOLIDAYS_UHD = "Biarritz UHD.mkv"
        const val DAY_MS = 24 * 60 * 60_000L
    }
}
