package app.houselights.providers.local

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Files renamed, moved, upgraded or replaced on the NAS: their rows keep what the user did; a file changed is read again. */
@RunWith(RobolectricTestRunner::class)
class ScanRenamesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val log = StatementLog()
    private lateinit var bench: ScanBench
    private lateinit var films: SourceEntity
    private val nas get() = bench.nas
    private val dao get() = bench.dao

    @Before
    fun open() = runTest {
        bench = ScanBench(temp.newFolder("artwork"), tmdbKey = false, onQuery = log)
        films = bench.folder("Films")
    }

    @After
    fun close() = bench.close()

    private suspend fun filePaths() = bench.paths(films)

    private fun film(n: Int) = "Film $n (2020).mkv"

    @Test
    fun aFilmImportedInTwoStepsKeepsWhatTheUserDid() = runTest {
        nas.put("Dune (2021) Bluray-1080p.mkv")
        nas.put("Heat (1995).mkv")
        bench.scan()
        val dune = dao.filesOf(films.id).single { it.title == "Dune" }
        dao.savePlayback(dune.id, positionMs = 600_000, durationMs = 9_000_000, played = true, lastPlayedAt = bench.now)
        dao.setFavorite(dune.id, true)

        // Radarr sends the old file to its recycle bin, then copies the new one from another volume.
        nas.remove("Dune (2021) Bluray-1080p.mkv")
        bench.scan()
        assertNull(dao.item(dune.id))
        nas.put("Dune (2021) Remux-2160p.mkv")
        bench.scan()

        val upgraded = dao.filesOf(films.id).single { it.title == "Dune" }
        assertEquals("Dune (2021) Remux-2160p.mkv", upgraded.filePath)
        assertTrue(upgraded.played)
        assertEquals(600_000L to bench.now, upgraded.positionMs to upgraded.lastPlayedAt)
        assertTrue(upgraded.favorite)
        assertEquals(dune.addedAt, upgraded.addedAt)
        assertEquals("given back once", emptyList<String>(), bench.keptTitles())
        assertEquals("nothing sent to Trakt", emptyList<TraktPendingEntity>(), bench.db.trakt().pending())
    }

    /** Sorted into a folder per letter, the whole library at once: written in a few transactions. */
    @Test
    fun filmsSortedIntoFoldersKeepTheirRows() = runTest {
        repeat(1_200) { nas.put(film(it)) }
        bench.scan()
        val before = dao.filesOf(films.id).associate { it.filePath to it.id }
        // Every film leaves the path and the folder it had.
        val moved = (0 until 1_200).associate { film(it) to "${film(it).first()}/${film(it)}" }
        moved.forEach { (from, to) -> nas.rename(from, to) }
        log.reset()
        bench.scan()

        assertEquals(moved.values.sorted(), filePaths())
        assertEquals(before.mapKeys { (path, _) -> moved[path] }, dao.filesOf(films.id).associate { it.filePath to it.id })
        log.assertNoStatementTooWideForAndroid9()
        assertTrue("${log.transactions.get()} transactions", log.transactions.get() <= A_FEW_TRANSACTIONS)
    }

    @Test
    fun aFilmFolderRenamedKeepsItsRow() = runTest {
        nas.put("Dune 2021/Dune.mkv")
        nas.put("Heat (1995)/Heat (1995).mkv")
        bench.scan()
        val dune = dao.filesOf(films.id).single { it.title == "Dune" }
        dao.setFavorite(dune.id, true)

        // Radarr's "Rename Movie Folder", and its files.
        nas.rename("Dune 2021/Dune.mkv", "Dune (2021)/Dune (2021) Bluray-1080p.mkv")
        bench.scan()

        val renamed = dao.filesOf(films.id).single { it.title == "Dune" }
        assertEquals(dune.id, renamed.id)
        assertEquals("Dune (2021)/Dune (2021) Bluray-1080p.mkv", renamed.filePath)
        assertTrue(renamed.favorite)
    }

    @Test
    fun twoCopiesOfAFilmMovedTogetherCannotBeToldApart() = runTest {
        nas.put("Dune (2021) Bluray-1080p.mkv")
        nas.put("Dune (2021) Remux-2160p.mkv")
        bench.scan()
        val before = dao.filesOf(films.id).map { it.id }.toSet()

        nas.rename("Dune (2021) Bluray-1080p.mkv", "Dune/Dune (2021) Bluray-1080p.mkv")
        nas.rename("Dune (2021) Remux-2160p.mkv", "Dune/Dune (2021) Remux-2160p.mkv")
        bench.scan()

        val moved = dao.filesOf(films.id).filter { it.missingSince == null }
        assertEquals(2, moved.size)
        assertTrue("which is which cannot be told", moved.none { it.id in before })
    }

    @Test
    fun aFilmUpgradedByRadarrKeepsWhatTheUserDid() = runTest {
        nas.put("Dune (2021)/Dune (2021) Bluray-1080p.mkv", size = 30_000_000_000)
        nas.put("Arrival (2016)/Arrival (2016) Bluray-1080p.mkv")
        bench.scan()
        val dune = dao.filesOf(films.id).single { it.title == "Dune" }
        dao.savePlayback(dune.id, positionMs = 600_000, durationMs = 9_000_000, played = false, lastPlayedAt = bench.now)
        dao.setFavorite(dune.id, true)
        dao.setUserTmdbId(dune.id, 438631)
        dao.setUserPoster(dune.id, "/chosen.jpg")

        nas.remove("Dune (2021)/Dune (2021) Bluray-1080p.mkv")
        nas.put("Dune (2021)/Dune (2021) Remux-2160p.mkv", size = 80_000_000_000)
        val reads = bench.pass().reads

        val upgraded = dao.filesOf(films.id).single { it.title == "Dune" }
        assertEquals(dune.id, upgraded.id)
        assertEquals("Dune (2021)/Dune (2021) Remux-2160p.mkv", upgraded.filePath)
        assertEquals(80_000_000_000L, upgraded.fileSize)
        assertEquals(dune.addedAt, upgraded.addedAt)
        assertEquals(600_000L, upgraded.positionMs)
        assertEquals(bench.now, upgraded.lastPlayedAt)
        assertTrue(upgraded.favorite)
        assertEquals(438631, upgraded.userTmdbId)
        assertEquals("/chosen.jpg", upgraded.userPosterPath)
        assertEquals("the new file is read", listOf(upgraded.filePath), reads)
        assertEquals(80_000_000_000L, MediaProbe.decode(upgraded.mediaInfo)?.sizeBytes)
    }

    @Test
    fun filmsUpgradedSideBySideKeepTheirRows() = runTest {
        nas.put("Dune (2021) Bluray-1080p.mkv")
        nas.put("Arrival (2016) WEBDL-1080p.mkv")
        nas.put("Heat (1995).mkv")
        bench.scan()
        val before = dao.filesOf(films.id).associate { it.title to it.id }

        nas.rename("Dune (2021) Bluray-1080p.mkv", "Dune (2021) Remux-2160p.mkv")
        nas.rename("Arrival (2016) WEBDL-1080p.mkv", "Arrival (2016) Bluray-2160p.mkv")
        bench.scan()

        val after = dao.filesOf(films.id)
        assertEquals(before, after.associate { it.title to it.id })
        assertEquals(listOf("Arrival (2016) Bluray-2160p.mkv", "Dune (2021) Remux-2160p.mkv", "Heat (1995).mkv"), filePaths())
    }

    @Test
    fun anotherFilmBesideIsNotARename() = runTest {
        nas.put("Dune (2021).mkv")
        nas.put("Heat (1995).mkv")
        bench.scan()
        val dune = dao.filesOf(films.id).single { it.title == "Dune" }
        dao.setFavorite(dune.id, true)

        nas.remove("Dune (2021).mkv")
        nas.put("Arrival (2016).mkv")
        bench.scan()

        val arrival = dao.filesOf(films.id).single { it.title == "Arrival" }
        assertTrue(arrival.id != dune.id)
        assertEquals(false, arrival.favorite)
        assertNull("Dune is gone", dao.item(dune.id))
        assertTrue("its state kept", dao.keptNamed(ItemKind.MOVIE, "dune|2021").single().favorite)
    }

    @Test
    fun anotherFilmAloneInAFolderNotNamedLikeAFilmIsNotARename() = runTest {
        nas.put("À voir/Dune (2021).mkv")
        nas.put("Heat (1995).mkv")
        bench.scan()
        val dune = dao.filesOf(films.id).single { it.title == "Dune" }
        dao.setFavorite(dune.id, true)
        dao.setPlayed(dune.id, played = true, now = bench.now)

        nas.remove("À voir/Dune (2021).mkv")
        nas.put("À voir/Arrival (2016).mkv")
        bench.scan()

        val arrival = dao.filesOf(films.id).single { it.filePath == "À voir/Arrival (2016).mkv" }
        assertTrue(arrival.id != dune.id)
        assertEquals("Arrival", arrival.title)
        assertEquals(false, arrival.favorite)
        assertEquals(false, arrival.played)
    }

    @Test
    fun anEpisodeUpgradedBySonarrStaysWatched() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        bench.showsNas.put("Severance/Season 1/Severance - S01E01 - Good News About Hell WEBDL-1080p.mkv")
        bench.showsNas.put("Severance/Season 1/Severance - S01E02 - Half Loop WEBDL-1080p.mkv")
        bench.scan()
        val pilot = dao.filesOf(shows.id).single { it.episodeNumber == 1 }
        dao.setPlayed(pilot.id, played = true, now = bench.now)

        bench.showsNas.rename(
            "Severance/Season 1/Severance - S01E01 - Good News About Hell WEBDL-1080p.mkv",
            "Severance/Season 1/Severance - S01E01 - Good News About Hell Bluray-2160p.mkv",
        )
        bench.scan()

        val upgraded = dao.filesOf(shows.id).single { it.episodeNumber == 1 }
        assertEquals(pilot.id, upgraded.id)
        assertTrue(upgraded.played)
        assertEquals(pilot.addedAt, upgraded.addedAt)
        assertEquals(2, dao.episodesOfSeries(checkNotNull(pilot.seriesId)).size)
    }

    @Test
    fun aFileRenamedAloneIsNotReadAgain() = runTest {
        nas.put("Dune (2021) Remux-2160p.mkv", size = 80_000_000_000, bytes = Matroska.sample())
        bench.scan()
        val dune = dao.filesOf(films.id).single()

        nas.rename("Dune (2021) Remux-2160p.mkv", "Dune (2021) {imdb-tt1160419} Remux-2160p.mkv")
        val pass = bench.pass()

        val renamed = dao.filesOf(films.id).single()
        assertEquals(dune.id, renamed.id)
        assertEquals("Dune (2021) {imdb-tt1160419} Remux-2160p.mkv", renamed.filePath)
        assertEquals("the same size, the same file", emptyList<String>(), pass.reads)
        assertEquals(dune.mediaInfo, renamed.mediaInfo)
        assertEquals(Matroska.SAMPLE_BADGES, renamed.badges)
        assertTrue("screens learn the new name", pass.changes > 0)
    }

    @Test
    fun aFileReplacedUnderTheSameNameIsReadAgain() = runTest {
        nas.put("Dune (2021).mkv", size = 30_000_000_000)
        bench.scan()
        assertEquals(emptyList<String>(), dao.filesOf(films.id).single().badges)

        // A remux in its place, under the same name; or the rest of a file read while it was copied.
        nas.put("Dune (2021).mkv", size = 80_000_000_000, bytes = Matroska.sample())
        val reads = bench.pass().reads

        val dune = dao.filesOf(films.id).single()
        assertEquals(listOf("Dune (2021).mkv"), reads)
        assertEquals(80_000_000_000L, dune.fileSize)
        assertEquals(80_000_000_000L, MediaProbe.decode(dune.mediaInfo)?.sizeBytes)
        assertEquals(Matroska.SAMPLE_BADGES, dune.badges)
        assertEquals("read once", emptyList<String>(), bench.pass().reads)
    }

    @Test
    fun anEpisodeReplacedUnderTheSameNameIsReadAgain() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        val pilot = "Severance/Season 1/Severance - S01E01.mkv"
        bench.showsNas.put(pilot, size = 2_000_000_000)
        bench.scan()

        bench.showsNas.put(pilot, size = 9_000_000_000, bytes = Matroska.sample())
        val reads = bench.pass().reads

        val episode = dao.filesOf(shows.id).single()
        assertEquals(listOf(pilot), reads)
        assertEquals(9_000_000_000L, episode.fileSize)
        assertEquals(Matroska.SAMPLE_BADGES, episode.badges)
        assertEquals("read once", emptyList<String>(), bench.pass().reads)
    }

    private companion object {
        const val A_FEW_TRANSACTIONS = 20
    }
}
