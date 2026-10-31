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
 * What the user chose on a film (artwork, copy to play, arrival), held by its main row, stays the film's when a row of
 * it is matched anew: the main row matched as another film leaves it to the copy left, an older row matched as the film
 * takes it on as its main row.
 */
@RunWith(RobolectricTestRunner::class)
class CopiesMatchedAnewTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private lateinit var films: SourceEntity
    private val tmdb get() = bench.tmdb

    @Before
    fun open() {
        bench = ScanBench(temp.newFolder("artwork"))
        tmdb.movie(DUNE, "Dune", 2021)
        tmdb.movie(PART_TWO, "Dune: Part Two", 2024)
        tmdb.movie(ARRIVAL, "Arrival", 2016)
    }

    @After
    fun close() = bench.close()

    /** Dune and its 4K copy, grouped under the first; the film's choices on its main row, the copy to play the 4K. */
    private suspend fun dunes(): Pair<ItemEntity, ItemEntity> {
        bench.nas.put(HD)
        bench.nas.put(UHD)
        bench.scan()
        val main = bench.item(films, HD)
        val copy = bench.item(films, UHD)
        assertEquals("grouped under the first", main.id, copy.versionOf)
        bench.dao.setUserPoster(main.id, "/chosen-poster.jpg")
        bench.dao.setUserBackdrop(main.id, "/chosen-backdrop.jpg")
        bench.dao.setUserLogo(main.id, "/chosen-logo.png")
        bench.dao.setVersionChoice(main.id, copy.id)
        bench.dao.setAddedAt(copy.id, main.addedAt + ARRIVED_LATER)
        return main to copy
    }

    private fun assertDunesChoices(row: ItemEntity, choice: Long, addedAt: Long) {
        assertNull("the film's main row", row.versionOf)
        assertEquals("/chosen-poster.jpg", row.userPosterPath)
        assertEquals("/chosen-backdrop.jpg", row.userBackdropPath)
        assertEquals("/chosen-logo.png", row.userLogoPath)
        assertEquals("the copy chosen is still the one to play", choice, row.versionChoice)
        assertEquals("its place in recently added", addedAt, row.addedAt)
    }

    /** Identified as another film, Dune's main row leaves Dune's choices to its copy and arrives in Part Two bare. */
    @Test
    fun aMainRowIdentifiedAsAnotherFilmLeavesTheFilmsChoicesToItsCopy() = runTest {
        films = bench.folder("Films")
        val (main, copy) = dunes()

        bench.scanner.identify(main.id, PART_TWO)

        assertDunesChoices(checkNotNull(bench.dao.item(copy.id)), choice = copy.id, addedAt = main.addedAt)
        val moved = checkNotNull(bench.dao.item(main.id))
        assertEquals(PART_TWO, moved.tmdbId)
        assertNull(moved.versionOf)
        assertNull("Dune's poster is not Part Two's", moved.userPosterPath)
        assertNull("nor its copy to play", moved.versionChoice)
    }

    /** Identified as Dune, a row older than Dune's main row becomes its main row, showing Dune's choices, not its own. */
    @Test
    fun anOlderRowIdentifiedAsTheFilmTakesItsChoices() = runTest {
        films = bench.folder("Films")
        bench.nas.put(OLDER)
        bench.scan()
        val older = bench.item(films, OLDER)
        assertEquals(ARRIVAL, older.tmdbId)
        bench.dao.setUserPoster(older.id, "/arrival-poster.jpg")
        val (main, copy) = dunes()

        bench.scanner.identify(older.id, DUNE)

        assertDunesChoices(checkNotNull(bench.dao.item(older.id)), choice = copy.id, addedAt = older.addedAt)
        assertEquals("a copy now", older.id, bench.dao.item(main.id)?.versionOf)
    }

    /** Named Dune by an NFO written since, a row older than Dune's main row takes Dune's choices as its main row. */
    @Test
    fun anOlderRowAScanMatchesAsTheFilmTakesItsChoices() = runTest {
        films = bench.folder("Films")
        bench.nas.put(UNKNOWN)
        bench.scan()
        val older = bench.item(films, UNKNOWN)
        assertNull("TMDB knows no film of its name", older.tmdbId)
        val (main, copy) = dunes()

        bench.nas.putText(UNKNOWN.replace(".mkv", ".nfo"), "<movie><title>Dune</title><tmdbid>$DUNE</tmdbid></movie>")
        bench.scan()

        val matched = checkNotNull(bench.dao.item(older.id))
        assertEquals(DUNE, matched.tmdbId)
        assertDunesChoices(matched, choice = copy.id, addedAt = older.addedAt)
        assertEquals("a copy now", older.id, bench.dao.item(main.id)?.versionOf)
    }

    private companion object {
        const val DUNE = 1
        const val PART_TWO = 2
        const val ARRIVAL = 3
        const val HD = "Dune (2021).mkv"
        const val UHD = "Dune (2021) 2160p.mkv"
        const val OLDER = "Arrival (2016).mkv"
        const val UNKNOWN = "Dune Part One (2021).mkv"
        const val ARRIVED_LATER = 60_000L
    }
}
