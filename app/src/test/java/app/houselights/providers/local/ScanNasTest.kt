package app.houselights.providers.local

import app.houselights.core.SecretUnreadException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ConnectException

/**
 * A pass closes its NAS connections when it ends or waits, reports a fully refused folder as refused, and a password not
 * read for now as such.
 */
@RunWith(RobolectricTestRunner::class)
class ScanNasTest {
    @get:Rule
    val scan = ScanBenchRule(tmdbKey = false)

    private val bench get() = scan.bench
    private lateinit var films: SourceEntity
    private val nas get() = bench.nas

    @Before
    fun open() = runTest {
        films = bench.folder("Films")
    }

    private suspend fun filePaths() = bench.paths(films)

    @Test
    fun eachPassClosesWhatItKeptOnTheNasOnce() = runTest {
        nas.put("Dune (2021).mkv")
        bench.scan()
        assertEquals(1, bench.closed.get())

        // One folder of the pass fails.
        bench.folder("Films 4K", on = FakeRemoteBrowser().apply { listFailure = ConnectException("Connection refused") })
        bench.scan()
        assertEquals(2, bench.closed.get())
    }

    @Test
    fun aScanWaitingForTheFilmHoldsNoSessionOnTheNas() = runTest {
        nas.put("Dune (2021).mkv")
        // The film starts while the folder is listed: the listing stops, to begin anew once the film ends.
        var listed = 0
        nas.onList = { if (++listed == 1) bench.playing.value = true }
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
        assertEquals(listOf("Dune (2021).mkv"), filePaths())
        assertEquals(2, bench.closed.get())
    }

    /** The permissions of every subfolder changed on the NAS: reported as such, never as a folder to remove. */
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

    /**
     * The folder's password kept, which the Keystore did not open for now, stops its listing before the NAS is asked (as
     * the browsers do, [Secret.forSignIn]): said as such, never as a folder that could not be read, its titles kept; the
     * next scan reads it again.
     */
    @Test
    fun aFolderWhosePasswordIsNotReadForNowSaysSoAndKeepsItsTitles() = runTest {
        nas.put("Dune (2021).mkv")
        bench.scan()

        nas.listFailure = SecretUnreadException()
        bench.scan()
        assertEquals(ScanErrorKind.PASSWORD_UNREAD, bench.error(films))
        assertEquals(listOf("Dune (2021).mkv"), filePaths())

        nas.listFailure = null
        bench.scan()
        assertNull(bench.error(films))
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
