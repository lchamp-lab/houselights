package app.houselights.providers.local

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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

    private companion object {
        const val MOBLAND = 247718
        const val EPISODE = "Mobland/S01/Mobland.S01E01.MULTi.1080p.WEB.H265-GRP.mkv"

        /** Two dates a file was written, as SMB or NFS list them. */
        const val T1 = 1_760_000_000_000L
        const val T2 = T1 + 60_000L
    }
}
