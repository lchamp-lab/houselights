package app.houselights.providers.local

import app.houselights.providers.local.MissingFiles.PassTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a listing does to the rows of the files it did not list: out of sight under what it could not read, or the
 * later parts of a film in several files, each part's row matched on its own or not.
 */
class MissingFilesTest {
    private fun film(id: Long, path: String) = KnownFile(id = id, filePath = path)

    private val dune = film(1, "Dune (2021)/Dune (2021).mkv")
    private val arrival = film(2, "Private/Arrival (2016)/Arrival (2016).mkv")
    private val heat = film(3, "Private films/Heat (1995).mkv")

    private fun settle(vararg unreadable: String) = MissingFiles.settle(
        SourceKind.MOVIES, listOf(dune, arrival, heat), Listing(emptyList(), emptyList(), unreadable.toList()), PassTime(NOW),
    )

    /** A file the NAS could not be asked about (gone between listing and query) is out of sight, not missing. */
    @Test
    fun aFileThatCouldNotBeAskedAboutKeepsItsRow() {
        assertEquals(listOf(arrival.id, heat.id), settle("Dune (2021)/Dune (2021).mkv").held)
    }

    @Test
    fun aFolderThatCouldNotBeReadKeepsTheRowsUnderItAlone() {
        assertEquals("a folder whose name only starts the same is not under it", listOf(dune.id, heat.id), settle("Private").held)
    }

    private fun stacked(rows: List<KnownFile>, vararg paths: String) =
        MissingFiles.settle(SourceKind.MOVIES, rows, Stacks.of(SourceKind.MOVIES, paths.map { RemoteFile(it, 1_000) }), PassTime(NOW))

    /** "Kill Bill CD1" and "CD2" read as one film, but each matched its own: two films, each keeping its row. */
    @Test
    fun filesMatchedWithTwoFilmsAreNotOne() {
        val files = arrayOf("Kill Bill CD1.avi", "Kill Bill CD2.avi")
        val first = film(1, "Kill Bill CD1.avi").copy(tmdbId = VOL_1)
        val second = film(2, "Kill Bill CD2.avi").copy(tmdbId = VOL_2)
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

    /** First part out of sight (a pool disk offline), second alone in the folder: not a rename; the film waits. */
    @Test
    fun aFilmWhoseFirstPartIsGoneWaitsForIt() {
        val dir = "Heat (1995)"
        val heat = film(1, "$dir/Heat (1995) CD1.avi").copy(parts = listOf("$dir/Heat (1995) CD2.avi"))
        val outcome = stacked(listOf(heat), "$dir/Heat (1995) CD2.avi")
        assertEquals(emptyList<Pair<Long, RemoteFile>>(), outcome.relinked)
        assertEquals(listOf(1L), outcome.held)
        assertEquals(emptyList<Pair<Long, List<String>>>(), outcome.parts)

        // Its first part renamed is still the film.
        val renamed = stacked(listOf(heat), "$dir/Heat (1995).avi")
        assertEquals(listOf(1L to RemoteFile("$dir/Heat (1995).avi", 1_000)), renamed.relinked)
    }

    /** A film missing for an hour by the clock. */
    private val gone = film(4, "Gone (2001).mkv").copy(missingSince = NOW - MissingFiles.BRIEF_HOLD_MS)

    /** What a pass at [time] decides for [gone], listed with nothing else. */
    private fun missing(time: PassTime) =
        MissingFiles.settle(SourceKind.MOVIES, listOf(gone), Stacks.of(SourceKind.MOVIES, emptyList()), time)

    @Test
    fun aFileMissingAnHourGoesOnceThisProcessSawItMissingLongEnough() {
        assertEquals(listOf(gone.id), missing(PassTime(NOW) { true }).removed)
        val unseen = missing(PassTime(NOW) { false })
        assertEquals(emptyList<Long>(), unseen.removed)
        assertEquals(listOf(gone.id), unseen.waiting)
    }

    /** The clock set back since the last pass: the hour is over by the clock, yet nothing goes in that pass. */
    @Test
    fun aPassEarlierThanTheLastDeletesNothing() {
        val outcome = missing(PassTime(NOW, lastScanAt = NOW + 1))
        assertEquals(emptyList<Long>(), outcome.removed)
        assertEquals(listOf(gone.id), outcome.waiting)
    }

    /** Held at a time the clock, set back since, has not reached: held again from now rather than until then. */
    @Test
    fun aFileMissingSinceATimeToComeWaitsFromNow() {
        val outcome = missing(PassTime(NOW - MissingFiles.BRIEF_HOLD_MS - 1))
        assertEquals(listOf(gone.id), outcome.held)
        assertEquals(emptyList<Long>(), outcome.removed + outcome.waiting)
    }

    /** Timed on the clock no setting moves, from the first pass that saw the file missing; forgotten once it is back. */
    @Test
    fun aFileIsSeenMissingLongEnoughAnHourAfterTheFirstPassThatSawIt() {
        var elapsed = 0L
        val sightings = MissingSightings { elapsed }
        val held = MissingFiles.Outcome(relinked = emptyList(), back = emptyList(), held = listOf(gone.id), removed = emptyList())
        sightings.note(listOf(gone), held)
        elapsed = MissingFiles.BRIEF_HOLD_MS - 1
        sightings.note(listOf(gone), held.copy(held = emptyList(), waiting = listOf(gone.id)))
        assertFalse(sightings.seenLong(gone.id))
        elapsed += 1
        assertTrue(sightings.seenLong(gone.id))

        sightings.note(listOf(gone), held.copy(held = emptyList(), back = listOf(gone.id)))
        assertFalse("a new absence starts over", sightings.seenLong(gone.id))
    }

    private companion object {
        const val NOW = 1_000_000L
        const val VOL_1 = 24
        const val VOL_2 = 393
    }
}
