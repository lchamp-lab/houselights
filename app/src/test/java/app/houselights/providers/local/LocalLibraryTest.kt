package app.houselights.providers.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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

/** Editing a folder: the same folder at a new address keeps its titles and what was done with them. */
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
        db = testDatabase { allowMainThreadQueries() }
        dao = db.dao()
        artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        // The scan requested after an edit never runs: it would go to the network.
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

        assertEquals(FolderSave.Saved, library.updateFolder(id, EDITED))

        val edited = dao.source(id)!!
        assertEquals(1, dao.sources().size)
        assertEquals(Protocol.SMB, edited.protocol)
        assertEquals("192.168.1.20", edited.host)
        assertEquals("video", edited.share)
        assertEquals("films-4k", edited.path)
        assertEquals("films-4k", edited.name)
        assertEquals("new", edited.password.value)
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
        assertEquals("the form hears that nothing was saved", FolderSave.Gone, library.updateFolder(id, EDITED))
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
        /** As the form gives it: its kind is not the folder's and must be ignored. */
        val EDITED = SourceEntity(
            name = "films-4k", kind = SourceKind.SHOWS, protocol = Protocol.SMB, host = "192.168.1.20", share = "video",
            path = "films-4k", username = "kodi", password = Secret("new"),
        )
    }

    private fun nfs(host: String, share: String, path: String, protocol: Protocol = Protocol.NFS) = SourceEntity(
        name = path.substringAfterLast('/').ifBlank { share }, kind = SourceKind.MOVIES, protocol = protocol, host = host,
        share = share, path = path, username = "", password = Secret(""),
    )

    /** The same folder twice, or one inside another: every title would show twice, so the second is refused. */
    @Test
    fun aFolderTheLibraryAlreadyHoldsIsNotAddedAgain() = runTest {
        assertEquals(FolderSave.Saved, library.addFolder(nfs("nas", "volume1/media", "films")))
        val films = dao.sources().single()

        assertEquals(FolderSave.Overlaps(films), library.addFolder(nfs("NAS", "/volume1/media/", "films/")))
        // The share's root contains the folder, and the folder contains a folder inside it.
        assertEquals(FolderSave.Overlaps(films), library.addFolder(nfs("nas", "volume1/media", "")))
        assertEquals(FolderSave.Overlaps(films), library.addFolder(nfs("nas", "volume1", "media/films/4K")))
        assertEquals(1, dao.sources().size)

        // A folder beside it, a name that only starts the same, another server or protocol: other files.
        assertEquals(FolderSave.Saved, library.addFolder(nfs("nas", "volume1/media", "series")))
        assertEquals(FolderSave.Saved, library.addFolder(nfs("nas", "volume1/media", "films-4k")))
        assertEquals(FolderSave.Saved, library.addFolder(nfs("nas2", "volume1/media", "films")))
        assertEquals(FolderSave.Saved, library.addFolder(nfs("nas", "volume1/media", "films", Protocol.SMB)))
        assertEquals(5, dao.sources().size)

        // An SMB share ignores case (Samba's default, as on Windows): the same folder, written differently.
        val smb = dao.sources().single { it.protocol == Protocol.SMB }
        assertEquals(FolderSave.Overlaps(smb), library.addFolder(nfs("nas", "Volume1/Media", "Films", Protocol.SMB)))
        // An NFS path does not: another folder.
        assertEquals(FolderSave.Saved, library.addFolder(nfs("nas", "volume1/media", "Films")))
        assertEquals(6, dao.sources().size)

        // An export "/" is the host's root: it holds every folder of the host, by the same protocol.
        assertTrue(nfs("nas", "/", "").overlaps(films))
        assertFalse(nfs("nas", "/", "").overlaps(smb))
        assertTrue(library.addFolder(nfs("nas", "/", "")) is FolderSave.Overlaps)
        assertEquals(FolderSave.Saved, library.addFolder(nfs("nas3", "/", "")))
        assertEquals(FolderSave.Overlaps(dao.sources().single { it.host == "nas3" }), library.addFolder(nfs("nas3", "export", "films")))
    }

    @Test
    fun aFolderEditedOntoAnotherIsNotSaved() = runTest {
        library.addFolder(nfs("nas", "volume1/media", "films"))
        library.addFolder(nfs("nas", "volume1/media", "series"))
        val (films, series) = dao.sources().sortedBy { it.id }
        assertEquals(FolderSave.Overlaps(films), library.updateFolder(series.id, nfs("nas", "volume1/media", "films")))
        assertEquals("series", dao.source(series.id)!!.path)
        assertEquals(FolderSave.Saved, library.updateFolder(films.id, nfs("192.168.1.10", "volume1/media", "films")))
    }
}
