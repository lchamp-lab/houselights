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
    private lateinit var films: SourceEntity

    @Before
    fun open() = runBlocking {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java)
            .addTypeConverter(SecretConverter(Secrets { key }))
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

    private companion object {
        const val START = 1_800_000_000_000L
        const val HOUR = 60 * 60_000L
    }
}
