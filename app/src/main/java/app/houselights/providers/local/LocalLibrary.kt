package app.houselights.providers.local

import android.content.Context
import android.util.Log
import app.houselights.core.Definition
import app.houselights.core.ImageRef
import app.houselights.providers.local.TmdbClient.Companion.ImageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
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
    val folders: Flow<List<Folder>> = dao.observeSources().map { sources -> sources.map(SourceEntity::toFolder) }

    val titleCount: Flow<Int> = dao.observeAllTitleCount().paced()

    /** How many films and shows are to identify ([TO_IDENTIFY]). */
    val toIdentifyCount: Flow<Int> = dao.observeToIdentifyCount().paced()

    val scan: StateFlow<ScanState> = scanner.state

    /** One query for all sources rather than two each, as Flow queries rerun on every scan write. */
    val folderCounts: Flow<Map<Long, FolderCounts>> =
        dao.observeFolderCounts(Definition.ULTRA_HD.token).paced().map { counts -> counts.associateBy { it.sourceId } }

    /** The films and episodes a sudden drop holds while their files are missing ([MissingFiles.isDrop]), by folder and title. */
    val held: Flow<List<HeldRow>> = dao.observeHeld().paced()

    fun latestPosters(folder: Folder, limit: Int): Flow<List<ImageRef>> = dao.observePosters(folder.id, limit).paced().map(::posters)

    /** The [limit] posters last added to the whole library. */
    fun latestPosters(limit: Int): Flow<List<ImageRef>> = dao.observeAllPosters(limit).map(::posters)

    suspend fun hasFolders(): Boolean = dao.sources().isNotEmpty()

    /** Whether there is a folder at least, followed; true once they cannot be read, so that home says why. */
    val anyFolder: Flow<Boolean> = dao.observeAnySource().catch { e ->
        Log.w(TAG, "Reading whether there are folders failed", e)
        emit(true)
    }

    /** The most recently added source: the next one is usually on the same server, often in the same share. */
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
            with(folder) { dao.updateConnection(id, name, protocol, host, share, path, username, password, uid, gid) }
            // A session or mount kept on the old address would otherwise still serve this source.
            forget(before)
        } finally {
            // Also on cancellation: the new address may be saved, and its last error stays until a scan.
            scanner.scanAll()
        }
        return FolderSave.Saved
    }

    /** Removes [folder] and its titles, once a scan's open file in it is closed; nothing when it is already gone. */
    suspend fun removeFolder(folder: Folder) {
        dao.source(folder.id)?.let { scanner.removeSource(it) }
    }

    fun scan(refreshMetadata: Boolean = false) = scanner.scanAll(refreshMetadata)

    /** « Garder »: the titles held in [folder], those [shown], wait for their files; nothing asks again until another drop. */
    suspend fun keepHeld(folder: Long, shown: Collection<Long>) = scanner.keepHeld(folder, shown)

    /** « Retirer »: the titles held among rows [ids] leave the library, what the user did kept; nothing goes on the NAS. */
    suspend fun removeHeld(ids: Collection<Long>) = scanner.removeHeld(ids)

    /**
     * « Retirer de la bibliothèque » on a title held ([LocalId] [itemId]): it alone leaves, as [removeHeld] does. The id
     * of what holds it after: its own, the copy of the film left, an episode's show once the episode went; null once
     * nothing of it is left ([LibraryScanner.removeHeldTitle]).
     */
    suspend fun removeHeldTitle(itemId: String): String? {
        val key = (LocalId.parse(itemId) as? LocalId.Item)?.key ?: return itemId
        return scanner.removeHeldTitle(key)?.toString()
    }

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

private fun posters(paths: List<String>) = paths.map { LocalProvider.image(it, ImageType.POSTER) }

/**
 * A query a scan reruns at each write, run at most every [PANE_PACE_MS] and shown when its answer changes. Room runs
 * the next query once an answer is taken, so the wait holds the answer (up to a period old) and no buffer may take it.
 */
private fun <T> Flow<T>.paced(): Flow<T> = transform {
    emit(it)
    delay(PANE_PACE_MS)
}.distinctUntilChanged()

/** How often the settings' counts and posters follow a scan. */
private const val PANE_PACE_MS = 2_000L

private const val TAG = "Library"

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
