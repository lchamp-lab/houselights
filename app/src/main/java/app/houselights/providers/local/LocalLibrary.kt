package app.houselights.providers.local

import android.content.Context
import app.houselights.core.Definition
import app.houselights.core.throttleLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class LibraryStorage(val artworkFiles: Int, val artworkBytes: Long, val databaseBytes: Long)

sealed interface FolderSave {
    data object Saved : FolderSave

    /** The edited source was removed meanwhile; nothing was saved. */
    data object Gone : FolderSave

    /** [existing] is the same folder or nests with it, so titles would appear twice; nothing was saved. */
    data class Overlaps(val existing: SourceEntity) : FolderSave
}

/** Library management facade for screens (sources, scans, storage, cleanup), hiding the database and scanner. */
class LocalLibrary(
    private val context: Context,
    private val dao: LibraryDao,
    private val scanner: LibraryScanner,
    private val artwork: ArtworkCache,
    /** Closes sessions and mounts kept open on a source's address; replaceable in tests. */
    private val forget: suspend (SourceEntity) -> Unit,
) {
    val folders: Flow<List<SourceEntity>> = dao.observeSources()

    val titleCount: Flow<Int> = dao.observeAllTitleCount().paced()

    val scan: StateFlow<ScanState> = scanner.state

    /** One query for all sources rather than two each, as Flow queries rerun on every scan write. */
    val folderCounts: Flow<Map<Long, FolderCounts>> =
        dao.observeFolderCounts(Definition.ULTRA_HD.token).paced().map { counts -> counts.associateBy { it.sourceId } }

    fun latestPosters(folder: SourceEntity, limit: Int): Flow<List<String>> = dao.observePosters(folder.id, limit).paced()

    /** The [limit] posters last added to the whole library. */
    fun latestPosters(limit: Int): Flow<List<String>> = dao.observeAllPosters(limit)

    suspend fun hasFolders(): Boolean = dao.sources().isNotEmpty()

    /** The most recently added source, as the next one is usually on the same server. */
    suspend fun lastFolder(): SourceEntity? = dao.sources().maxByOrNull { it.id }

    /** An existing source other than [except] that [folder] [overlaps]; null if none. */
    suspend fun overlapping(folder: SourceEntity, except: Long? = null): SourceEntity? =
        dao.sources().firstOrNull { it.id != except && it.overlaps(folder) }

    /** Adds a source and scans; refused when it overlaps an existing one ([FolderSave.Overlaps]). */
    suspend fun addFolder(folder: SourceEntity): FolderSave {
        overlapping(folder)?.let { return FolderSave.Overlaps(it) }
        dao.insertSource(folder)
        scanner.scanAll()
        return FolderSave.Saved
    }

    suspend fun folder(id: Long): SourceEntity? = dao.source(id)

    /**
     * Gives source [id] the address and credentials of [folder] and rescans, keeping its id and kind so titles keep
     * their user state, which removing and re-adding would lose.
     */
    suspend fun updateFolder(id: Long, folder: SourceEntity): FolderSave {
        val before = dao.source(id) ?: return FolderSave.Gone
        overlapping(folder, except = id)?.let { return FolderSave.Overlaps(it) }
        try {
            with(folder) { dao.updateConnection(id, name, protocol, host, share, path, username, password) }
            // A session or mount kept on the old address would otherwise still serve this source.
            forget(before)
        } finally {
            // Also on cancellation: the new address may be saved, and its last error stays until a scan.
            scanner.scanAll()
        }
        return FolderSave.Saved
    }

    suspend fun removeFolder(folder: SourceEntity) = scanner.removeSource(folder)

    fun scan(refreshMetadata: Boolean = false) = scanner.scanAll(refreshMetadata)

    /** Tells library screens to reload after a setting they read changed (collection visibility). */
    fun settingsChanged() = scanner.notifyChanged()

    suspend fun cleanup(): CleanupResult = scanner.cleanup()

    suspend fun storage(): LibraryStorage = withContext(Dispatchers.IO) {
        val usage = artwork.usage()
        val db = context.getDatabasePath(LibraryDatabase.NAME)
        LibraryStorage(
            artworkFiles = usage.files,
            artworkBytes = usage.bytes,
            // SQLite keeps recent writes in a write-ahead log next to the file.
            databaseBytes = listOf(db, File(db.path + "-wal")).sumOf { if (it.exists()) it.length() else 0L },
        )
    }
}

/** A query a scan reruns at each write, read at most every [PANE_PACE_MS] and only when its answer changes. */
private fun <T> Flow<T>.paced(): Flow<T> = throttleLatest(PANE_PACE_MS).distinctUntilChanged()

/** How often the settings' counts and posters follow a scan. */
private const val PANE_PACE_MS = 2_000L

/** True when both read the same files: same protocol and host, and one path (share included) contains the other. */
internal fun SourceEntity.overlaps(other: SourceEntity): Boolean {
    if (protocol != other.protocol || !host.trim().equals(other.host.trim(), ignoreCase = true)) return false
    val mine = place()
    val theirs = other.place()
    val (shorter, longer) = if (mine.size <= theirs.size) mine to theirs else theirs to mine
    return longer.take(shorter.size) == shorter
}

/** True when this source and [other] are the same folder, possibly spelled differently. */
internal fun SourceEntity.samePlace(other: SourceEntity): Boolean = overlaps(other) && place() == other.place()

/** Path segments from the host root; lowercase for SMB, which is case-insensitive (Samba's default, Windows). */
private fun SourceEntity.place(): List<String> {
    val segments = segments(share) + segments(path)
    return if (protocol == Protocol.SMB) segments.map { it.lowercase(Locale.ROOT) } else segments
}

private fun segments(path: String) = path.split('/').filter { it.isNotEmpty() }
