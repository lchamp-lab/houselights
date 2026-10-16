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

    private suspend fun series() = bench.dao.filesOf(shows.id).mapNotNull { it.seriesId }.distinct()
        .map { checkNotNull(bench.dao.item(it)) }.sortedBy { it.matchKey }

    @Test
    fun twoShowsOfOneNameAndTwoYearsAreTwoShows() = runTest {
        showsNas.put("Doctor Who (1963)/Season 1/Doctor Who - S01E01 - An Unearthly Child.mkv")
        showsNas.put("Doctor Who (2005)/Season 1/Doctor Who - S01E01 - Rose.mkv")
        bench.scan()

        assertEquals(listOf("doctor who|1963" to 1963, "doctor who|2005" to 2005), series().map { it.matchKey to it.year })
    }

    /** A folder of kinds above the shows: each show is its own, described by its own folder. */
    @Test
    fun showsUnderAFolderOfKindsAreTheirOwn() = runTest {
        showsNas.put("Animation/Arcane/tvshow.nfo", size = ARCANE.size.toLong(), bytes = ARCANE)
        showsNas.put("Animation/Arcane/Saison 1/Arcane.S01E01.MULTi.1080p.WEB.mkv")
        showsNas.put("Animation/Blue Eye Samurai/Saison 1/Blue.Eye.Samurai.S01E01.MULTi.1080p.WEB.mkv")
        bench.scan()

        val found = series()
        assertEquals(listOf("Arcane", "Blue Eye Samurai"), found.map { it.title })
        assertEquals("Animation/Arcane/tvshow.nfo", found.first().nfoPath)
        assertEquals(listOf(1, 1), bench.dao.filesOf(shows.id).map { it.episodeNumber })
    }

    @Test
    fun specialsAreSeasonZero() = runTest {
        showsNas.put("Severance/Season 1/Severance - S01E01.mkv")
        showsNas.put("Severance/Specials/01 - Main Title Sequence.mkv")
        bench.scan()

        assertEquals(listOf(1 to 1, 0 to 1), bench.dao.filesOf(shows.id).sortedBy { it.filePath }.map { it.seasonNumber to it.episodeNumber })
        assertEquals(1, series().size)
    }

    /**
     * A show a library holds from before its key held the year keeps its row, and what the user did
     * with it, as new episodes come; a folder of the show that gives no year joins it.
     */
    @Test
    fun aShowKeyedWithoutItsYearKeepsItsRow() = runTest {
        val old = bench.dao.insert(
            ItemEntity(
                kind = ItemKind.SERIES, sourceId = shows.id, matchKey = "severance", title = "Severance", sortTitle = "severance", addedAt = 0,
            )
        )
        bench.dao.setFavorite(old, true)
        showsNas.put("Severance (2022)/Season 2/Severance - S02E01.mkv")
        bench.scan()
        val uhd = FakeRemoteBrowser().apply { put("Severance/Season 2/Severance - S02E02 2160p.mkv") }
        val second = bench.folder("Séries 4K", SourceKind.SHOWS, on = uhd)
        bench.scan()

        val row = checkNotNull(bench.dao.item(old))
        assertEquals("severance|2022", row.matchKey)
        assertTrue(row.favorite)
        assertEquals(listOf(old, old), (bench.dao.filesOf(shows.id) + bench.dao.filesOf(second.id)).map { it.seriesId })
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

    private companion object {
        val ARCANE = "<tvshow><title>Arcane</title><year>2021</year></tvshow>".toByteArray()
    }
}
