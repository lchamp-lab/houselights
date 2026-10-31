package app.houselights.providers.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Films in several files (CD1, disc 1…): one title under the first part, whatever happens to its parts. */
@RunWith(RobolectricTestRunner::class)
class ScanStacksTest {
    @get:Rule
    val scan = ScanBenchRule(tmdbKey = false)

    private val bench get() = scan.bench
    private lateinit var films: SourceEntity
    private val nas get() = bench.nas

    @Before
    fun open() = runTest {
        films = bench.folder("Films")
    }

    private suspend fun rows() = bench.dao.filesOf(films.id).sortedBy { it.filePath }

    private fun nfo(title: String) = "<movie><title>$title</title><year>1972</year></movie>"

    /** The Kodi way: the parts, and the film's NFO and poster under its folder. */
    private fun godfather() {
        nas.put("$DIR/Le Parrain (1972) CD1.mkv", size = 7_000_000_000)
        nas.put("$DIR/Le Parrain (1972) CD2.mkv", size = 6_000_000_000)
        nas.putText("$DIR/Le Parrain (1972).nfo", nfo("The Godfather"))
        nas.putImage("$DIR/poster.jpg")
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
        // Its size and media info are its first part's.
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

        nas.rename("$DIR/Le Parrain (1972) CD2.mkv", "$DIR/Le Parrain (1972) - Disc 2.mkv")
        val told = bench.changesDuring { bench.scan() }
        val renamed = rows().single()
        assertEquals(film.id, renamed.id)
        assertEquals(listOf("$DIR/Le Parrain (1972) - Disc 2.mkv"), renamed.parts)
        assertTrue(renamed.favorite && renamed.played)
        assertEquals(null, renamed.missingSince)
        assertTrue("screens show its parts", told > 0)

        // A part removed: the film, now in one file, keeps its row.
        nas.remove("$DIR/Le Parrain (1972) - Disc 2.mkv")
        bench.scan()
        val alone = rows().single()
        assertEquals(film.id, alone.id)
        assertEquals(emptyList<String>(), alone.parts)
    }

    /** While its first part is out of sight, the film's other parts make no title that TMDB might mismatch. */
    @Test
    fun theOtherPartsOfAFilmWhoseFirstIsOutOfSightMakeNoTitle() = runTest {
        godfather()
        bench.scan()
        val film = rows().single()
        bench.withTmdb = true
        val first = "$DIR/Le Parrain (1972) CD1.mkv"

        // Not queried: out of sight, not missing.
        nas.remove(first)
        nas.unreadable = listOf(first)
        bench.scan()
        assertEquals(listOf(film.id), rows().map { it.id })
        assertEquals(null, rows().single().missingSince)
        assertEquals("TMDB not asked for its second part", emptyList<String>(), bench.tmdb.requests)
    }

    /**
     * Its first part deleted, a film leaves as any title missing does, its second part then a film of its own given
     * back what the user did; the first part back, they are one film again, still as the user left it.
     */
    @Test
    fun aFilmWhoseFirstPartLeftIsOneFilmAgainOnceItIsBack() = runTest {
        godfather()
        bench.scan()
        bench.dao.setFavorite(rows().single().id, true)
        val first = "$DIR/Le Parrain (1972) CD1.mkv"

        nas.remove(first)
        bench.scan()
        val second = rows().single()
        assertEquals("$DIR/Le Parrain (1972) CD2.mkv", second.filePath)
        assertEquals(null, second.missingSince)
        assertTrue("given back", second.favorite)

        nas.put(first, size = 7_000_000_000)
        bench.scan()
        val back = rows().single()
        assertEquals(first, back.filePath)
        assertEquals(listOf("$DIR/Le Parrain (1972) CD2.mkv"), back.parts)
        assertTrue(back.favorite)
        assertEquals(null, back.missingSince)
    }

    @Test
    fun aFilmScannedFileByFileBecomesOneTitleAtOnce() = runTest {
        // Legacy rows: one title per file.
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

    @Test
    fun filesMatchedWithTwoFilmsStayTwoFilms() = runTest {
        nas.put("Kill Bill CD1.avi")
        nas.put("Kill Bill CD2.avi")
        val first = bench.dao.insert(row("Kill Bill CD1.avi", 1_000).copy(title = "Kill Bill: Vol. 1", tmdbId = 24))
        val second = bench.dao.insert(row("Kill Bill CD2.avi", 1_000).copy(title = "Kill Bill: Vol. 2", userTmdbId = 393, tmdbId = 393))
        bench.dao.setFavorite(second, true)
        bench.dao.setPlayed(second, played = true, now = bench.now)
        bench.scan()
        bench.scan()
        val (one, two) = rows()
        assertEquals(listOf(first, second), listOf(one.id, two.id))
        assertEquals(listOf("Kill Bill: Vol. 1", "Kill Bill: Vol. 2"), listOf(one.title, two.title))
        assertTrue(one.parts.isEmpty() && two.parts.isEmpty())
        assertTrue(two.favorite && two.played && two.userTmdbId == 393)
        assertEquals(null, two.missingSince)

        // Each is a title of its own: an NFO placed beside the second is read for it, not for the first.
        nas.putText("Kill Bill CD2.nfo", nfo("Kill Bill: Volume 2"))
        bench.scan()
        assertEquals(listOf("Kill Bill: Vol. 1", "Kill Bill: Volume 2"), rows().map { it.title })
    }

    private fun row(path: String, size: Long) = ItemEntity(
        kind = ItemKind.MOVIE,
        sourceId = films.id,
        title = "Le Parrain",
        sortTitle = "parrain",
        year = 1972,
        filePath = path,
        fileSize = size,
        addedAt = bench.now,
    )

    @Test
    fun aFilmInPartsIsLookedForByItsName() = runTest {
        bench.withTmdb = true
        bench.tmdb.movie(949, "Heat", 1995)
        nas.put("Heat CD1.avi")
        bench.scan()
        assertEquals("named by its only file", "Heat CD1", rows().single().title)

        // Its second part arrives: the film is looked up again, by its name.
        nas.put("Heat CD2.avi")
        bench.scan()
        val heat = rows().single()
        assertEquals(949, heat.tmdbId)
        assertEquals("Heat", heat.title)
        assertEquals(listOf("Heat CD2.avi"), heat.parts)
    }

    /** Unknown to TMDB and named by no NFO: the film is named as its files are, not as its first part. */
    @Test
    fun aFilmInPartsTmdbDoesNotKnowIsNamedByItsFiles() = runTest {
        bench.withTmdb = true
        nas.put("Heat CD1.avi")
        nas.put("Ronin CD1.avi")
        bench.scan()
        assertEquals(listOf("Heat CD1", "Ronin CD1"), rows().map { it.title })
        // A name given by other means stays.
        rows().last().let { bench.dao.saveScanned(it.copy(title = "Ronin (Frankenheimer)")) }

        nas.put("Heat CD2.avi")
        nas.put("Ronin CD2.avi")
        bench.scan()
        val (heat, ronin) = rows()
        assertEquals(listOf("Heat CD2.avi"), heat.parts)
        assertEquals("Heat", heat.title)
        assertEquals("heat", heat.sortTitle)
        assertEquals("Ronin (Frankenheimer)", ronin.title)
    }

    private companion object {
        const val DIR = "Le Parrain (1972)"
    }
}
