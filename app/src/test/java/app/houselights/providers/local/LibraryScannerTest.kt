package app.houselights.providers.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.Secrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator

/** Whole scans of a folder on a NAS held in memory, without TMDB, on an in-memory database. */
@RunWith(RobolectricTestRunner::class)
class LibraryScannerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var scanner: LibraryScanner
    private val nas = FakeRemoteBrowser()
    private val showsNas = FakeRemoteBrowser()
    private val passes = SupervisorJob()
    private var now = START

    /** The most values one statement bound. */
    private val widest = AtomicInteger()
    private lateinit var films: SourceEntity

    @Before
    fun open() = runBlocking {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java)
            .addTypeConverter(SecretConverter(Secrets { key }))
            .setQueryCallback({ _, args -> widest.accumulateAndGet(args.size, ::maxOf) }, Runnable::run)
            .build()
        dao = db.dao()
        scanner = LibraryScanner(
            dao = dao,
            tmdb = { null },
            scope = CoroutineScope(passes),
            artwork = ArtworkCache(temp.newFolder("artwork")),
            browsers = { if (it.kind == SourceKind.SHOWS) showsNas else nas },
            clock = { now },
        )
        films = folder("Films", SourceKind.MOVIES)
    }

    @After
    fun close() = db.close()

    private suspend fun folder(name: String, kind: SourceKind): SourceEntity {
        val id = dao.insertSource(
            SourceEntity(
                name = name, kind = kind, protocol = Protocol.NFS, host = "nas", share = "media", path = name.lowercase(),
                username = "", password = Secret(""),
            )
        )
        return checkNotNull(dao.source(id))
    }

    /** One whole pass, as the app asks for it, waited for. */
    private suspend fun scan(refreshMetadata: Boolean = false) {
        scanner.scanAll(refreshMetadata)
        passes.children.toList().forEach { it.join() }
    }

    private suspend fun filePaths(source: SourceEntity = films) = dao.filesOf(source.id).mapNotNull { it.filePath }.sorted()

    private fun error(source: SourceEntity = films) = scanner.state.value.errors[source.name]?.kind

    private fun film(n: Int) = "Film $n (2020).mkv"

    /** A folder of [count] films, scanned. */
    private suspend fun filmFolder(count: Int) {
        repeat(count) { nas.put(film(it)) }
        scan()
    }

    @Test
    fun indexesFilmsAndShowsThenReadsNothingAgain() = runTest {
        val shows = folder("Séries", SourceKind.SHOWS)
        nas.put("Dune (2021)/Dune (2021).mkv")
        nas.put("Arrival (2016).mkv")
        showsNas.put("Severance/Season 1/Severance - S01E01.mkv")
        showsNas.put("Severance/Season 1/Severance - S01E02.mkv")
        scan()

        assertEquals(listOf("Arrival (2016).mkv", "Dune (2021)/Dune (2021).mkv"), filePaths())
        assertEquals(listOf("Arrival", "Dune"), dao.filesOf(films.id).map { it.title }.sorted())
        val episodes = dao.filesOf(shows.id).sortedBy { it.episodeNumber }
        assertEquals(listOf(1, 2), episodes.map { it.episodeNumber })
        assertEquals("Severance", dao.item(episodes.first().seriesId!!)?.title)
        assertEquals(4, nas.reads.size + showsNas.reads.size)

        nas.reads.clear()
        showsNas.reads.clear()
        scan()
        assertEquals("a pass with nothing new reads no file", 0, nas.reads.size + showsNas.reads.size)
    }

    @Test
    fun anUnreachableNasRemovesNothing() = runTest {
        nas.put("Dune (2021).mkv")
        scan()
        dao.setFavorite(dao.filesOf(films.id).single().id, true)

        nas.listFailure = ConnectException("Connection refused")
        scan()

        assertEquals(ScanErrorKind.UNREACHABLE, error())
        assertTrue(dao.filesOf(films.id).single().favorite)
    }

    @Test
    fun aNasThatStopsAnsweringEndsThePassAfterThreeFiles() = runTest {
        nas.put("Dune (2021).mkv")
        scan()

        repeat(5) { nas.put("New $it (2024).mkv") }
        nas.readFailure = SocketTimeoutException("timeout")
        nas.reads.clear()
        scan()

        assertEquals(ScanErrorKind.UNREACHABLE, error())
        assertEquals("gives up after three files, each with long timeouts", 3, nas.reads.size)
        assertTrue("Dune (2021).mkv" in filePaths())

        // Files it gave up on are read at the next pass.
        nas.readFailure = null
        nas.reads.clear()
        scan()
        assertEquals(5, nas.reads.size)
        assertEquals(null, error())
    }

    @Test
    fun anEmptyListingRemovesNothing() = runTest {
        nas.put("Dune (2021).mkv")
        nas.put("Arrival (2016).mkv")
        scan()
        val dune = dao.filesOf(films.id).first { it.title == "Dune" }
        dao.savePlayback(dune.id, positionMs = 600_000, durationMs = 9_000_000, played = false, lastPlayedAt = now)
        dao.setFavorite(dune.id, true)

        // The share answers, but its disk is not mounted.
        nas.clear()
        scan()

        assertEquals(ScanErrorKind.EMPTY, error())
        assertEquals(listOf("Arrival (2016).mkv", "Dune (2021).mkv"), filePaths())
        val kept = checkNotNull(dao.item(dune.id))
        assertEquals(600_000L, kept.positionMs)
        assertTrue(kept.favorite)
        assertEquals(null, kept.missingSince)
    }

    @Test
    fun aFolderNeverFilledMayListEmpty() = runTest {
        scan()
        assertEquals(null, error())
    }

    @Test
    fun manyFilesGoneAtOnceStayADay() = runTest {
        filmFolder(40)
        // More than 20 and than a quarter of the folder: a disk of the pool is offline.
        val offline = (0 until 21).map(::film)
        offline.forEach(nas::remove)
        scan()
        assertEquals(40, filePaths().size)
        assertEquals(setOf(START), dao.filesOf(films.id).filter { it.filePath in offline }.map { it.missingSince }.toSet())
        assertEquals(null, error())

        now += 23 * HOUR
        scan()
        assertEquals("held for a day", 40, filePaths().size)

        now += 2 * HOUR
        scan()
        assertEquals("still missing a day later", (21 until 40).map(::film).sorted(), filePaths())
    }

    @Test
    fun aFileBackIsNoLongerHeld() = runTest {
        filmFolder(40)
        (0 until 21).map(::film).forEach(nas::remove)
        scan()

        nas.put(film(0))
        now += 25 * HOUR
        scan()

        val back = dao.filesOf(films.id).single { it.filePath == film(0) }
        assertEquals(null, back.missingSince)
        assertEquals(20, filePaths().size)
    }

    @Test
    fun fewFilesGoneGoAtOnce() = runTest {
        filmFolder(40)
        (0 until 20).map(::film).forEach(nas::remove)
        scan()
        assertEquals((20 until 40).map(::film).sorted(), filePaths())
    }

    @Test
    fun moreFilesGoneThanAStatementTakes() = runTest {
        filmFolder(1_200)
        // Sorted into a folder per letter: every film leaves the path it had.
        val moved = (0 until 1_200).map { "${film(it).first()}/${film(it)}" }
        (0 until 1_200).forEach { nas.rename(film(it), moved[it]) }
        widest.set(0)
        scan()
        assertEquals(null, error())
        assertEquals("held, beside the new paths", 2_400, filePaths().size)

        now += 25 * HOUR
        scan()
        assertEquals(moved.sorted(), filePaths())
        assertTrue("${widest.get()} values in one statement", widest.get() <= ANDROID_9_LIMIT)
    }

    @Test
    fun aFilmUpgradedByRadarrKeepsWhatTheUserDid() = runTest {
        nas.put("Dune (2021)/Dune (2021) Bluray-1080p.mkv", size = 30_000_000_000)
        nas.put("Arrival (2016)/Arrival (2016) Bluray-1080p.mkv")
        scan()
        val dune = dao.filesOf(films.id).single { it.title == "Dune" }
        dao.savePlayback(dune.id, positionMs = 600_000, durationMs = 9_000_000, played = false, lastPlayedAt = now)
        dao.setFavorite(dune.id, true)
        dao.setUserTmdbId(dune.id, 438631)
        dao.setUserPoster(dune.id, "/chosen.jpg")

        nas.remove("Dune (2021)/Dune (2021) Bluray-1080p.mkv")
        nas.put("Dune (2021)/Dune (2021) Remux-2160p.mkv", size = 80_000_000_000)
        nas.reads.clear()
        scan()

        val upgraded = dao.filesOf(films.id).single { it.title == "Dune" }
        assertEquals(dune.id, upgraded.id)
        assertEquals("Dune (2021)/Dune (2021) Remux-2160p.mkv", upgraded.filePath)
        assertEquals(80_000_000_000L, upgraded.fileSize)
        assertEquals(dune.addedAt, upgraded.addedAt)
        assertEquals(600_000L, upgraded.positionMs)
        assertEquals(now, upgraded.lastPlayedAt)
        assertTrue(upgraded.favorite)
        assertEquals(438631, upgraded.userTmdbId)
        assertEquals("/chosen.jpg", upgraded.userPosterPath)
        assertEquals("the new file is read", listOf(upgraded.filePath), nas.reads)
        assertEquals(80_000_000_000L, MediaProbe.decode(upgraded.mediaInfo)?.sizeBytes)
    }

    @Test
    fun filmsUpgradedSideBySideKeepTheirRows() = runTest {
        nas.put("Dune (2021) Bluray-1080p.mkv")
        nas.put("Arrival (2016) WEBDL-1080p.mkv")
        nas.put("Heat (1995).mkv")
        scan()
        val before = dao.filesOf(films.id).associate { it.title to it.id }

        nas.rename("Dune (2021) Bluray-1080p.mkv", "Dune (2021) Remux-2160p.mkv")
        nas.rename("Arrival (2016) WEBDL-1080p.mkv", "Arrival (2016) Bluray-2160p.mkv")
        scan()

        val after = dao.filesOf(films.id)
        assertEquals(before, after.associate { it.title to it.id })
        assertEquals(listOf("Arrival (2016) Bluray-2160p.mkv", "Dune (2021) Remux-2160p.mkv", "Heat (1995).mkv"), filePaths())
    }

    @Test
    fun anotherFilmBesideIsNotARename() = runTest {
        nas.put("Dune (2021).mkv")
        nas.put("Heat (1995).mkv")
        scan()
        val dune = dao.filesOf(films.id).single { it.title == "Dune" }
        dao.setFavorite(dune.id, true)

        nas.remove("Dune (2021).mkv")
        nas.put("Arrival (2016).mkv")
        scan()

        val arrival = dao.filesOf(films.id).single { it.title == "Arrival" }
        assertTrue(arrival.id != dune.id)
        assertEquals(false, arrival.favorite)
        assertEquals(null, dao.item(dune.id))
    }

    @Test
    fun anEpisodeUpgradedBySonarrStaysWatched() = runTest {
        val shows = folder("Séries", SourceKind.SHOWS)
        showsNas.put("Severance/Season 1/Severance - S01E01 - Good News About Hell WEBDL-1080p.mkv")
        showsNas.put("Severance/Season 1/Severance - S01E02 - Half Loop WEBDL-1080p.mkv")
        scan()
        val pilot = dao.filesOf(shows.id).single { it.episodeNumber == 1 }
        dao.setPlayed(pilot.id, played = true, now = now)

        showsNas.rename(
            "Severance/Season 1/Severance - S01E01 - Good News About Hell WEBDL-1080p.mkv",
            "Severance/Season 1/Severance - S01E01 - Good News About Hell Bluray-2160p.mkv",
        )
        scan()

        val upgraded = dao.filesOf(shows.id).single { it.episodeNumber == 1 }
        assertEquals(pilot.id, upgraded.id)
        assertTrue(upgraded.played)
        assertEquals(pilot.addedAt, upgraded.addedAt)
        assertEquals(2, dao.episodesOfSeries(checkNotNull(pilot.seriesId)).size)
    }

    @Test
    fun aFileReplacedUnderTheSameNameIsReadAgain() = runTest {
        nas.put("Dune (2021).mkv", size = 30_000_000_000)
        scan()
        assertEquals(emptyList<String>(), dao.filesOf(films.id).single().badges)

        // A remux in its place, under the same name; or the rest of a file read while it was copied.
        nas.put("Dune (2021).mkv", size = 80_000_000_000, bytes = MkvProbeTest().sampleFile())
        nas.reads.clear()
        scan()

        val dune = dao.filesOf(films.id).single()
        assertEquals(listOf("Dune (2021).mkv"), nas.reads)
        assertEquals(80_000_000_000L, dune.fileSize)
        assertEquals(80_000_000_000L, MediaProbe.decode(dune.mediaInfo)?.sizeBytes)
        assertEquals(REMUX_BADGES, dune.badges)

        nas.reads.clear()
        scan()
        assertEquals("read once", emptyList<String>(), nas.reads)
    }

    @Test
    fun anEpisodeReplacedUnderTheSameNameIsReadAgain() = runTest {
        val shows = folder("Séries", SourceKind.SHOWS)
        val pilot = "Severance/Season 1/Severance - S01E01.mkv"
        showsNas.put(pilot, size = 2_000_000_000)
        scan()

        showsNas.put(pilot, size = 9_000_000_000, bytes = MkvProbeTest().sampleFile())
        showsNas.reads.clear()
        scan()

        val episode = dao.filesOf(shows.id).single()
        assertEquals(listOf(pilot), showsNas.reads)
        assertEquals(9_000_000_000L, episode.fileSize)
        assertEquals(REMUX_BADGES, episode.badges)

        showsNas.reads.clear()
        scan()
        assertEquals("read once", emptyList<String>(), showsNas.reads)
    }

    private companion object {
        val REMUX_BADGES = listOf("4K", "Dolby Vision", "HDR10", "TrueHD Atmos", "7.1")
        const val ANDROID_9_LIMIT = 999
        const val START = 1_800_000_000_000L
        const val HOUR = 60 * 60_000L
    }
}
