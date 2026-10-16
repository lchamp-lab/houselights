package app.houselights.providers.local

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Films in several files (CD1, CD2, part 1…), as a scan meets them: one title under the first part,
 * found by the film's NFO and artwork, whose parts may be renamed, added or gone without the title
 * being another.
 */
@RunWith(RobolectricTestRunner::class)
class ScanStacksTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private lateinit var films: SourceEntity
    private val nas get() = bench.nas

    @Before
    fun open() = runTest {
        bench = ScanBench(temp.newFolder("artwork"), tmdbKey = false)
        films = bench.folder("Films")
    }

    @After
    fun close() = bench.close()

    private suspend fun rows() = bench.dao.filesOf(films.id).sortedBy { it.filePath }

    private fun nfo(title: String) = "<movie><title>$title</title><year>1972</year></movie>".toByteArray()

    /** The Kodi way: the parts, and the film's NFO and poster under its folder. */
    private fun godfather() {
        nas.put("$DIR/Le Parrain (1972) CD1.mkv", size = 7_000_000_000)
        nas.put("$DIR/Le Parrain (1972) CD2.mkv", size = 6_000_000_000)
        nas.put("$DIR/Le Parrain (1972).nfo", size = nfo("The Godfather").size.toLong(), bytes = nfo("The Godfather"))
        nas.put("$DIR/poster.jpg", size = 100, bytes = ByteArray(100))
    }

    @Test
    fun twoPartsAreOneFilmDescribedByItsOwnNfo() = runTest {
        godfather()
        bench.scan()
        val film = rows().single()
        assertEquals("$DIR/Le Parrain (1972) CD1.mkv", film.filePath)
        assertEquals(listOf("$DIR/Le Parrain (1972) CD2.mkv"), film.parts)
        assertEquals("$DIR/Le Parrain (1972).nfo", film.nfoPath)
        assertEquals("The Godfather", film.title)
        assertTrue("its folder is its own", film.posterPath.isCachedArtwork())
        // Its size and what it holds are its first part's.
        assertEquals(7_000_000_000L, film.fileSize)
        assertFalse("the second part is not read", nas.reads.any { it.endsWith("CD2.mkv") })
    }

    @Test
    fun aPartRenamedIsTheSameFilm() = runTest {
        godfather()
        bench.scan()
        val film = rows().single()
        bench.dao.setFavorite(film.id, true)
        bench.dao.setPlayed(film.id, played = true, now = bench.now)

        nas.rename("$DIR/Le Parrain (1972) CD2.mkv", "$DIR/Le Parrain (1972) - Part 2.mkv")
        val told = bench.changesDuring { bench.scan() }
        val renamed = rows().single()
        assertEquals(film.id, renamed.id)
        assertEquals(listOf("$DIR/Le Parrain (1972) - Part 2.mkv"), renamed.parts)
        assertTrue(renamed.favorite && renamed.played)
        assertEquals(null, renamed.missingSince)
        assertTrue("screens show its parts", told > 0)

        // A part gone: the film of one file it now is keeps its row.
        nas.remove("$DIR/Le Parrain (1972) - Part 2.mkv")
        bench.scan()
        val alone = rows().single()
        assertEquals(film.id, alone.id)
        assertEquals(emptyList<String>(), alone.parts)
    }

    @Test
    fun aFilmScannedFileByFileBecomesOneTitleAtOnce() = runTest {
        // What a scan made of the parts before it told them apart: one title a file.
        godfather()
        val first = bench.dao.insert(row("$DIR/Le Parrain (1972) CD1.mkv", 7_000_000_000))
        bench.dao.insert(row("$DIR/Le Parrain (1972) CD2.mkv", 6_000_000_000))
        bench.dao.setFavorite(first, true)
        bench.scan()
        val film = rows().single()
        assertEquals(first, film.id)
        assertEquals(listOf("$DIR/Le Parrain (1972) CD2.mkv"), film.parts)
        assertTrue(film.favorite)
    }

    private fun row(path: String, size: Long) = ItemEntity(
        kind = ItemKind.MOVIE, sourceId = films.id, title = "Le Parrain", sortTitle = "parrain", year = 1972, filePath = path,
        fileSize = size, addedAt = bench.now,
    )

    @Test
    fun aFilmInPartsIsLookedForByItsName() = runTest {
        bench.withTmdb = true
        bench.tmdb.movie(949, "Heat", 1995)
        nas.put("Heat CD1.avi")
        bench.scan()
        assertEquals("named by its only file", "Heat CD1", rows().single().title)

        // Its second part arrives: the film is asked for again, by its name.
        nas.put("Heat CD2.avi")
        bench.scan()
        val heat = rows().single()
        assertEquals(949, heat.tmdbId)
        assertEquals("Heat", heat.title)
        assertEquals(listOf("Heat CD2.avi"), heat.parts)
    }

    @Test
    fun namesThatOnlyLookLikePartsAreFilmsOfTheirOwn() = runTest {
        nas.put("Dune Part 2.mkv")
        nas.put("Kill Bill Vol. 1 (2003).mkv")
        nas.put("Kill Bill Vol. 2 (2004).mkv")
        bench.scan()
        assertEquals(listOf("Dune Part 2", "Kill Bill Vol. 1", "Kill Bill Vol. 2"), rows().map { it.title })
        assertTrue(rows().all { it.parts.isEmpty() })
    }

    /** Two films named "… Part 1" and "… Part 2" without their year, among the others of a folder: two titles. */
    @Test
    fun twoFilmsNamedPartOneAndPartTwoAreTwoTitles() = runTest {
        nas.put("Hunger Games Mockingjay Part 1.mkv")
        nas.put("Hunger Games Mockingjay Part 2.mkv")
        nas.put("Top Gun Maverick.mkv")
        bench.scan()
        val titles = listOf("Hunger Games Mockingjay Part 1", "Hunger Games Mockingjay Part 2", "Top Gun Maverick")
        assertEquals(titles, rows().map { it.title })
        assertTrue(rows().all { it.parts.isEmpty() })
    }

    private companion object {
        const val DIR = "Le Parrain (1972)"
    }
}
