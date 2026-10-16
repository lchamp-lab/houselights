package app.houselights.providers.local

import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.shadows.ShadowLog

/** What a scan makes of the names a NAS holds: files holding several episodes, files naming none. */
@RunWith(RobolectricTestRunner::class)
class ScanNamesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private lateinit var shows: SourceEntity
    private val showsNas get() = bench.showsNas

    @Before
    fun open() = runTest {
        bench = ScanBench(temp.newFolder("artwork"), tmdbKey = false)
        shows = bench.folder("Séries", SourceKind.SHOWS)
    }

    @After
    fun close() = bench.close()

    private suspend fun episodes() = bench.dao.filesOf(shows.id).sortedBy { it.episodeNumber }

    @Test
    fun aFileHoldingTwoEpisodesIsOneRowNamedForBoth() = runTest {
        showsNas.put("Severance/Season 1/Severance - S01E01-E02 - Good News About Hell.mkv")
        showsNas.put("Severance/Season 1/Severance - S01E03 - In Perpetuity.mkv")
        bench.scan()

        val rows = episodes()
        assertEquals(listOf(1 to 2, 3 to null), rows.map { it.episodeNumber to it.lastEpisodeNumber })
        val double = LocalMapper(bench.dao, ApplicationProvider.getApplicationContext()).map(rows.first())
        assertEquals("S1 E1-E2", double.episodeLabel)
        assertEquals("1-2", double.episodeNumbers)
        assertEquals("Episodes 1-2", double.title)

        // Watched, it is watched whole: one row, and no other made for its second episode.
        bench.dao.setPlayed(rows.first().id, true, bench.now)
        assertEquals(listOf(true, false), episodes().map { it.played })
    }

    @Test
    fun videosThatNameNoEpisodeAreToldOnceAPass() = runTest {
        showsNas.put("Severance/Season 1/Severance - S01E01.mkv")
        showsNas.put("Severance/Season 1/Severance - Pilot.mkv")
        ShadowLog.clear()
        bench.scan()

        assertEquals(1, episodes().size)
        val told = ShadowLog.getLogsForTag("LibraryScanner").filter { "name no episode" in it.msg }
        assertEquals(1, told.size)
        assertTrue(told.single().msg, "1 of 2 videos" in told.single().msg)
    }
}
