package app.houselights.providers.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.ImageRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/**
 * Adding and editing folders: one the library already holds is refused, one removed meanwhile stays removed, and the
 * same folder at a new address keeps its titles and what was done with them, and is scanned again.
 */
@RunWith(RobolectricTestRunner::class)
class LocalLibraryTest {
    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var library: LocalLibrary
    private lateinit var context: Context
    private lateinit var artwork: ArtworkCache

    /** Statements the library ran, from any thread. */
    private val statements = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun open() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = testDatabase {
            allowMainThreadQueries()
            setQueryCallback({ sql, _ -> statements += sql }, Runnable::run)
        }
        dao = db.dao()
        artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        // The scan requested after an edit never runs: it would go to the network.
        library = LocalLibrary(context, dao, idleScanner(dao), artwork) {}
    }

    @After
    fun close() = db.close()

    private suspend fun folder() = dao.insertSource(
        smbFolder("films", password = "old", domain = "WORKGROUP").copy(lastScanAt = 2_000, firstScanAt = 1_000),
    )

    /**
     * A library that can no longer be read (a damaged database, a worn-out storage) ends what screens follow of it, said in
     * the log, where Réglages › Bibliothèque crashed reading it; none held, so that home goes on without its note.
     */
    @Test
    fun aLibraryThatCannotBeReadEndsWhatScreensFollow() = runTest {
        val films = checkNotNull(dao.source(folder())).toFolder()
        // Its tables gone from under it, reading them fails as a damaged database's do.
        listOf("sources", "items").forEach { db.openHelper.writableDatabase.execSQL("ALTER TABLE $it RENAME TO ${it}_away") }
        val followed = listOf(
            library.folders, library.titleCount, library.toIdentifyCount, library.folderCounts, library.ignored,
            library.latestPosters(films, 3), library.latestPosters(3), library.firstTitles(3), library.firstTitleCount,
        )
        followed.forEach { assertEquals(emptyList<Any>(), it.toList()) }
        assertEquals(listOf(emptyList<HeldRow>()), library.held.toList())
    }

    @Test
    fun anEditedFolderKeepsItsTitlesAndWhatWasDoneWithThem() = runTest {
        val id = folder()
        val film = dao.insert(
            ItemEntity(
                kind = ItemKind.MOVIE, sourceId = id, title = "Heat", sortTitle = "heat", filePath = "Heat.mkv",
                addedAt = 0, positionMs = 4_200_000, favorite = true, userTmdbId = 949, lastPlayedAt = 3_000,
            ),
        )

        assertEquals(FolderSave.Saved, library.updateFolder(id, EDITED))

        // The new address and account; the folder's kind, domain and scans as they were.
        val kept = EDITED.copy(id = id, kind = SourceKind.MOVIES, domain = "WORKGROUP", lastScanAt = 2_000, firstScanAt = 1_000)
        assertEquals(listOf(kept), dao.sources())

        dao.item(film)!!.let {
            assertEquals(id, it.sourceId)
            assertEquals(4_200_000L, it.positionMs)
            assertTrue(it.favorite)
            assertEquals(949, it.userTmdbId)
            assertEquals(3_000L, it.lastPlayedAt)
        }
    }

    /** Home and the settings read a folder's address and last scan, never its account. */
    @Test
    fun theScreensReadAFolderWithoutItsAccount() = runTest {
        val id = folder()
        val shown = Folder(id, "films", SourceKind.MOVIES, Protocol.SMB, "192.168.1.10", "192.168.1.10/media/films", lastScanAt = 2_000)
        assertEquals(listOf(shown), library.folders.first())

        library.removeFolder(shown)
        assertTrue(dao.sources().isEmpty())
        // Already gone: nothing is deleted, and nothing fails.
        statements.clear()
        library.removeFolder(shown)
        assertTrue("deleted: $statements", statements.none { it.trimStart().startsWith("DELETE", ignoreCase = true) })
    }

    /** Whether there is a library of folders, followed as they come and go. */
    @Test
    fun anyFolderFollowsTheFolders() = runTest {
        assertFalse(library.anyFolder.first())
        val id = folder()
        assertTrue(library.anyFolder.first())
        dao.removeSource(id)
        assertFalse(library.anyFolder.first())
    }

    /** Folders that cannot be read are taken as there: home then says why, rather than the app offer to start again. */
    @Test
    fun foldersThatCannotBeReadCountAsFolders() = runTest {
        db.openHelper.writableDatabase.execSQL("DROP TABLE sources")
        assertTrue(library.anyFolder.first())
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
                dao = dao,
                tmdb = { null },
                scope = CoroutineScope(passes),
                artwork = artwork,
                browsers = { nas },
                closeIdle = {},
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

    /**
     * A scan writing a film every 50 ms: Réglages › Bibliothèque asks its counts and posters again every 2 s at most,
     * not at each write, showing the first answers alone meanwhile, then the latest. On the test's clock, Room answering
     * on its thread.
     */
    @Test
    fun aScansWritesAskTheFolderCountsAgainEveryTwoSecondsAtMost() = runTest {
        val db = testDatabase {
            onCallingThread()
            setQueryCallback({ sql, _ -> statements += sql }, Runnable::run)
        }
        try {
            val dao = db.dao()
            val library = LocalLibrary(context, dao, idleScanner(dao), artwork) {}
            val id = dao.insertSource(nfs("nas", "volume1/media", "films"))
            val counted = mutableListOf<Int>()
            val posters = mutableListOf<List<ImageRef>>()
            backgroundScope.launch { library.folderCounts.collect { counted += it[id]?.titles ?: 0 } }
            backgroundScope.launch { library.latestPosters(dao.source(id)!!.toFolder(), 4).collect { posters += it } }
            runCurrent()
            assertEquals(listOf(0), counted)
            statements.clear()

            repeat(WRITES) { n ->
                dao.insert(
                    ItemEntity(
                        kind = ItemKind.MOVIE,
                        sourceId = id,
                        title = "Film $n",
                        sortTitle = "film $n",
                        filePath = "Film $n.mkv",
                        posterPath = "/p$n.jpg",
                        addedAt = n.toLong(),
                    ),
                )
                advanceTimeBy(WRITE_MS)
            }
            // Within 2 s of the first answers, though ten films were written: those answers alone, each query asked once more.
            assertEquals(listOf(0), counted)
            assertEquals(listOf(emptyList<ImageRef>()), posters)
            val asked = statements.toList()
            assertTrue("counts asked ${asked.count { "GROUP BY sourceId" in it }} times", asked.count { "GROUP BY sourceId" in it } <= 1)
            assertTrue("posters asked again", asked.count { "sourceId = ? AND kind IN" in it && "ORDER BY addedAt" in it } <= 1)

            advanceTimeBy(PACE_MS - WRITES * WRITE_MS - 1)
            assertEquals("the first answers until the 2 s are over", listOf(0), counted)
            advanceTimeBy(2)
            assertEquals("the latest, once they are", listOf(0, 10), counted)
            assertEquals(4, posters.last().size)
        } finally {
            db.close()
        }
    }

    /** A scanner whose scans never run: they would go to the network. */
    private fun idleScanner(dao: LibraryDao) =
        LibraryScanner(dao, { null }, CoroutineScope(Job().apply { cancel() }), artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})

    private companion object {
        /** A scan's writes, one film each, this far apart. */
        const val WRITE_MS = 50L

        /** How often Réglages › Bibliothèque reads its counts again, and a moment more. */
        const val PACE_MS = 2_000L

        /** The films the scan writes meanwhile. */
        const val WRITES = 10

        /** As the form gives it: its kind is not the folder's and must be ignored. */
        val EDITED = smbFolder("films-4k", SourceKind.SHOWS, host = "192.168.1.20", share = "video", password = "new")
    }

    private fun nfs(host: String, share: String, path: String, protocol: Protocol = Protocol.NFS) = SourceEntity(
        name = path.substringAfterLast('/').ifBlank { share },
        kind = SourceKind.MOVIES,
        protocol = protocol,
        host = host,
        share = share,
        path = path,
        username = "",
        password = Secret(""),
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

    /**
     * The add-folder flow's first step offers the NAS the folders are on, one per host however typed (case, spaces),
     * the latest used first, spelled as its latest folder spells it, with their protocols (the latest folder's first)
     * and their folders' names, by name.
     */
    @Test
    fun theNasAlreadyUsedAreTheFoldersHosts() = runTest {
        assertEquals(emptyList<UsedNas>(), library.usedNas())
        library.addFolder(nfs("DS920.local", "volume1/video", "Films").copy(name = "Films"))
        library.addFolder(nfs("192.168.1.31", "media", "films").copy(name = "Films"))
        library.addFolder(nfs(" ds920.local ", "volume1/video", "4K").copy(name = "4K"))
        library.addFolder(nfs("ds920.local", "video", "Séries", Protocol.SMB).copy(name = "Séries", kind = SourceKind.SHOWS))
        library.addFolder(nfs("192.168.1.31", "media", "séries").copy(name = "Séries", kind = SourceKind.SHOWS))
        assertEquals(
            listOf(
                UsedNas("192.168.1.31", listOf(Protocol.NFS), listOf("Films", "Séries")),
                UsedNas("ds920.local", listOf(Protocol.SMB, Protocol.NFS), listOf("4K", "Films", "Séries")),
            ),
            library.usedNas(),
        )
    }

    /**
     * « Ajouter un dossier » reads the folders of the NAS chosen, however its host is spelled, the latest first, to reuse
     * their account and identity and to say how each row stands to them; and the kinds the library holds, to ask first
     * for the one it lacks.
     */
    @Test
    fun theFlowReadsTheFoldersOfTheNasChosenAndTheKindsHeld() = runTest {
        assertEquals(emptySet<SourceKind>(), library.kinds())
        library.addFolder(nfs("DS920.local", "volume1/video", "Films"))
        library.addFolder(nfs("192.168.1.31", "media", "films"))
        library.addFolder(nfs("ds920.local ", "video", "Séries", Protocol.SMB).copy(kind = SourceKind.SHOWS))
        assertEquals(listOf("Séries", "Films"), library.foldersOn("ds920.local").map { it.path })
        assertEquals(listOf("films"), library.foldersOn("192.168.1.31").map { it.path })
        assertEquals(setOf(SourceKind.MOVIES, SourceKind.SHOWS), library.kinds())
    }

    /**
     * On the player's own disks, a folder nests with another of the same disk, whatever the case of its names (the
     * storage and FAT, exFAT or NTFS disks ignore it); the same path on another disk, or on a NAS, holds other files.
     */
    @Test
    fun aFolderOfTheSameDiskIsNotAddedTwice() = runTest {
        assertEquals(FolderSave.Saved, library.addFolder(diskFolder(path = "Vidéos/Films")))
        val films = dao.sources().single()

        assertEquals(FolderSave.Overlaps(films), library.addFolder(diskFolder(path = "vidéos/films/")))
        assertEquals(FolderSave.Overlaps(films), library.addFolder(diskFolder(path = "")))
        assertEquals(FolderSave.Overlaps(films), library.addFolder(diskFolder(disk = "1234-abcd", path = "Vidéos/Films/4K")))

        assertEquals(FolderSave.Saved, library.addFolder(diskFolder(path = "Vidéos/Séries")))
        assertEquals(FolderSave.Saved, library.addFolder(diskFolder(disk = "5678-EF01", path = "Vidéos/Films")))
        assertEquals(FolderSave.Saved, library.addFolder(diskFolder(disk = Volume.INTERNAL, path = "Vidéos/Films")))
        assertEquals(FolderSave.Saved, library.addFolder(nfs(USB_DISK, "", "Vidéos/Films")))
        assertEquals(5, dao.sources().size)
    }

    /**
     * A folder on the player's own disks is on no NAS: the add-folder flow offers no NAS for it, and reads it among the
     * folders of its disk alone, to say how a folder there stands to the library.
     */
    @Test
    fun aDisksFolderIsOnNoNas() = runTest {
        library.addFolder(nfs("nas", "volume1/media", "films"))
        library.addFolder(diskFolder())
        assertEquals(listOf(UsedNas("nas", listOf(Protocol.NFS), listOf("films"))), library.usedNas())
        assertEquals(listOf("films"), library.foldersOn("nas").map { it.path })
        assertEquals(listOf("Films"), library.foldersOn(USB_DISK).map { it.path })
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
