package app.houselights.providers.local

import kotlinx.coroutines.CompletableDeferred
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
 * The NAS as a scan meets it: what a pass keeps open there is closed when it ends and before it
 * waits, and a folder whose subfolders all refuse the device is said refused, never empty.
 */
@RunWith(RobolectricTestRunner::class)
class ScanNasTest {
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

    private suspend fun filePaths() = bench.dao.filesOf(films.id).mapNotNull { it.filePath }.sorted()

    @Test
    fun eachPassClosesWhatItKeptOnTheNasOnce() = runTest {
        nas.put("Dune (2021).mkv")
        bench.scan()
        assertEquals(1, bench.closed.get())

        // A folder of the pass fails.
        bench.folder("Films 4K", on = FakeRemoteBrowser().apply { listFailure = ConnectException("Connection refused") })
        bench.scan()
        assertEquals(2, bench.closed.get())
    }

    @Test
    fun aScanWaitingForTheFilmHoldsNoSessionOnTheNas() = runTest {
        nas.put("Dune (2021).mkv")
        // The film starts while the folder is listed: the pass waits before its first file.
        nas.onList = { bench.playing.value = true }
        val closing = CompletableDeferred<Unit>().also { bench.onClose = { it.complete(Unit) } }
        bench.scanner.scanAll()
        closing.await()
        assertEquals("closed before reading a file", emptyList<String>(), nas.reads)

        bench.playing.value = false
        bench.awaitScans()
        assertEquals(listOf("Dune (2021).mkv"), filePaths())
        assertEquals("and once the pass is over", 2, bench.closed.get())
    }

    @Test
    fun aScanWaitingForTheAppHoldsNoSessionOnTheNas() = runTest {
        nas.put("Dune (2021).mkv")
        var listed = 0
        nas.onList = { listed++ }
        bench.started.value = false
        val closing = CompletableDeferred<Unit>().also { bench.onClose = { it.complete(Unit) } }
        bench.scanner.scanAll()
        closing.await()
        assertEquals("closed before listing", 0, listed)

        bench.started.value = true
        bench.awaitScans()
        assertEquals(2, bench.closed.get())
    }

    /** The rights of every subfolder changed on the NAS: said as such, never as a folder to remove. */
    @Test
    fun aFolderWhoseSubfoldersAllRefuseTheDeviceIsAccessDenied() = runTest {
        nas.put("Dune (2021)/Dune (2021).mkv")
        nas.put("Arrival (2016)/Arrival (2016).mkv")
        bench.scan()
        bench.dao.setFavorite(bench.item(films, "Dune (2021)/Dune (2021).mkv").id, true)

        nas.unreadable = listOf("Dune (2021)", "Arrival (2016)")
        bench.scan()

        assertEquals(ScanErrorKind.ACCESS_DENIED, bench.error(films))
        assertEquals(listOf("Arrival (2016)/Arrival (2016).mkv", "Dune (2021)/Dune (2021).mkv"), filePaths())
        assertTrue(bench.item(films, "Dune (2021)/Dune (2021).mkv").favorite)
    }

    @Test
    fun aFirstScanOfAFolderWhoseSubfoldersAllRefuseTheDeviceSaysSo() = runTest {
        nas.put("Private/Dune (2021)/Dune (2021).mkv")
        nas.unreadable = listOf("Private")
        bench.scan()

        assertEquals("not a folder left at 0 titles, unexplained", ScanErrorKind.ACCESS_DENIED, bench.error(films))
        assertEquals(emptyList<String>(), filePaths())
    }
}
