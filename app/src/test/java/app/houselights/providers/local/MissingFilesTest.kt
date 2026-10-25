package app.houselights.providers.local

import app.houselights.providers.local.MissingFiles.PassTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a listing does to the rows of the files it did not list: out of sight under what it could not read, the later
 * parts of a film in several files, each part's row matched on its own or not; a few gone at once, many held a day.
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
        assertEquals(listOf(arrival.id, heat.id), settle("Dune (2021)/Dune (2021).mkv").removed)
    }

    @Test
    fun aFolderThatCouldNotBeReadKeepsTheRowsUnderItAlone() {
        assertEquals("a folder whose name only starts the same is not under it", listOf(dune.id, heat.id), settle("Private").removed)
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

    /** A film held a day ago by the clock, as many files went with it. */
    private val gone = film(4, "Gone (2001).mkv").copy(missingSince = NOW - MissingFiles.HOLD_MS)

    /** What a pass at [time] decides for [gone], listed with nothing else. */
    private fun missing(time: PassTime) =
        MissingFiles.settle(SourceKind.MOVIES, listOf(gone), Stacks.of(SourceKind.MOVIES, emptyList()), time)

    @Test
    fun aFileHeldADayGoesOnceThisProcessSawItMissingLongEnough() {
        assertEquals(listOf(gone.id), missing(PassTime(NOW) { _, _ -> true }).removed)
        val unseen = missing(PassTime(NOW) { _, _ -> false })
        assertEquals(emptyList<Long>(), unseen.removed)
        assertEquals(listOf(gone.id), unseen.waiting)
    }

    /** The clock set back since the last pass: the day is over by the clock, yet nothing goes in that pass. */
    @Test
    fun aPassEarlierThanTheLastDeletesNothing() {
        val outcome = missing(PassTime(NOW, lastScanAt = NOW + 1))
        assertEquals(emptyList<Long>(), outcome.removed)
        assertEquals(listOf(gone.id), outcome.waiting)
    }

    /** Held at a time the clock, set back since, has not reached: held again from now rather than until then. */
    @Test
    fun aFileMissingSinceATimeToComeWaitsFromNow() {
        val outcome = missing(PassTime(NOW - MissingFiles.HOLD_MS - 1))
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
        elapsed = MissingFiles.SEEN_AGAIN_MS - 1
        sightings.note(listOf(gone), held.copy(held = emptyList(), waiting = listOf(gone.id)))
        assertFalse(sightings.seenLong(gone.id, MissingFiles.SEEN_AGAIN_MS))
        elapsed += 1
        assertTrue(sightings.seenLong(gone.id, MissingFiles.SEEN_AGAIN_MS))

        sightings.note(listOf(gone), held.copy(held = emptyList(), back = listOf(gone.id)))
        assertFalse("a new absence starts over", sightings.seenLong(gone.id, MissingFiles.SEEN_AGAIN_MS))
    }

    /**
     * A file this process held waits its whole hold on the clock no setting moves, its date being of the same clock;
     * one held before, maybe by a clock since set right, waits an hour, so a restart does not hold it ever again.
     */
    @Test
    fun aFileHeldByThisProcessWaitsItsWholeHoldOnTheClockNoSettingMoves() {
        var elapsed = 0L
        val sightings = MissingSightings { elapsed }
        val before = film(5, "Before (1999).mkv").copy(missingSince = NOW - MissingFiles.HOLD_MS)
        val pass = MissingFiles.Outcome(relinked = emptyList(), back = emptyList(), held = listOf(gone.id), removed = emptyList())
        sightings.note(listOf(gone, before), pass.copy(waiting = listOf(before.id)))
        elapsed = MissingFiles.SEEN_AGAIN_MS
        assertTrue("held before this process", sightings.seenLong(before.id, MissingFiles.HOLD_MS))
        assertFalse("held by this process", sightings.seenLong(gone.id, MissingFiles.HOLD_MS))
        elapsed = MissingFiles.HOLD_MS
        assertTrue(sightings.seenLong(gone.id, MissingFiles.HOLD_MS))
    }

    private fun films(count: Int) = (1L..count).map { film(it, "Film $it (2020).mkv") }

    private fun settled(rows: List<KnownFile>, time: PassTime = PassTime(NOW), vararg paths: String) =
        MissingFiles.settle(SourceKind.MOVIES, rows, Stacks.of(SourceKind.MOVIES, paths.map { RemoteFile(it, 1_000) }), time)

    /** A few gone go on the pass that misses them, whatever the clock; more, as when a disk of the pool is offline, wait a day. */
    @Test
    fun manyFilesGoneAtOnceAreHeld() {
        val few = films(20)
        assertEquals(few.map { it.id }, settled(few, PassTime(NOW) { _, _ -> false }).removed)
        val many = films(21)
        val outcome = settled(many)
        assertEquals(many.map { it.id }, outcome.held)
        assertEquals(emptyList<Long>(), outcome.removed)
    }

    /** Held as many went, then missing alone: it waits its day all the same, a hold being decided when first missed. */
    @Test
    fun aFileHeldBecauseManyWentWaitsItsDayAlone() {
        val held = gone.copy(missingSince = NOW - HOUR)
        val outcome = settled(listOf(held))
        assertEquals(emptyList<Long>(), outcome.removed + outcome.held)
        assertEquals(listOf(held.id), outcome.waiting)
    }

    /** Its first part missing, a later part listed: the film waits a day, not counted among many, then goes. */
    @Test
    fun aFilmWhoseLaterPartIsListedWaitsADayForItsFirst() {
        val dir = "Heat (1995)"
        val heat = film(100, "$dir/Heat (1995) CD1.avi").copy(parts = listOf("$dir/Heat (1995) CD2.avi"))
        val others = films(20)
        val first = settled(others + heat, PassTime(NOW), "$dir/Heat (1995) CD2.avi")
        assertEquals(listOf(heat.id), first.held)
        assertEquals("a few besides", others.map { it.id }, first.removed)

        val waiting = settled(listOf(heat.copy(missingSince = NOW - MissingFiles.HOLD_MS + 1)), PassTime(NOW), "$dir/Heat (1995) CD2.avi")
        assertEquals(emptyList<Long>(), waiting.removed)
        assertEquals(listOf(heat.id), waiting.waiting)
        val dayLater = settled(listOf(heat.copy(missingSince = NOW - MissingFiles.HOLD_MS)), PassTime(NOW), "$dir/Heat (1995) CD2.avi")
        assertEquals(listOf(heat.id), dayLater.removed)
    }

    private companion object {
        const val HOUR = 60 * 60_000L
        const val NOW = 1_000_000L
        const val VOL_1 = 24
        const val VOL_2 = 393
    }
}
