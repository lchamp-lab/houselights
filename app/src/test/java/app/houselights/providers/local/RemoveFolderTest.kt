package app.houselights.providers.local

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Removing a folder removes what was found in it, and leaves what another folder still holds. */
@RunWith(RobolectricTestRunner::class)
class RemoveFolderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private val uhdNas = FakeRemoteBrowser()

    @Before
    fun open() {
        bench = ScanBench(temp.newFolder("artwork"), tmdbKey = false)
    }

    @After
    fun close() = bench.close()

    private fun cached(path: String?) = File(checkNotNull(path).removePrefix("file://"))

    @Test
    fun aShowTwoFoldersShareMovesToTheOneLeft() = runTest {
        val hd = bench.folder("Séries", SourceKind.SHOWS)
        val uhd = bench.folder("Séries 4K", SourceKind.SHOWS, on = uhdNas)
        bench.showsNas.putImage("Severance/poster.jpg")
        bench.showsNas.put("Severance/Season 1/Severance - S01E01.mkv")
        uhdNas.put("Severance/Season 2/Severance - S02E01 2160p.mkv")
        bench.scan()
        val series = bench.series("Severance")
        assertEquals("first found in the first folder", hd.id, series.sourceId)

        bench.scanner.removeSource(hd)

        val moved = checkNotNull(bench.dao.item(series.id))
        assertEquals(uhd.id, moved.sourceId)
        assertEquals(uhd.id, bench.dao.season(series.id, 2)?.sourceId)
        assertNull(bench.dao.season(series.id, 1))
        assertEquals(1, bench.dao.observeTitleCount(uhd.id).first())
        assertTrue("the poster the show still shows stays", cached(moved.posterPath).isFile)
    }

    @Test
    fun aSeasonTwoFoldersShareMovesToTheOneLeft() = runTest {
        val hd = bench.folder("Séries", SourceKind.SHOWS)
        val uhd = bench.folder("Séries 4K", SourceKind.SHOWS, on = uhdNas)
        bench.showsNas.put("Severance/Season 1/Severance - S01E01.mkv")
        uhdNas.put("Severance/Season 1/Severance - S01E02 2160p.mkv")
        bench.scan()
        val series = bench.series("Severance")
        val season = checkNotNull(bench.dao.season(series.id, 1))
        assertEquals("first found in the first folder", hd.id, season.sourceId)

        bench.scanner.removeSource(hd)

        val moved = checkNotNull(bench.dao.season(series.id, 1))
        assertEquals(season.id, moved.id)
        assertEquals(uhd.id, moved.sourceId)
        assertEquals(listOf(2), bench.dao.children(moved.id).map { it.episodeNumber })
    }

    @Test
    fun aFolderRemovedTakesItsImagesAlong() = runTest {
        val films = bench.folder("Films")
        bench.nas.put("Dune (2021).mp4")
        bench.nas.putImage("Dune (2021)-poster.jpg")
        bench.scan()
        val poster = cached(bench.item(films, "Dune (2021).mp4").posterPath)
        assertTrue(poster.isFile)

        bench.scanner.removeSource(films)
        assertFalse(poster.exists())
    }
}
