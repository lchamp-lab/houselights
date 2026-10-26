package app.houselights.providers.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a listing does to the rows of the files it did not list: out of sight under what it could not read, the later
 * parts of a film in several files, each part's row matched on its own or not; gone on the pass that misses them, a
 * sudden drop held, whatever the clock, until the files are back or the user answers.
 */
class MissingFilesTest {
    private fun film(id: Long, path: String) = KnownFile(id = id, filePath = path)

    private val dune = film(1, "Dune (2021)/Dune (2021).mkv")
    private val arrival = film(2, "Private/Arrival (2016)/Arrival (2016).mkv")
    private val heat = film(3, "Private films/Heat (1995).mkv")

    private fun settle(vararg unreadable: String) = MissingFiles.settle(
        SourceKind.MOVIES, listOf(dune, arrival, heat), Listing(emptyList(), emptyList(), unreadable.toList()),
    )

    /** A file the NAS could not be asked about (gone between listing and query) is out of sight, not missing. */
    @Test
    fun aFileThatCouldNotBeAskedAboutKeepsItsRow() {
        assertEquals(listOf(arrival.id, heat.id), settle("Dune (2021)/Dune (2021).mkv").removed)
    }

    @Test
    fun aFolderThatCouldNotBeReadKeepsTheRowsUnderItAlone() {
        assertEquals("a folder whose name only starts the same is not under it", listOf(dune.id, heat.id), settle("Private").removed)
    }

    private fun stacked(rows: List<KnownFile>, vararg paths: String) =
        MissingFiles.settle(SourceKind.MOVIES, rows, Stacks.of(SourceKind.MOVIES, paths.map { RemoteFile(it, 1_000) }))

    /** "Kill Bill CD1" and "CD2" read as one film, but each matched its own: two films, each keeping its row. */
    @Test
    fun filesMatchedWithTwoFilmsAreNotOne() {
        val files = arrayOf("Kill Bill CD1.avi", "Kill Bill CD2.avi")
        val first = film(1, "Kill Bill CD1.avi").copy(tmdbId = VOL_1)
        val second = film(2, "Kill Bill CD2.avi").copy(tmdbId = VOL_2)
        listOf(second, second.copy(tmdbId = VOL_1, userTmdbId = VOL_2)).forEach { other ->
            val outcome = stacked(listOf(first, other), *files)
            assertEquals("not given way", emptyList<Long>(), outcome.removed + outcome.absorbed)
            assertEquals("its file is there", emptyList<Long>(), outcome.held)
            assertEquals("no part of the other", emptyList<Pair<Long, List<String>>>(), outcome.parts)
        }

        // The film has no row: neither stands for it.
        val third = film(3, "Kill Bill CD3.avi").copy(tmdbId = VOL_1)
        val alone = stacked(listOf(second, third), *files, "Kill Bill CD3.avi")
        assertEquals(emptyList<Pair<Long, RemoteFile>>(), alone.relinked)
        assertEquals(emptyList<Long>(), alone.removed + alone.held + alone.absorbed)

        // Matched with the same film, or one of them not at all: one film in two files.
        for (other in listOf(second.copy(tmdbId = VOL_1), second.copy(tmdbId = null))) {
            val one = stacked(listOf(first, other), *files)
            assertEquals("a part of the film now, nothing of its own", listOf(2L), one.absorbed)
            assertEquals(emptyList<Long>(), one.removed)
            assertEquals(listOf(1L to listOf("Kill Bill CD2.avi")), one.parts)
        }
    }

    /**
     * First part gone, second alone in the folder: not a rename. The film is missing like any title, so it goes when
     * few are, its later part then a title of its own, and is held with the others in a sudden drop, its parts waiting.
     */
    @Test
    fun aFilmWhoseFirstPartIsGoneIsMissingLikeAnyTitle() {
        val dir = "Heat (1995)"
        val heat = film(100, "$dir/Heat (1995) CD1.avi").copy(parts = listOf("$dir/Heat (1995) CD2.avi"))
        val alone = stacked(listOf(heat), "$dir/Heat (1995) CD2.avi")
        assertEquals(emptyList<Pair<Long, RemoteFile>>(), alone.relinked)
        assertEquals(listOf(heat.id), alone.removed)
        assertEquals(emptyList<Pair<Long, List<String>>>(), alone.parts)

        val others = films(11)
        val drop = settled(others + heat, "$dir/Heat (1995) CD2.avi")
        assertEquals("held with the others", others.map { it.id } + heat.id, drop.held)
        val files = Stacks.of(SourceKind.MOVIES, listOf(RemoteFile("$dir/Heat (1995) CD2.avi", 1_000))).titles
        assertEquals(setOf("$dir/Heat (1995) CD2.avi"), MissingFiles.waitingParts(listOf(heat), files, drop.removed))

        // Its first part renamed is still the film.
        val renamed = stacked(listOf(heat), "$dir/Heat (1995).avi")
        assertEquals(listOf(heat.id to RemoteFile("$dir/Heat (1995).avi", 1_000)), renamed.relinked)
    }

    private fun films(count: Int) = (1L..count).map { film(it, "Film $it (2020).mkv") }

    private fun settled(rows: List<KnownFile>, vararg paths: String) =
        MissingFiles.settle(SourceKind.MOVIES, rows, Stacks.of(SourceKind.MOVIES, paths.map { RemoteFile(it, 1_000) }))

    /** [count] films of a folder of [titles], the rest listed. */
    private fun gone(count: Int, titles: Int): MissingFiles.Outcome {
        val rows = films(titles)
        return settled(rows, *rows.drop(count).map { it.filePath }.toTypedArray())
    }

    /** More than a quarter of the folder's titles, and ten at least: a sudden drop, held whole, nothing removed. */
    @Test
    fun aSuddenDropHoldsItsTitlesAndRemovesNothing() {
        val drop = gone(11, titles = 40)
        assertEquals((1L..11).toList(), drop.held)
        assertEquals(emptyList<Long>(), drop.removed)
        assertTrue("screens show them held", drop.changed)
        assertEquals("ten of twenty", (1L..10).toList(), gone(10, titles = 20).held)
        assertEquals("all of them, the folder still listing others", (1L..12).toList(), gone(12, titles = 22).held)
    }

    /** A quarter exactly, or fewer than ten however many of the folder they are: gone on the pass that misses them. */
    @Test
    fun belowADropMissingTitlesGoAtOnce() {
        val quarter = gone(10, titles = 40)
        assertEquals((1L..10).toList(), quarter.removed)
        assertEquals(emptyList<Long>(), quarter.held)
        val few = gone(9, titles = 12)
        assertEquals((1L..9).toList(), few.removed)
        assertEquals(emptyList<Long>(), few.held)
    }

    @Test
    fun theDropsEdges() {
        assertTrue(MissingFiles.isDrop(missing = 10, titles = 39))
        assertFalse("a quarter is no drop", MissingFiles.isDrop(missing = 10, titles = 40))
        assertFalse("nine are never one", MissingFiles.isDrop(missing = 9, titles = 9))
        assertTrue(MissingFiles.isDrop(missing = 1_000, titles = 3_999))
    }

    /** Held by a drop, still missing: they wait, however few are left and whatever time has passed; nothing new held. */
    @Test
    fun titlesHeldWaitForTheirFilesOrTheAnswer() {
        val held = films(3).map { it.copy(missingSince = NOW - YEAR) }
        val rows = held + films(20).drop(3)
        val outcome = settled(rows, *rows.drop(3).map { it.filePath }.toTypedArray())
        assertEquals(held.map { it.id }, outcome.waiting)
        assertEquals(emptyList<Long>(), outcome.removed + outcome.held)
        assertFalse("nothing changed for screens", outcome.changed)
    }

    /** A title held whose file is listed again is back, as it was; screens no longer mark it. */
    @Test
    fun aTitleHeldWhoseFileIsBackIsAsItWas() {
        val held = film(1, "Film 1 (2020).mkv").copy(missingSince = NOW)
        val outcome = settled(listOf(held), held.filePath)
        assertEquals(listOf(held.id), outcome.back)
        assertTrue(outcome.changed)
    }

    /** Ten more missing while others are held: a new drop, judged on what is newly missing, the folder's titles all counted. */
    @Test
    fun aNewDropIsJudgedOnWhatIsNewlyMissing() {
        val held = films(12).map { it.copy(missingSince = NOW) }
        val more = (13L..30).map { film(it, "Film $it (2020).mkv") }
        val listed = (31L..40).map { film(it, "Film $it (2020).mkv") }
        val outcome = settled(held + more + listed, *listed.map { it.filePath }.toTypedArray())
        assertEquals(held.map { it.id }, outcome.waiting)
        assertEquals(more.map { it.id }, outcome.held)
        val few = settled(held + more.take(9) + listed, *listed.map { it.filePath }.toTypedArray())
        assertEquals(more.take(9).map { it.id }, few.removed)
    }

    private companion object {
        const val NOW = 1_000_000L
        const val YEAR = 365 * 24 * 60 * 60_000L
        const val VOL_1 = 24
        const val VOL_2 = 393
    }
}
