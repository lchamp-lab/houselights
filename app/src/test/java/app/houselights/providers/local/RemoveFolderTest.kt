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

/** Removing a folder removes what was found in it, what was kept of it included, and leaves what another folder still holds. */
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

    @Test
    fun aFolderRemovedTakesItsKeptStatesAlong() = runTest {
        val films = bench.folder("Films")
        bench.nas.put("Dune (2021).mp4")
        bench.nas.put("Heat (1995).mp4")
        bench.scan()
        bench.dao.setFavorite(bench.item(films, "Dune (2021).mp4").id, true)
        bench.nas.remove("Dune (2021).mp4")
        bench.scan()
        assertEquals(1, bench.keptTitles().size)

        bench.scanner.removeSource(films)
        assertEquals(emptyList<String>(), bench.keptTitles())
    }

    /** Dune in each folder, grouped under the first as TMDB would: its main row in [hd], its copy in [uhd]. */
    private suspend fun twoDunes(hd: SourceEntity, uhd: SourceEntity): Pair<ItemEntity, ItemEntity> {
        bench.nas.put(DUNE)
        uhdNas.put(DUNE_UHD)
        bench.scan()
        listOf(bench.item(hd, DUNE), bench.item(uhd, DUNE_UHD)).forEach { bench.dao.update(it.copy(tmdbId = DUNE_TMDB)) }
        bench.dao.groupVersions()
        val main = bench.item(hd, DUNE)
        val copy = bench.item(uhd, DUNE_UHD)
        assertEquals("the first folder's is the main row", main.id, copy.versionOf)
        return main to copy
    }

    /** What the user chose on the film, held by its main row, goes to the copy left, which holds the film now. */
    @Test
    fun aFilmsCopyInAnotherFolderKeepsItsChoicesWhenTheMainRowsFolderGoes() = runTest {
        val hd = bench.folder("Films")
        val uhd = bench.folder("Films 4K", on = uhdNas)
        val (main, copy) = twoDunes(hd, uhd)
        bench.dao.setUserPoster(main.id, "/chosen-poster.jpg")
        bench.dao.setUserBackdrop(main.id, "/chosen-backdrop.jpg")
        bench.dao.setUserLogo(main.id, "/chosen-logo.png")
        bench.dao.setVersionChoice(main.id, copy.id)
        bench.dao.setUserTmdbId(main.id, DUNE_TMDB)
        bench.dao.setAddedAt(copy.id, main.addedAt + ARRIVED_LATER)
        bench.dao.setPlayed(main.id, played = true, now = bench.now)
        bench.dao.setFavorite(main.id, true)

        bench.scanner.removeSource(hd)

        assertNull(bench.dao.item(main.id))
        val left = checkNotNull(bench.dao.item(copy.id))
        assertNull("the copy left is the main row", left.versionOf)
        assertEquals("/chosen-poster.jpg", left.userPosterPath)
        assertEquals("/chosen-backdrop.jpg", left.userBackdropPath)
        assertEquals("/chosen-logo.png", left.userLogoPath)
        assertEquals("the copy chosen is still the one to play", copy.id, left.versionChoice)
        assertEquals("the match by hand", DUNE_TMDB, left.userTmdbId)
        assertEquals("its place in recently added", main.addedAt, left.addedAt)
        assertTrue("watched, as every copy is", left.played)
        assertTrue("favourite, as every copy is", left.favorite)
    }

    /**
     * The copy chosen gone with its folder, the film plays the copy Lire would choose ([VersionRank.played]), not one the
     * copy left chose before it was a copy.
     */
    @Test
    fun aChoiceOfACopyRemovedWithItsFolderBecomesAutomatic() = runTest {
        val hd = bench.folder("Films")
        val uhd = bench.folder("Films 4K", on = uhdNas)
        val (main, copy) = twoDunes(hd, uhd)
        bench.dao.setVersionChoice(copy.id, copy.id)
        bench.dao.setVersionChoice(main.id, main.id)

        bench.scanner.removeSource(hd)

        assertNull(checkNotNull(bench.dao.item(copy.id)).versionChoice)
    }

    /** Removed while a pass reads another folder, a folder leaves nothing kept, not even what the pass kept of it before. */
    @Test
    fun aFolderRemovedDuringAPassLeavesNoStateKept() = runTest {
        val hd = bench.folder("Séries", SourceKind.SHOWS)
        bench.folder("Séries 4K", SourceKind.SHOWS, on = uhdNas)
        val severance = listOf("Severance/Season 1/Severance - S01E01.mkv", "Severance/Season 1/Severance - S01E02.mkv")
        severance.forEach { bench.showsNas.put(it) }
        bench.showsNas.put("Andor/Season 1/Andor - S01E01.mkv")
        uhdNas.put("Slow Horses/Season 1/Slow Horses - S01E01 2160p.mkv")
        bench.scan()
        val show = bench.series("Severance")
        bench.dao.setFavorite(show.id, true)
        bench.dao.setPlayed(bench.item(hd, severance.first()).id, played = true, now = bench.now)

        severance.forEach(bench.showsNas::remove)
        uhdNas.onList = { bench.scanner.removeSource(hd) }
        bench.scan()
        assertNull(bench.dao.item(show.id))
        assertEquals(emptyList<String>(), bench.keptTitles())
    }

    private companion object {
        const val DUNE = "Dune (2021).mkv"
        const val DUNE_UHD = "Dune (2021) 2160p.mkv"
        const val DUNE_TMDB = 438631
        const val ARRIVED_LATER = 60_000L
    }
}
