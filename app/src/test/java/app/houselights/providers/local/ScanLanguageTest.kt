package app.houselights.providers.local

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.SocketTimeoutException
import java.util.Locale

/**
 * A new language for the details reads every folder again once and is recorded at once: a folder out of reach is
 * read when back, alone, never the whole library at each launch.
 */
@RunWith(RobolectricTestRunner::class)
class ScanLanguageTest {
    @get:Rule
    val scan = ScanBenchRule()

    private val bench get() = scan.bench
    private lateinit var films: SourceEntity
    private lateinit var shows: SourceEntity

    /** The language the scans' TMDB speaks, wanted after [former]. */
    private val wanted get() = bench.scanClient.locale.toLanguageTag()
    private val former get() = "fr-FR".takeIf { it != wanted } ?: "de-DE"

    /** The language recorded in the settings, and the refreshes the app asked for. */
    private var read: String? = null
    private val refreshes = mutableListOf<Locale>()

    @Before
    fun open() = runTest {
        films = bench.folder("Films")
        shows = bench.folder("Séries", SourceKind.SHOWS)
        bench.tmdb.movie(DUNE, "Dune", 2021)
        bench.tmdb.show(SEVERANCE, "Severance", 2022)
        bench.tmdb.season(SEVERANCE, 1, episodes = 1)
        bench.nas.put("Dune (2021).mkv")
        bench.showsNas.put(PILOT)
        bench.scan()
        read = former
        // TMDB's details in the new language.
        bench.tmdb.editMovie(DUNE) { it.copy(overview = "Arrakis") }
        bench.tmdb.editShow(SEVERANCE) { it.copy(overview = "Lumon") }
    }

    /** A launch: the app follows the language its settings hold, then its passes run. */
    private suspend fun launch() {
        val language = DetailsLanguage(wanted, read, refreshable = true)
        followDetailsLanguage(flowOf(language), {
            refreshes += it
            bench.scanner.refreshAllIn(it)
        }) { read = it }
        bench.awaitScans()
    }

    private suspend fun dune() = bench.item(films, "Dune (2021).mkv")

    private suspend fun due(source: SourceEntity) = bench.dao.source(source.id)?.refreshIn

    @Test
    fun aNewLanguageReadsEveryFolderAgainOnce() = runTest {
        launch()
        assertEquals(listOf(wanted), refreshes.map { it.toLanguageTag() })
        assertEquals(wanted, read)
        assertEquals("Arrakis", dune().overview)
        assertEquals("Lumon", bench.series("Severance").overview)
        assertNull(due(films))
        assertNull(due(shows))

        launch()
        assertEquals("not at the next launch", 1, refreshes.size)
        bench.assertQuiet(auto = true)
    }

    @Test
    fun aFolderOutOfReachLeavesTheLanguageRecordedAndTheNextLaunchAlone() = runTest {
        bench.showsNas.listFailure = SocketTimeoutException("NAS asleep")
        launch()
        assertEquals("recorded with a folder in error", wanted, read)
        assertEquals(ScanErrorKind.UNREACHABLE, bench.error(shows))
        assertEquals("Arrakis", dune().overview)
        assertEquals(wanted, due(shows))

        launch()
        assertEquals("not at the next launch", 1, refreshes.size)
        val pass = bench.pass(auto = true)
        assertEquals("the folder read is not read again", emptyList<String>(), pass.reads)
        assertEquals(emptyList<String>(), pass.requests)
    }

    @Test
    fun aFolderBackIsReadAgainAlone() = runTest {
        bench.showsNas.listFailure = SocketTimeoutException("NAS asleep")
        launch()
        bench.showsNas.listFailure = null

        val back = bench.pass(auto = true)
        assertEquals("Lumon", bench.series("Severance").overview)
        assertNull(due(shows))
        assertTrue(back.requests.toString(), back.requests.none { it.startsWith("movie/") })
        assertTrue(back.reads.toString(), back.reads.none { it.startsWith("Dune") })
        bench.assertQuiet(auto = true)
    }

    @Test
    fun aFolderWaitsForTmdbToBeReadAgain() = runTest {
        bench.withTmdb = false
        bench.scanner.refreshAllIn(Locale.forLanguageTag(wanted))
        bench.awaitScans()
        assertEquals("About Dune", dune().overview)
        assertEquals(wanted, due(films))

        bench.withTmdb = true
        bench.autoScan()
        assertEquals("Arrakis", dune().overview)
        assertNull(due(films))
    }

    /** As when the language changes again while a pass runs: that pass's TMDB speaks the former one. */
    @Test
    fun aFolderDueInAnotherLanguageWaitsForAPassInIt() = runTest {
        bench.dao.refreshAllIn(former)
        val pass = bench.pass(auto = true)
        assertEquals(emptyList<String>(), pass.requests)
        assertEquals("About Dune", dune().overview)
        assertEquals(former, due(films))
    }

    @Test
    fun theFirstLaunchRecordsTheLanguageInUseAndReadsNothingAgain() = runTest {
        read = null
        launch()
        assertEquals(wanted, read)
        assertEquals(emptyList<Locale>(), refreshes)
    }

    private companion object {
        const val DUNE = 438631
        const val SEVERANCE = 95396
        const val PILOT = "Severance/Season 1/Severance - S01E01.mkv"
    }
}
