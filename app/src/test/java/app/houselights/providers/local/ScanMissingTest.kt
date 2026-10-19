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
 * Rows of files a pass did not list: kept whole for a NAS off, an empty listing or a folder out of sight; held an
 * hour when a few files are gone, a day when many are, as when a disk of the NAS's pool is offline.
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
        bench.now += MissingFiles.HOLD_MS
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

        bench.now += 23 * HOUR
        bench.scan()
        assertEquals("held for a day", 40, filePaths().size)

        bench.now += 2 * HOUR
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

        bench.now += 2 * HOUR
        bench.scan()
        assertEquals("held past the hour", 1_000, filePaths().size)
    }

    @Test
    fun aFileBackIsNoLongerHeld() = runTest {
        filmFolder(40)
        (0 until 21).map(::film).forEach(nas::remove)
        bench.scan()

        nas.put(film(0))
        bench.now += 25 * HOUR
        bench.scan()

        val back = dao.filesOf(films.id).single { it.filePath == film(0) }
        assertEquals(null, back.missingSince)
        assertEquals(20, filePaths().size)
    }

    @Test
    fun fewFilesGoneGoAnHourLater() = runTest {
        filmFolder(40)
        (0 until 20).map(::film).forEach(nas::remove)
        bench.scan()
        assertEquals("held until a later pass", 40, filePaths().size)

        bench.now += HOUR - 1
        bench.scan()
        assertEquals("held for an hour", 40, filePaths().size)

        bench.now += 1
        bench.scan()
        assertEquals((20 until 40).map(::film).sorted(), filePaths())
    }

    @Test
    fun moreFilesGoneThanAStatementTakes() = runTest {
        filmFolder(1_201)
        (0 until 1_200).map(::film).forEach(nas::remove)
        log.reset()
        bench.scan()
        assertEquals(null, bench.error(films))
        assertEquals("held", 1_201, filePaths().size)

        bench.now += 25 * HOUR
        bench.scan()
        assertEquals(listOf(film(1_200)), filePaths())
        log.assertNoStatementTooWideForAndroid9()
    }

    private companion object {
        const val START = ScanBench.START
        const val HOUR = 60 * 60_000L
    }
}
