package app.houselights.providers.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.ConnectException

/** A device out of space stops the scan at once and says so; nearly out of space, images are not copied. */
@RunWith(RobolectricTestRunner::class)
class ScanStorageTest {
    @get:Rule
    val scan = ScanBenchRule(tmdbKey = false)

    private val bench get() = scan.bench
    private val nas get() = bench.nas

    /** Prevents the database from growing by another page. */
    private fun fillTheDatabase(full: Boolean) {
        val db = bench.db.openHelper.writableDatabase
        val pages = db.query("PRAGMA page_count").use {
            it.moveToFirst()
            it.getLong(0)
        }
        db.query("PRAGMA max_page_count = ${if (full) pages else Int.MAX_VALUE}").use { it.moveToFirst() }
        // The free space the device reports then.
        bench.freeSpace = if (full) 1L shl 20 else Long.MAX_VALUE
    }

    @Test
    fun aFullDatabaseStopsThePassAtTheFirstFile() = runTest {
        val films = bench.folder("Films")
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        repeat(200) { nas.put("Film $it (2020).mkv") }
        bench.showsNas.put("Severance/Season 1/Severance - S01E01.mkv")
        var showsListed = 0
        bench.showsNas.onList = { showsListed++ }
        fillTheDatabase(full = true)
        bench.scan()

        assertEquals(ScanErrorKind.STORAGE_FULL, bench.error(films))
        val read = nas.reads.size
        assertTrue("$read files read", read < 200)
        assertEquals("the next folder would fail the same way", 0, showsListed)
        assertNull(bench.error(shows))

        fillTheDatabase(full = false)
        bench.scan()
        assertEquals(null, bench.error(films))
        assertEquals(200, bench.dao.filesOf(films.id).size)
    }

    @Test
    fun anImageThatFillsTheDeviceStopsThePass() = runTest {
        val films = bench.folder("Films")
        repeat(3) {
            nas.put("Film $it (2020).mp4")
            nas.putImage("Film $it (2020)-poster.jpg")
        }
        nas.onRead = { _, _, _ -> throw IOException("write failed: ENOSPC (No space left on device)") }
        bench.scan()

        assertEquals(ScanErrorKind.STORAGE_FULL, bench.error(films))
        assertEquals("one image tried", 1, nas.reads.size)
    }

    /** A pass the full device stops after some titles tells screens of them at its end, as a whole pass would. */
    @Test
    fun aPassStoppedAfterItsFirstTitlesTellsTheirChange() = runTest {
        val films = bench.folder("Films")
        repeat(6) { nas.put("Film $it (2020).mp4") }
        nas.put("Zodiac (2007).mp4")
        nas.putImage("Zodiac (2007)-poster.jpg")
        nas.onRead = { _, _, _ -> throw IOException("write failed: ENOSPC (No space left on device)") }
        val told = bench.toldDuring { bench.scan() }

        assertEquals(ScanErrorKind.STORAGE_FULL, bench.error(films))
        assertTrue("the titles before it written", bench.paths(films).size >= 6)
        assertEquals("the change told once, at the pass's end", 1, told.changes)
    }

    @Test
    fun theFoldersAPassStopsBeforeKeepTheirLastError() = runTest {
        val films = bench.folder("Films")
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        bench.showsNas.listFailure = ConnectException("Connection refused")
        bench.scan()
        assertEquals(ScanErrorKind.UNREACHABLE, bench.error(shows))

        nas.put("Dune (2021).mp4")
        nas.putImage("Dune (2021)-poster.jpg")
        nas.onRead = { _, _, _ -> throw IOException("write failed: ENOSPC (No space left on device)") }
        bench.scan()

        assertEquals(ScanErrorKind.STORAGE_FULL, bench.error(films))
        assertEquals("not read again: as far as the app knows, still off", ScanErrorKind.UNREACHABLE, bench.error(shows))
    }

    @Test
    fun aDeviceNearlyFullLeavesImagesToTmdb() = runTest {
        val films = bench.folder("Films")
        nas.put("Dune (2021).mp4")
        nas.putImage("Dune (2021)-poster.jpg")
        bench.freeSpace = ArtworkCache.MIN_FREE_BYTES
        bench.scan()

        assertEquals(emptyList<String>(), nas.reads)
        assertFalse(bench.item(films, "Dune (2021).mp4").posterPath.isCachedArtwork())
        assertNull(bench.error(films))

        assertEquals("tried again, to no avail: screens left as they are", 0, bench.changesDuring { bench.scan() })

        bench.freeSpace = Long.MAX_VALUE
        assertTrue("the poster copied at last", bench.changesDuring { bench.scan() } > 0)
        assertTrue(bench.item(films, "Dune (2021).mp4").posterPath.isCachedArtwork())
    }
}
