package app.houselights.providers.local

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.SocketTimeoutException
import java.util.Locale

/** A refresh says which language every title is now in, only once it read every source with TMDB. */
@RunWith(RobolectricTestRunner::class)
class ScanLanguageTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench

    @Before
    fun open() = runTest {
        bench = ScanBench(temp.newFolder("artwork"))
        bench.folder("Films")
        bench.tmdb.movie(438631, "Dune", 2021)
        bench.nas.put("Dune (2021).mkv")
    }

    @After
    fun close() = bench.close()

    @Test
    fun aRefreshThatReadEverySourceGivesItsLanguage() = runTest {
        bench.scan()
        assertEquals(emptyList<Locale>(), bench.refreshedIn)
        bench.scan(refreshMetadata = true)
        assertEquals(listOf(bench.scanClient.locale), bench.refreshedIn)
    }

    @Test
    fun aRefreshThatMissedASourceGivesNone() = runTest {
        bench.folder("Séries", SourceKind.SHOWS)
        bench.showsNas.listFailure = SocketTimeoutException("NAS asleep")
        bench.scan(refreshMetadata = true)
        assertEquals(emptyList<Locale>(), bench.refreshedIn)
    }

    @Test
    fun aRefreshWithoutTmdbGivesNone() = runTest {
        bench.withTmdb = false
        bench.scan(refreshMetadata = true)
        assertEquals(emptyList<Locale>(), bench.refreshedIn)
    }
}
