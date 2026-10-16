package app.houselights.providers.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.Secrets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator

/** Editing a folder: the same folder at a new address, its titles and what was done with them kept. */
@RunWith(RobolectricTestRunner::class)
class LocalLibraryTest {
    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var library: LocalLibrary
    private lateinit var context: Context
    private lateinit var artwork: ArtworkCache

    @Before
    fun open() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .addTypeConverter(SecretConverter(Secrets { key }))
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
        artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        // The scan asked after an edit never runs: it would go to the network.
        val idle = CoroutineScope(Job().apply { cancel() })
        val scanner = LibraryScanner(dao, { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
        library = LocalLibrary(context, dao, scanner, artwork) {}
    }

    @After
    fun close() = db.close()

    private suspend fun folder() = dao.insertSource(
        SourceEntity(
            name = "films", kind = SourceKind.MOVIES, protocol = Protocol.SMB, host = "192.168.1.10", share = "media",
            path = "films", username = "kodi", password = Secret("old"), domain = "WORKGROUP", lastScanAt = 2_000,
            firstScanAt = 1_000,
        )
    )

    @Test
    fun anEditedFolderKeepsItsTitlesAndWhatWasDoneWithThem() = runTest {
        val id = folder()
        val film = dao.insert(
            ItemEntity(
                kind = ItemKind.MOVIE, sourceId = id, title = "Heat", sortTitle = "heat", filePath = "Heat.mkv",
                addedAt = 0, positionMs = 4_200_000, favorite = true, userTmdbId = 949, lastPlayedAt = 3_000,
            )
        )

        assertTrue(library.updateFolder(id, EDITED))

        val edited = dao.source(id)!!
        assertEquals(1, dao.sources().size)
        assertEquals(Protocol.SMB, edited.protocol)
        assertEquals("192.168.1.20", edited.host)
        assertEquals("video", edited.share)
        assertEquals("films-4k", edited.path)
        assertEquals("films-4k", edited.name)
        assertEquals("new", edited.password.value)
        // What the form does not edit stays.
        assertEquals(SourceKind.MOVIES, edited.kind)
        assertEquals("WORKGROUP", edited.domain)
        assertEquals(2_000L, edited.lastScanAt)
        assertEquals(1_000L, edited.firstScanAt)

        dao.item(film)!!.let {
            assertEquals(id, it.sourceId)
            assertEquals(4_200_000L, it.positionMs)
            assertTrue(it.favorite)
            assertEquals(949, it.userTmdbId)
            assertEquals(3_000L, it.lastPlayedAt)
        }
    }

    @Test
    fun aFolderRemovedMeanwhileStaysRemoved() = runTest {
        val id = folder()
        dao.removeSource(id)
        assertFalse("the form hears that nothing was saved", library.updateFolder(id, EDITED))
        assertTrue(dao.sources().isEmpty())
    }

    @Test
    fun anEditCutShortStillAsksForAScan() = runTest {
        val id = folder()
        val nas = FakeRemoteBrowser()
        var listings = 0
        nas.onList = { listings++ }
        val passes = SupervisorJob()
        val forgetting = CompletableDeferred<Unit>()
        val scanning = LocalLibrary(
            context,
            dao,
            LibraryScanner(
                dao = dao, tmdb = { null }, scope = CoroutineScope(passes), artwork = artwork, browsers = { nas }, closeIdle = {},
            ),
            artwork,
            // The session kept on the old address is busy with a listing: closing it waits.
            forget = {
                forgetting.complete(Unit)
                awaitCancellation()
            },
        )

        val edit = launch { scanning.updateFolder(id, EDITED) }
        forgetting.await()
        edit.cancelAndJoin()
        passes.children.toList().forEach { it.join() }

        assertEquals("192.168.1.20", dao.source(id)!!.host)
        assertEquals("the folder at its new address was scanned", 1, listings)
    }

    private companion object {
        /** As the form gives it: its kind is not the folder's, and must not be taken. */
        val EDITED = SourceEntity(
            name = "films-4k", kind = SourceKind.SHOWS, protocol = Protocol.SMB, host = "192.168.1.20", share = "video",
            path = "films-4k", username = "kodi", password = Secret("new"),
        )
    }
}
