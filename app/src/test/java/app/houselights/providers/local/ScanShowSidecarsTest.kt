package app.houselights.providers.local

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
import org.robolectric.shadows.ShadowSystemClock
import java.io.IOException
import java.time.Duration

/**
 * A show's own sidecars (tvshow.nfo, its poster, fanart and logo), as a scan sees them from one pass to
 * the next ([SidecarKey.ofShow]): read again when placed or rewritten in place, though none of its
 * episodes changed; not read again otherwise, nor TMDB asked.
 */
@RunWith(RobolectricTestRunner::class)
class ScanShowSidecarsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private val nas get() = bench.showsNas
    private val tmdb get() = bench.tmdb

    @Before
    fun open() = runTest {
        bench = ScanBench(temp.newFolder("artwork"))
        bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(MOBLAND, "MobLand", 2025)
        tmdb.season(MOBLAND, 1, episodes = 10)
        tmdb.season(MOBLAND, 2, episodes = 10)
        nas.put(EPISODE)
    }

    @After
    fun close() = bench.close()

    private fun FakeRemoteBrowser.text(path: String, text: String, lastModified: Long) =
        text.toByteArray().let { put(path, size = it.size.toLong(), bytes = it, lastModified = lastModified) }

    private fun FakeRemoteBrowser.image(path: String, size: Int = 100) =
        put(path, size = size.toLong(), bytes = ByteArray(size), lastModified = T1)

    private fun nfo(title: String) = "<tvshow><title>$title</title><uniqueid type=\"tmdb\">$MOBLAND</uniqueid></tvshow>"

    private suspend fun show(): ItemEntity = checkNotNull(bench.dao.item(checkNotNull(bench.item(sourceOf(), EPISODE).seriesId)))

    private suspend fun sourceOf() = bench.dao.sources().single()

    /** What the NAS and TMDB are asked, and screens told, during a pass. */
    private suspend fun pass(): Triple<List<String>, List<String>, Int> {
        nas.reads.clear()
        tmdb.requests.clear()
        val changes = bench.changesDuring { bench.scan() }
        return Triple(nas.reads.toList(), tmdb.requests.toList(), changes)
    }

    private suspend fun assertQuiet() {
        val (reads, requests, changes) = pass()
        assertEquals("nothing read on the NAS", emptyList<String>(), reads)
        assertEquals("nothing asked of TMDB", emptyList<String>(), requests)
        assertEquals("screens left as they are", 0, changes)
    }

    /** Sonarr or tinyMediaManager rewrites tvshow.nfo under its name, often to the same size: its date tells. */
    @Test
    fun aShowNfoRewrittenInPlaceIsReadAgainWithoutTmdb() = runTest {
        nas.text("Mobland/tvshow.nfo", nfo("MobLand"), lastModified = T1)
        bench.scan()
        assertEquals("MobLand", show().title)

        nas.text("Mobland/tvshow.nfo", nfo("MOBLAND"), lastModified = T2)
        val (reads, requests, changes) = pass()
        assertEquals(listOf("Mobland/tvshow.nfo"), reads)
        assertEquals(emptyList<String>(), requests)
        assertTrue("screens told", changes > 0)
        assertEquals("MOBLAND", show().title)
        assertQuiet()
    }

    @Test
    fun aPosterPlacedInAShowFolderIsCopied() = runTest {
        bench.scan()
        assertFalse(show().posterPath.isCachedArtwork())

        nas.image("Mobland/poster.jpg")
        val (reads, requests, _) = pass()
        assertEquals(listOf("Mobland/poster.jpg"), reads)
        assertEquals(emptyList<String>(), requests)
        assertTrue(show().posterPath.isCachedArtwork())
        assertQuiet()
    }

    /**
     * What a show's folder holds, read once: its NFO, a poster, an empty fanart the cache does not hold,
     * and a landscape image no show reads.
     */
    @Test
    fun showSidecarsReadOnceAreNotReadAgain() = runTest {
        nas.text("Mobland/tvshow.nfo", nfo("MobLand"), lastModified = T1)
        nas.image("Mobland/poster.jpg")
        nas.image("Mobland/fanart.jpg", size = 0)
        nas.image("Mobland/landscape.jpg")
        bench.scan()
        assertTrue(show().posterPath.isCachedArtwork())
        assertQuiet()
    }

    /** A library from before shows kept what was read of their folder: each is read once, without TMDB, then no more. */
    @Test
    fun aShowReadBeforeItsKeyIsReadOnceMore() = runTest {
        nas.text("Mobland/tvshow.nfo", nfo("MobLand"), lastModified = T1)
        nas.image("Mobland/poster.jpg")
        bench.scan()
        bench.dao.saveScanned(show().copy(sidecarKey = null))

        val (reads, requests, changes) = pass()
        assertEquals("the NFO; the poster's copy is there", listOf("Mobland/tvshow.nfo"), reads)
        assertEquals(emptyList<String>(), requests)
        assertEquals(0, changes)
        assertQuiet()
    }

    /** A show in two folders, one giving its year, each with its NFO: they do not take turns at every pass. */
    @Test
    fun aShowInTwoFoldersIsNotReadAgainAtEveryPass() = runTest {
        val second = "MobLand (2025)/S02/MobLand.S02E01.MULTi.1080p.WEB.H265-GRP.mkv"
        nas.put(second)
        nas.text("Mobland/tvshow.nfo", nfo("MobLand"), lastModified = T1)
        nas.text("MobLand (2025)/tvshow.nfo", nfo("MobLand (2025)"), lastModified = T1)
        bench.scan()
        assertEquals("one show", bench.item(sourceOf(), EPISODE).seriesId, bench.item(sourceOf(), second).seriesId)
        assertQuiet()
        assertQuiet()
    }

    /** Sonarr renames a show's folder: its episodes follow, and its sidecars are read there once. */
    @Test
    fun aShowFolderRenamedHasItsSidecarsReadThereOnce() = runTest {
        nas.text("Mobland/tvshow.nfo", nfo("MobLand"), lastModified = T1)
        bench.scan()
        val id = show().id
        nas.rename(EPISODE, EPISODE.replace("Mobland/", "MobLand (2025)/"))
        nas.rename("Mobland/tvshow.nfo", "MobLand (2025)/tvshow.nfo")

        val (reads, requests, _) = pass()
        assertEquals(listOf("MobLand (2025)/tvshow.nfo"), reads)
        assertEquals(emptyList<String>(), requests)
        assertQuiet()

        nas.text("MobLand (2025)/tvshow.nfo", nfo("MOBLAND"), lastModified = T2)
        assertEquals(listOf("MobLand (2025)/tvshow.nfo"), pass().first)
        assertEquals("MOBLAND", bench.dao.item(id)?.title)
    }

    /**
     * Episodes of a show keyed by an earlier rule than their path's ("mobland s01"): its sidecars are not
     * read through one of them, which would move it to the show its path names, and the rest one a pass.
     */
    @Test
    fun aShowKeyedByAnEarlierRuleIsNotReadForItsSidecars() = runTest {
        val second = EPISODE.replace("S01E01", "S01E02")
        nas.put(second)
        nas.text("Mobland/tvshow.nfo", nfo("MobLand"), lastModified = T1)
        bench.scan()
        val old = show().copy(matchKey = "mobland s01", sidecarKey = null)
        bench.dao.saveScanned(old)

        assertQuiet()
        assertEquals(listOf(old.id, old.id), listOf(EPISODE, second).map { bench.item(sourceOf(), it).seriesId })
    }

    /**
     * A tvshow.nfo removed from a show's folder: what it said goes, TMDB's details take its place. The user's
     * match and artwork stay.
     */
    @Test
    fun aShowNfoRemovedLeavesItsDetailsToTmdb() = runTest {
        nas.text("Mobland/tvshow.nfo", "<tvshow><title>MobLand (VF)</title><plot>Londres.</plot></tvshow>", lastModified = T1)
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
        assertQuiet()
    }

    /**
     * A show TMDB knew through its tvshow.nfo's id alone, its folder naming it otherwise: the NFO removed, TMDB is
     * asked for the show it was, rather than searched by the folder's name, which finds another of that name.
     */
    @Test
    fun aShowNfoRemovedKeepsTheShowItsIdNamed() = runTest {
        tmdb.show(LEGENDES, "Le Bureau des Légendes", 2015)
        tmdb.show(HOMONYM, "Le Bureau", 2006)
        tmdb.season(LEGENDES, 1, episodes = 10)
        tmdb.season(HOMONYM, 1, episodes = 6)
        nas.put(BUREAU)
        nas.text("Le Bureau/tvshow.nfo", "<tvshow><uniqueid type=\"tmdb\">$LEGENDES</uniqueid></tvshow>", lastModified = T1)
        bench.scan()
        suspend fun bureau() = checkNotNull(bench.dao.item(checkNotNull(bench.item(sourceOf(), BUREAU).seriesId)))
        assertEquals(LEGENDES to "Le Bureau des Légendes", bureau().let { it.tmdbId to it.title })

        nas.remove("Le Bureau/tvshow.nfo")
        bench.scan()
        val read = bureau()
        assertEquals(LEGENDES to "Le Bureau des Légendes", read.tmdbId to read.title)
        assertNull(read.nfoPath)
        assertNotNull(read.matchedAt)

        // And at a full refresh, as a film keeps the match it has: not its folder's homonym.
        bench.scan(refreshMetadata = true)
        assertEquals(LEGENDES to "Le Bureau des Légendes", bureau().let { it.tmdbId to it.title })
    }

    /**
     * A season's poster in its show's folder, rewritten: the next episode read copies it again, and its season
     * shows the new copy. Unchanged, it is not read again.
     */
    @Test
    fun aSeasonPosterRewrittenIsCopiedAgain() = runTest {
        nas.image("Mobland/season01-poster.jpg")
        bench.scan()
        suspend fun poster() = checkNotNull(bench.dao.season(show().id, 1)).posterPath
        val first = poster()
        assertTrue(first.isCachedArtwork())
        nas.reads.clear()
        bench.scan(refreshMetadata = true)
        assertFalse(nas.reads.contains("Mobland/season01-poster.jpg"))
        assertEquals(first, poster())

        nas.put("Mobland/season01-poster.jpg", size = 120, bytes = ByteArray(120), lastModified = T2)
        nas.put(EPISODE.replace("S01E01", "S01E02"))
        bench.scan()
        val second = poster()
        assertTrue(second.isCachedArtwork())
        assertNotEquals(first, second)
    }

    /** A poster removed from a show's folder: its copy is no longer shown, TMDB's poster takes its place. */
    @Test
    fun aShowPosterRemovedIsNoLongerShown() = runTest {
        nas.image("Mobland/poster.jpg")
        nas.image("Mobland/fanart.jpg")
        bench.scan()
        assertTrue(show().posterPath.isCachedArtwork())

        nas.remove("Mobland/poster.jpg")
        bench.scan()
        val read = show()
        assertEquals("/show-$MOBLAND.jpg", read.posterPath)
        assertTrue("its fanart is still there", read.backdropPath.isCachedArtwork())
        assertQuiet()
    }

    /** Removed while TMDB is out of reach: the show keeps nothing of them, and TMDB fills it in once it answers. */
    @Test
    fun showSidecarsRemovedWhileTmdbIsOutOfReachAreFilledInOnceItAnswers() = runTest {
        nas.text("Mobland/tvshow.nfo", "<tvshow><title>MobLand (VF)</title><plot>Londres.</plot></tvshow>", lastModified = T1)
        nas.image("Mobland/poster.jpg")
        bench.scan()

        tmdb.failure = IOException("Network is unreachable")
        nas.remove("Mobland/tvshow.nfo")
        nas.remove("Mobland/poster.jpg")
        bench.scan()
        val waiting = show()
        assertEquals(null, waiting.overview)
        assertFalse(waiting.posterPath.isCachedArtwork())

        tmdb.failure = null
        ShadowSystemClock.advanceBy(Duration.ofHours(1))
        bench.scan()
        val read = show()
        assertEquals("MobLand" to "About MobLand", read.title to read.overview)
        assertEquals("/show-$MOBLAND.jpg", read.posterPath)
    }

    /**
     * A show in two folders, its poster in one: read through the other one, at a refresh TMDB does not answer, it
     * keeps that poster. Only what was read in a folder is missed there.
     */
    @Test
    fun aShowsPosterInOneOfItsFoldersStaysWhenTheOtherIsRead() = runTest {
        val second = "MobLand (2025)/S02/MobLand.S02E01.MULTi.1080p.WEB.H265-GRP.mkv"
        nas.clear()
        nas.put(second)
        nas.put(EPISODE)
        nas.image("Mobland/poster.jpg")
        bench.scan()
        // The show was read through the first folder met: its poster's is read at the next pass.
        bench.scan()
        assertTrue(show().posterPath.isCachedArtwork())

        tmdb.failure = IOException("Network is unreachable")
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

        /** Two dates a file was written, as SMB or NFS list them. */
        const val T1 = 1_760_000_000_000L
        const val T2 = T1 + 60_000L
    }
}
