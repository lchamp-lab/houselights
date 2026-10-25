package app.houselights.providers.local

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ConnectException

/**
 * Rows of files a pass did not list: kept whole for a NAS off, an empty listing or a folder out of sight; removed on
 * the pass that misses them when a few files are gone, what the user did kept by title; held a day when many are, as
 * when a disk of the NAS's pool is offline.
 */
@RunWith(RobolectricTestRunner::class)
class ScanMissingTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val log = StatementLog()
    private lateinit var bench: ScanBench
    private lateinit var films: SourceEntity
    private val nas get() = bench.nas
    private val dao get() = bench.dao

    @Before
    fun open() = runTest {
        bench = ScanBench(temp.newFolder("artwork"), tmdbKey = false, onQuery = log)
        films = bench.folder("Films")
    }

    @After
    fun close() = bench.close()

    private suspend fun filePaths() = bench.paths(films)

    private fun film(n: Int) = "Film $n (2020).mkv"

    private suspend fun filmFolder(count: Int) {
        repeat(count) { nas.put(film(it)) }
        bench.scan()
    }

    @Test
    fun anUnreachableNasRemovesNothing() = runTest {
        nas.put("Dune (2021).mkv")
        bench.scan()
        dao.setFavorite(dao.filesOf(films.id).single().id, true)

        nas.listFailure = ConnectException("Connection refused")
        bench.scan()

        assertEquals(ScanErrorKind.UNREACHABLE, bench.error(films))
        assertTrue(dao.filesOf(films.id).single().favorite)
    }

    @Test
    fun anEmptyListingRemovesNothing() = runTest {
        nas.put("Dune (2021).mkv")
        nas.put("Arrival (2016).mkv")
        bench.scan()
        val dune = dao.filesOf(films.id).first { it.title == "Dune" }
        dao.savePlayback(dune.id, positionMs = 600_000, durationMs = 9_000_000, played = false, lastPlayedAt = bench.now)
        dao.setFavorite(dune.id, true)

        // The share answers, but its disk is not mounted.
        nas.clear()
        bench.scan()

        assertEquals(ScanErrorKind.EMPTY, bench.error(films))
        assertEquals(listOf("Arrival (2016).mkv", "Dune (2021).mkv"), filePaths())
        val kept = checkNotNull(dao.item(dune.id))
        assertEquals(600_000L, kept.positionMs)
        assertTrue(kept.favorite)
        assertEquals(null, kept.missingSince)
    }

    /** lost+found, another user's folder: what the library holds under it is out of sight, not gone. */
    @Test
    fun aFolderThatCannotBeReadRemovesNothingUnderIt() = runTest {
        nas.put("Dune (2021)/Dune (2021).mkv")
        nas.put("Private/Arrival (2016)/Arrival (2016).mkv")
        bench.scan()

        nas.unreadable = listOf("Private")
        nas.put("Blade Runner (1982)/Blade Runner (1982).mkv")
        bench.scan()
        bench.later(MissingFiles.HOLD_MS)
        bench.scan()

        assertEquals(null, bench.error(films))
        assertEquals(
            listOf(
                "Blade Runner (1982)/Blade Runner (1982).mkv",
                "Dune (2021)/Dune (2021).mkv",
                "Private/Arrival (2016)/Arrival (2016).mkv",
            ),
            filePaths(),
        )
        assertEquals("not even held", null, dao.filesOf(films.id).single { it.title == "Arrival" }.missingSince)
    }

    @Test
    fun aFolderNeverFilledMayListEmpty() = runTest {
        bench.scan()
        assertEquals(null, bench.error(films))
    }

    @Test
    fun manyFilesGoneAtOnceStayADay() = runTest {
        filmFolder(40)
        // More than 20: a disk of the pool is offline.
        val offline = (0 until 21).map(::film)
        offline.forEach(nas::remove)
        bench.scan()
        assertEquals(40, filePaths().size)
        assertEquals(setOf(START), dao.filesOf(films.id).filter { it.filePath in offline }.map { it.missingSince }.toSet())
        assertEquals(null, bench.error(films))

        bench.later(23 * HOUR)
        bench.scan()
        assertEquals("held for a day", 40, filePaths().size)

        bench.later(2 * HOUR)
        bench.scan()
        assertEquals("still missing a day later", (21 until 40).map(::film).sorted(), filePaths())
    }

    @Test
    fun aDiskOfALargePoolOfflineIsHeldToo() = runTest {
        filmFolder(1_000)
        // One disk of seven: far less than a quarter of the folder.
        val offline = (0 until 150).map(::film)
        offline.forEach(nas::remove)
        bench.scan()
        assertEquals(1_000, filePaths().size)
        assertEquals(setOf(START), dao.filesOf(films.id).filter { it.filePath in offline }.map { it.missingSince }.toSet())

        bench.later(2 * HOUR)
        bench.scan()
        assertEquals("held past the hour", 1_000, filePaths().size)
    }

    @Test
    fun aFileBackIsNoLongerHeld() = runTest {
        filmFolder(40)
        (0 until 21).map(::film).forEach(nas::remove)
        bench.scan()

        nas.put(film(0))
        bench.later(25 * HOUR)
        bench.scan()

        val back = dao.filesOf(films.id).single { it.filePath == film(0) }
        assertEquals(null, back.missingSince)
        assertEquals(20, filePaths().size)
    }

    @Test
    fun fewFilesGoneGoOnTheNextPass() = runTest {
        filmFolder(40)
        (0 until 20).map(::film).forEach(nas::remove)
        bench.scan()
        assertEquals((20 until 40).map(::film).sorted(), filePaths())
    }

    /** A film deleted goes on the pass that misses it, the app's own after a restart included; what the user did is kept. */
    @Test
    fun aFileDeletedIsRemovedOnTheNextPass() = runTest {
        nas.put("Dune (2021).mkv")
        nas.put("Heat (1995).mkv")
        bench.scan()
        dao.setFavorite(dao.filesOf(films.id).single { it.title == "Dune" }.id, true)

        nas.remove("Dune (2021).mkv")
        bench.autoScan()
        assertEquals(listOf("Heat (1995).mkv"), filePaths())
        assertEquals(listOf("film:name:${films.id}::dune|2021"), bench.keptTitles())
    }

    /** Held while a disk was offline, a file still missing once it is back waits the day it was held for. */
    @Test
    fun aFileHeldWithManyWaitsItsDayOnceTheOthersAreBack() = runTest {
        filmFolder(40)
        val offline = (0 until 21).map(::film)
        offline.forEach(nas::remove)
        bench.scan()

        // The disk back, but for one file, deleted meanwhile.
        offline.drop(1).forEach { nas.put(it) }
        bench.later(HOUR)
        bench.scan()
        assertEquals("held from when many went", 40, filePaths().size)

        bench.later(DAY - HOUR)
        bench.scan()
        assertEquals((1 until 40).map(::film).sorted(), filePaths())
    }

    @Test
    fun moreFilesGoneThanAStatementTakes() = runTest {
        filmFolder(1_201)
        (0 until 1_200).map(::film).forEach(nas::remove)
        log.reset()
        bench.scan()
        assertEquals(null, bench.error(films))
        assertEquals("held", 1_201, filePaths().size)

        bench.later(25 * HOUR)
        bench.scan()
        assertEquals(listOf(film(1_200)), filePaths())
        log.assertNoStatementTooWideForAndroid9()
    }

    private suspend fun missingSince(paths: List<String>) =
        dao.filesOf(films.id).filter { it.filePath in paths }.map { it.missingSince }.toSet()

    /** The clock set back a day (by hand, or a device without a battery started offline): files held wait from its time. */
    @Test
    fun aClockSetBackHoldsFilesFromItsTime() = runTest {
        filmFolder(40)
        val gone = (0 until 21).map(::film)
        gone.forEach(nas::remove)
        bench.scan()

        bench.later(10 * MINUTE)
        bench.now -= DAY
        bench.scan()
        assertEquals(40, filePaths().size)
        assertEquals(setOf(bench.now), missingSince(gone))

        bench.later(DAY)
        bench.scan()
        assertEquals("a day later by its time", (21 until 40).map(::film).sorted(), filePaths())
    }

    /** Files missing a day by the clock, which is then set back behind the folder's last pass: none goes in that pass. */
    @Test
    fun aPassEarlierThanTheLastDeletesNothing() = runTest {
        filmFolder(40)
        val gone = (0 until 20).map(::film)
        gone.forEach(nas::remove)
        // Held a day ago, by a process since stopped.
        val ids = dao.filesOf(films.id).filter { it.filePath in gone }.map { it.id }
        dao.settleMissing(emptyList(), emptyList(), held = ids, removed = emptyList(), parts = emptyList(), now = START - DAY)
        bench.later(2 * HOUR)
        bench.scan()
        assertEquals("not yet seen missing an hour apart by this process", 40, filePaths().size)

        bench.later(2 * HOUR)
        bench.now -= 3 * HOUR
        bench.scan()
        assertEquals("the clock behind the last pass", 40, filePaths().size)

        bench.later(HOUR)
        bench.scan()
        assertEquals((20 until 40).map(::film).sorted(), filePaths())
    }

    /**
     * A device without a battery starts on its build date, offline, before a disk of the NAS's pool is up; the network
     * then sets its clock years ahead: the disk still has an hour to come back, not none.
     */
    @Test
    fun aClockJumpingForwardLeavesTheFilesTimeToComeBack() = runTest {
        filmFolder(40)
        val offline = (0 until 21).map(::film)
        val watched = dao.filesOf(films.id).single { it.filePath == film(0) }.id
        dao.setFavorite(watched, true)
        bench.now = START - 2 * YEAR
        offline.forEach(nas::remove)
        bench.scan()
        assertEquals(40, filePaths().size)

        bench.later(10 * MINUTE)
        bench.now = START + DAY
        bench.scan()
        assertEquals("years by the clock, minutes in fact", 40, filePaths().size)

        offline.forEach { nas.put(it) }
        bench.later(30 * MINUTE)
        bench.scan()
        assertEquals(setOf(null), missingSince(offline))
        assertTrue(checkNotNull(dao.item(watched)).favorite)
    }

    /** The same jump with the disk offline for hours: its files wait the day they would wait with a clock always right. */
    @Test
    fun aClockJumpingForwardStillHoldsManyFilesADay() = runTest {
        filmFolder(40)
        val offline = (0 until 21).map(::film)
        bench.now = START - 2 * YEAR
        offline.forEach(nas::remove)
        bench.scan()

        bench.later(10 * MINUTE)
        bench.now = START + DAY
        bench.scan()
        bench.later(3 * HOUR)
        bench.scan()
        assertEquals("hours after the jump", 40, filePaths().size)

        bench.later(DAY)
        bench.scan()
        assertEquals("a day after they went", (21 until 40).map(::film).sorted(), filePaths())
    }

    private companion object {
        const val START = ScanBench.START
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val YEAR = 365 * DAY
    }
}
