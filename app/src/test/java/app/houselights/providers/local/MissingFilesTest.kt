package app.houselights.providers.local

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a listing that could not see everything does to the rows of the files it did not list. */
class MissingFilesTest {
    private fun film(id: Long, path: String) = ItemEntity(
        id = id, kind = ItemKind.MOVIE, sourceId = 1, title = path, sortTitle = path, filePath = path, addedAt = 0,
    )

    private val dune = film(1, "Dune (2021)/Dune (2021).mkv")
    private val arrival = film(2, "Private/Arrival (2016)/Arrival (2016).mkv")
    private val heat = film(3, "Private films/Heat (1995).mkv")

    private fun settle(vararg unreadable: String) =
        MissingFiles.settle(SourceKind.MOVIES, listOf(dune, arrival, heat), Listing(emptyList(), emptyList(), unreadable.toList()), NOW)

    /** A file the NAS could not be asked about (gone between the listing and the question) is out of sight, not missing. */
    @Test
    fun aFileThatCouldNotBeAskedAboutKeepsItsRow() {
        assertEquals(listOf(arrival.id, heat.id), settle("Dune (2021)/Dune (2021).mkv").held)
    }

    @Test
    fun aFolderThatCouldNotBeReadKeepsTheRowsUnderItAlone() {
        assertEquals("a folder whose name only starts the same is not under it", listOf(dune.id, heat.id), settle("Private").held)
    }

    private fun stacked(rows: List<ItemEntity>, vararg paths: String) =
        MissingFiles.settle(SourceKind.MOVIES, rows, Stacks.of(SourceKind.MOVIES, paths.map { RemoteFile(it, 1_000) }), NOW)

    /**
     * "Kill Bill CD1" and "CD2" side by side read as the parts of one film, but TMDB or the user matched
     * each with a film of its own: two films, each row keeping its file and what the user did with it.
     */
    @Test
    fun filesMatchedWithTwoFilmsAreNotOne() {
        val files = arrayOf("Kill Bill CD1.avi", "Kill Bill CD2.avi")
        val first = film(1, "Kill Bill CD1.avi").copy(tmdbId = VOL_1)
        val second = film(2, "Kill Bill CD2.avi").copy(tmdbId = VOL_2, favorite = true, played = true)
        listOf(second, second.copy(tmdbId = VOL_1, userTmdbId = VOL_2)).forEach { other ->
            val outcome = stacked(listOf(first, other), *files)
            assertEquals("not given way", emptyList<Long>(), outcome.removed)
            assertEquals("its file is there", emptyList<Long>(), outcome.held)
            assertEquals("no part of the other", emptyList<Pair<Long, List<String>>>(), outcome.parts)
        }

        // The film has no row: neither stands for it.
        val third = film(3, "Kill Bill CD3.avi").copy(tmdbId = VOL_1)
        val alone = stacked(listOf(second, third), *files, "Kill Bill CD3.avi")
        assertEquals(emptyList<Pair<Long, RemoteFile>>(), alone.relinked)
        assertEquals(emptyList<Long>(), alone.removed + alone.held)

        // Matched with the same film, or one of them not at all: one film in two files.
        for (other in listOf(second.copy(tmdbId = VOL_1), second.copy(tmdbId = null))) {
            val one = stacked(listOf(first, other), *files)
            assertEquals(listOf(2L), one.removed)
            assertEquals(listOf(1L to listOf("Kill Bill CD2.avi")), one.parts)
        }
    }

    /**
     * A film's first part out of sight a while (a disk of a pool offline), its second still there, alone
     * in the film's folder: that part is not the film renamed. The film waits for its first part, its parts
     * as they were.
     */
    @Test
    fun aFilmWhoseFirstPartIsGoneWaitsForIt() {
        val dir = "Heat (1995)"
        val heat = film(1, "$dir/Heat (1995) CD1.avi").copy(parts = listOf("$dir/Heat (1995) CD2.avi"))
        val outcome = stacked(listOf(heat), "$dir/Heat (1995) CD2.avi")
        assertEquals(emptyList<Pair<Long, RemoteFile>>(), outcome.relinked)
        assertEquals(listOf(1L), outcome.held)
        assertEquals(emptyList<Pair<Long, List<String>>>(), outcome.parts)

        // Its first part renamed is the film still.
        val renamed = stacked(listOf(heat), "$dir/Heat (1995).avi")
        assertEquals(listOf(1L to RemoteFile("$dir/Heat (1995).avi", 1_000)), renamed.relinked)
    }

    private companion object {
        const val NOW = 1_000_000L
        const val VOL_1 = 24
        const val VOL_2 = 393
    }
}
