package app.houselights.providers.local

import android.content.Context
import app.houselights.core.Definition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Space the library takes on the device. */
data class LibraryStorage(val artworkFiles: Int, val artworkBytes: Long, val databaseBytes: Long)

/** What saving a folder did. */
sealed interface FolderSave {
    data object Saved : FolderSave

    /** The folder edited is no longer in the library (removed meanwhile): nothing was saved. */
    data object Gone : FolderSave

    /**
     * [existing], already in the library, is the same folder, or one inside or around it: every title
     * would show twice. Nothing was saved.
     */
    data class Overlaps(val existing: SourceEntity) : FolderSave
}

/**
 * The local library as screens use it besides browsing: its folders and what they hold, scans, the
 * space it takes and its upkeep. Screens go through it, never through the database or the scanner.
 */
class LocalLibrary(
    private val context: Context,
    private val dao: LibraryDao,
    private val scanner: LibraryScanner,
    private val artwork: ArtworkCache,
    /** Closes the sessions and mounts kept open on a folder's address; tests hand in their own. */
    private val forget: suspend (SourceEntity) -> Unit = ::forgetConnections,
) {
    val folders: Flow<List<SourceEntity>> = dao.observeSources()

    /** Films and shows, all folders together. */
    val titleCount: Flow<Int> = dao.observeAllTitleCount()

    val scan: StateFlow<ScanState> = scanner.state

    fun titleCount(folder: SourceEntity): Flow<Int> = dao.observeTitleCount(folder.id)

    fun ultraHdCount(folder: SourceEntity): Flow<Int> = dao.observeDefinitionCount(folder.id, Definition.ULTRA_HD.token)

    /** Posters of the titles found last in [folder], the user's choice first. */
    fun latestPosters(folder: SourceEntity, limit: Int): Flow<List<String>> = dao.observePosters(folder.id, limit)

    suspend fun hasFolders(): Boolean = dao.sources().isNotEmpty()

    /** The folder added last: the next one usually lives on the same server. */
    suspend fun lastFolder(): SourceEntity? = dao.sources().maxByOrNull { it.id }

    /**
     * A folder already in the library, other than [except], that [folder] would hold titles of twice:
     * the same folder, or one inside or around it ([overlaps]); null when there is none.
     */
    suspend fun overlapping(folder: SourceEntity, except: Long? = null): SourceEntity? =
        dao.sources().firstOrNull { it.id != except && it.overlaps(folder) }

    /** Adds a folder and looks for what it holds; refused when the library already holds it ([FolderSave.Overlaps]). */
    suspend fun addFolder(folder: SourceEntity): FolderSave {
        overlapping(folder)?.let { return FolderSave.Overlaps(it) }
        dao.insertSource(folder)
        scanner.scanAll()
        return FolderSave.Saved
    }

    suspend fun folder(id: Long): SourceEntity? = dao.source(id)

    /**
     * Gives the folder [id] the address and sign-in of [folder] (an IP or a password changed, SMB
     * became NFS), and looks again at what it holds. It stays the same folder: its titles keep what
     * was watched and chosen, which removing and adding it again would lose. Its kind stays. Nothing
     * is saved when the folder is no longer in the library (removed meanwhile, [FolderSave.Gone]), or
     * when its new address is another folder's, or inside or around it ([FolderSave.Overlaps]).
     */
    suspend fun updateFolder(id: Long, folder: SourceEntity): FolderSave {
        val before = dao.source(id) ?: return FolderSave.Gone
        overlapping(folder, except = id)?.let { return FolderSave.Overlaps(it) }
        try {
            with(folder) { dao.updateConnection(id, name, protocol, host, share, path, username, password) }
            // A session or mount kept on the old address would still answer for the folder.
            forget(before)
        } finally {
            // Even when the wait above is cut short: the folder may already have its new address,
            // and its last error stays shown until a scan reads it again.
            scanner.scanAll()
        }
        return FolderSave.Saved
    }

    suspend fun removeFolder(folder: SourceEntity) = scanner.removeSource(folder)

    fun scan(refreshMetadata: Boolean = false) = scanner.scanAll(refreshMetadata)

    /** A setting the library's screens read changed (when collections show): they reload. */
    fun settingsChanged() = scanner.notifyChanged()

    suspend fun cleanup(): CleanupResult = scanner.cleanup()

    suspend fun storage(): LibraryStorage = withContext(Dispatchers.IO) {
        val usage = artwork.usage()
        val db = context.getDatabasePath(LibraryDatabase.NAME)
        LibraryStorage(
            artworkFiles = usage.files,
            artworkBytes = usage.bytes,
            // Room keeps recent writes in a write-ahead log next to the file.
            databaseBytes = listOf(db, File(db.path + "-wal")).sumOf { if (it.exists()) it.length() else 0L },
        )
    }
}

/**
 * True when this folder and [other] would read the same files: the same protocol and host (whatever
 * its case), and the path of one, share included, is the other's or inside it.
 */
internal fun SourceEntity.overlaps(other: SourceEntity): Boolean {
    if (protocol != other.protocol || !host.trim().equals(other.host.trim(), ignoreCase = true)) return false
    val mine = segments(share) + segments(path)
    val theirs = segments(other.share) + segments(other.path)
    val (shorter, longer) = if (mine.size <= theirs.size) mine to theirs else theirs to mine
    return shorter.isNotEmpty() && longer.take(shorter.size) == shorter
}

/** True when this folder and [other] are the same one, perhaps written another way. */
internal fun SourceEntity.samePlace(other: SourceEntity): Boolean =
    overlaps(other) && segments(share) + segments(path) == segments(other.share) + segments(other.path)

/** The folders of a path, without empty ones (a slash doubled, at either end). */
private fun segments(path: String) = path.split('/').filter { it.isNotEmpty() }

/** Off the main thread: both wait for the network operation under way on the folder. */
private suspend fun forgetConnections(folder: SourceEntity) = withContext(Dispatchers.IO) {
    when (folder.protocol) {
        Protocol.SMB -> SmbBrowser.forget(folder)
        Protocol.NFS -> NfsBrowser.forget(folder)
    }
}
