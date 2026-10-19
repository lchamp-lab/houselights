package app.houselights.providers.local

import app.houselights.providers.local.FakeRemoteBrowser.Companion.T1
import app.houselights.providers.local.FakeRemoteBrowser.Companion.T2
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A show's sidecars ([SidecarKey.ofShow]) are read again when added or rewritten, and otherwise never again. */
@RunWith(RobolectricTestRunner::class)
class ScanShowSidecarsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private lateinit var shows: SourceEntity
    private val nas get() = bench.showsNas
    private val tmdb get() = bench.tmdb

    @Before
    fun open() = runTest {
        bench = ScanBench(temp.newFolder("artwork"))
        shows = bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(MOBLAND, "MobLand", 2025)
        tmdb.season(MOBLAND, 1, episodes = 10)
        tmdb.season(MOBLAND, 2, episodes = 10)
        nas.put(EPISODE)
    }

    @After
    fun close() = bench.close()

    private fun nfo(title: String) = "<tvshow><title>$title</title><uniqueid type=\"tmdb\">$MOBLAND</uniqueid></tvshow>"

    private suspend fun show(): ItemEntity = bench.showOf(shows, EPISODE)

    /** Sonarr or tinyMediaManager rewrites tvshow.nfo in place, often at the same size: its date shows it. */
    @Test
    fun aShowNfoRewrittenInPlaceIsReadAgainWithoutTmdb() = runTest {
        nas.putText("Mobland/tvshow.nfo", nfo("MobLand"), lastModified = T1)
        bench.scan()
        assertEquals("MobLand", show().title)

        nas.putText("Mobland/tvshow.nfo", nfo("MOBLAND"), lastModified = T2)
        val pass = bench.pass()
        assertEquals(listOf("Mobland/tvshow.nfo"), pass.reads)
        assertEquals(emptyList<String>(), pass.requests)
        assertTrue("screens told", pass.changes > 0)
        assertEquals("MOBLAND", show().title)
        bench.assertQuiet()
    }

    @Test
    fun aPosterPlacedInAShowFolderIsCopied() = runTest {
        bench.scan()
        assertFalse(show().posterPath.isCachedArtwork())

        nas.putImage("Mobland/poster.jpg", lastModified = T1)
        val pass = bench.pass()
        assertEquals(listOf("Mobland/poster.jpg"), pass.reads)
        assertEquals(emptyList<String>(), pass.requests)
        assertTrue(show().posterPath.isCachedArtwork())
        bench.assertQuiet()
    }

    /** A show folder's NFO, poster, empty fanart and an unused landscape image are each read once. */
    @Test
    fun showSidecarsReadOnceAreNotReadAgain() = runTest {
        nas.putText("Mobland/tvshow.nfo", nfo("MobLand"), lastModified = T1)
        nas.putImage("Mobland/poster.jpg", lastModified = T1)
        nas.putImage("Mobland/fanart.jpg", size = 0, lastModified = T1)
        nas.putImage("Mobland/landscape.jpg", lastModified = T1)
        bench.scan()
        assertTrue(show().posterPath.isCachedArtwork())
        bench.assertQuiet()
    }

    /** Shows from before their folder's reads were recorded: each is read once, without TMDB, then no more. */
    @Test
    fun aShowReadBeforeItsKeyIsReadOnceMore() = runTest {
        nas.putText("Mobland/tvshow.nfo", nfo("MobLand"), lastModified = T1)
        nas.putImage("Mobland/poster.jpg", lastModified = T1)
        bench.scan()
        bench.dao.saveScanned(show().copy(sidecarKey = null))

        val pass = bench.pass()
        assertEquals("the NFO; the poster's copy is there", listOf("Mobland/tvshow.nfo"), pass.reads)
        assertEquals(emptyList<String>(), pass.requests)
        assertEquals(0, pass.changes)
        bench.assertQuiet()
    }

    /** A show in two folders, one giving its year, each with its NFO: they do not alternate at every pass. */
    @Test
    fun aShowInTwoFoldersIsNotReadAgainAtEveryPass() = runTest {
        val second = "MobLand (2025)/S02/MobLand.S02E01.MULTi.1080p.WEB.H265-GRP.mkv"
        nas.put(second)
        nas.putText("Mobland/tvshow.nfo", nfo("MobLand"), lastModified = T1)
        nas.putText("MobLand (2025)/tvshow.nfo", nfo("MobLand (2025)"), lastModified = T1)
        bench.scan()
        assertEquals("one show", bench.item(shows, EPISODE).seriesId, bench.item(shows, second).seriesId)
        bench.assertQuiet()
        bench.assertQuiet()
    }

    /** Sonarr renames a show's folder: its episodes follow, and its sidecars are read there once. */
    @Test
    fun aShowFolderRenamedHasItsSidecarsReadThereOnce() = runTest {
        nas.putText("Mobland/tvshow.nfo", nfo("MobLand"), lastModified = T1)
        bench.scan()
        val id = show().id
        nas.rename(EPISODE, EPISODE.replace("Mobland/", "MobLand (2025)/"))
        nas.rename("Mobland/tvshow.nfo", "MobLand (2025)/tvshow.nfo")

        val pass = bench.pass()
        assertEquals(listOf("MobLand (2025)/tvshow.nfo"), pass.reads)
        assertEquals(emptyList<String>(), pass.requests)
        bench.assertQuiet()

        nas.putText("MobLand (2025)/tvshow.nfo", nfo("MOBLAND"), lastModified = T2)
        assertEquals(listOf("MobLand (2025)/tvshow.nfo"), bench.pass().reads)
        assertEquals("MOBLAND", bench.dao.item(id)?.title)
    }

    /** Reading them through one episode would move the show to the one its path names, the rest one per pass. */
    @Test
    fun aShowKeyedByAnEarlierRuleIsNotReadForItsSidecars() = runTest {
        val second = EPISODE.replace("S01E01", "S01E02")
        nas.put(second)
        nas.putText("Mobland/tvshow.nfo", nfo("MobLand"), lastModified = T1)
        bench.scan()
        val old = show().copy(matchKey = "mobland s01", sidecarKey = null)
        bench.dao.saveScanned(old)

        bench.assertQuiet()
        assertEquals(listOf(old.id, old.id), listOf(EPISODE, second).map { bench.item(shows, it).seriesId })
    }

    /** Without its tvshow.nfo, TMDB's details replace the NFO's; the user's match and artwork stay. */
    @Test
    fun aShowNfoRemovedLeavesItsDetailsToTmdb() = runTest {
        nas.putText("Mobland/tvshow.nfo", "<tvshow><title>MobLand (VF)</title><plot>Londres.</plot></tvshow>", lastModified = T1)
        bench.scan()
        val described = show()
        assertEquals("Londres.", described.overview)
        bench.dao.setUserTmdbId(described.id, MOBLAND)
        bench.dao.setUserPoster(described.id, POSTER)

        nas.remove("Mobland/tvshow.nfo")
        bench.scan()
        val read = show()
        assertEquals("MobLand" to "About MobLand", read.title to read.overview)
        assertEquals(null, read.nfoPath)
        assertEquals(MOBLAND to POSTER, read.userTmdbId to read.userPosterPath)
        bench.assertQuiet()
    }

    /** Known only by the NFO's TMDB id: once the NFO is removed, that id is asked for, not the folder's name. */
    @Test
    fun aShowNfoRemovedKeepsTheShowItsIdNamed() = runTest {
        tmdb.show(LEGENDES, "Le Bureau des Légendes", 2015)
        tmdb.show(HOMONYM, "Le Bureau", 2006)
        tmdb.season(LEGENDES, 1, episodes = 10)
        tmdb.season(HOMONYM, 1, episodes = 6)
        nas.put(BUREAU)
        nas.putText("Le Bureau/tvshow.nfo", "<tvshow><uniqueid type=\"tmdb\">$LEGENDES</uniqueid></tvshow>", lastModified = T1)
        bench.scan()
        suspend fun bureau() = bench.showOf(shows, BUREAU)
        assertEquals(LEGENDES to "Le Bureau des Légendes", bureau().let { it.tmdbId to it.title })

        nas.remove("Le Bureau/tvshow.nfo")
        bench.scan()
        val read = bureau()
        assertEquals(LEGENDES to "Le Bureau des Légendes", read.tmdbId to read.title)
        assertNull(read.nfoPath)
        assertNotNull(read.matchedAt)

        // Likewise at a full refresh, as a film keeps its match: not its folder's namesake.
        bench.scan(refreshMetadata = true)
        assertEquals(LEGENDES to "Le Bureau des Légendes", bureau().let { it.tmdbId to it.title })
    }

    /** Rewritten, a season poster is copied again by the next episode read; unchanged, it is not read again. */
    @Test
    fun aSeasonPosterRewrittenIsCopiedAgain() = runTest {
        nas.putImage("Mobland/season01-poster.jpg", lastModified = T1)
        bench.scan()
        suspend fun poster() = checkNotNull(bench.dao.season(show().id, 1)).posterPath
        val first = poster()
        assertTrue(first.isCachedArtwork())
        assertFalse("Mobland/season01-poster.jpg" in bench.pass(refresh = true).reads)
        assertEquals(first, poster())

        nas.putImage("Mobland/season01-poster.jpg", size = 120, lastModified = T2)
        nas.put(EPISODE.replace("S01E01", "S01E02"))
        bench.scan()
        val second = poster()
        assertTrue(second.isCachedArtwork())
        assertNotEquals(first, second)
    }

    @Test
    fun aShowPosterRemovedIsNoLongerShown() = runTest {
        nas.putImage("Mobland/poster.jpg", lastModified = T1)
        nas.putImage("Mobland/fanart.jpg", lastModified = T1)
        bench.scan()
        assertTrue(show().posterPath.isCachedArtwork())

        nas.remove("Mobland/poster.jpg")
        bench.scan()
        val read = show()
        assertEquals("/show-$MOBLAND.jpg", read.posterPath)
        assertTrue("its fanart is still there", read.backdropPath.isCachedArtwork())
        bench.assertQuiet()
    }

    @Test
    fun showSidecarsRemovedWhileTmdbIsOutOfReachAreFilledInOnceItAnswers() = runTest {
        nas.putText("Mobland/tvshow.nfo", "<tvshow><title>MobLand (VF)</title><plot>Londres.</plot></tvshow>", lastModified = T1)
        nas.putImage("Mobland/poster.jpg", lastModified = T1)
        bench.scan()

        bench.tmdbOutOfReach()
        nas.remove("Mobland/tvshow.nfo")
        nas.remove("Mobland/poster.jpg")
        bench.scan()
        val waiting = show()
        assertEquals(null, waiting.overview)
        assertFalse(waiting.posterPath.isCachedArtwork())

        bench.tmdbBack()
        bench.scan()
        val read = show()
        assertEquals("MobLand" to "About MobLand", read.title to read.overview)
        assertEquals("/show-$MOBLAND.jpg", read.posterPath)
    }

    /** Read through its other folder at a refresh TMDB does not answer, a show keeps the poster from the first. */
    @Test
    fun aShowsPosterInOneOfItsFoldersStaysWhenTheOtherIsRead() = runTest {
        val second = "MobLand (2025)/S02/MobLand.S02E01.MULTi.1080p.WEB.H265-GRP.mkv"
        nas.clear()
        nas.put(second)
        nas.put(EPISODE)
        nas.putImage("Mobland/poster.jpg", lastModified = T1)
        bench.scan()
        // The show was read through the first folder met: the poster's folder is read at the next pass.
        bench.scan()
        assertTrue(show().posterPath.isCachedArtwork())

        bench.tmdbOutOfReach()
        bench.scan(refreshMetadata = true)
        assertTrue(show().posterPath.isCachedArtwork())
    }

    private companion object {
        const val MOBLAND = 247718
        const val LEGENDES = 62215
        const val HOMONYM = 3480
        const val POSTER = "https://image.tmdb.org/t/p/original/chosen.jpg"
        const val EPISODE = "Mobland/S01/Mobland.S01E01.MULTi.1080p.WEB.H265-GRP.mkv"
        const val BUREAU = "Le Bureau/Saison 1/Le.Bureau.S01E01.FRENCH.1080p.mkv"
    }
}
