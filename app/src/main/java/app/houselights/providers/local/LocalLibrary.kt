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

/**
 * The local library as screens use it besides browsing: its folders and what they hold, scans, the
 * space it takes and its upkeep. Screens go through it, never through the database or the scanner.
 */
class LocalLibrary(
    private val context: Context,
    private val dao: LibraryDao,
    private val scanner: LibraryScanner,
    private val artwork: ArtworkCache,
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

    /** Adds a folder and looks for what it holds. */
    suspend fun addFolder(folder: SourceEntity) {
        dao.insertSource(folder)
        scanner.scanAll()
    }

    suspend fun folder(id: Long): SourceEntity? = dao.source(id)

    /**
     * Gives the folder [id] the address and sign-in of [folder] (an IP or a password changed, SMB
     * became NFS), and looks again at what it holds. It stays the same folder: its titles keep what
     * was watched and chosen, which removing and adding it again would lose. Its kind stays.
     */
    suspend fun updateFolder(id: Long, folder: SourceEntity) {
        val before = dao.source(id) ?: return
        with(folder) { dao.updateConnection(id, name, protocol, host, share, path, username, password) }
        // A session or mount kept on the old address would still answer for the folder. Off the main
        // thread: both wait for the network operation under way on it.
        withContext(Dispatchers.IO) {
            when (before.protocol) {
                Protocol.SMB -> SmbBrowser.forget(before)
                Protocol.NFS -> NfsBrowser.forget(before)
            }
        }
        scanner.scanAll()
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
