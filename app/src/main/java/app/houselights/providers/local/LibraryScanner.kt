package app.houselights.providers.local

import android.util.Log
import app.houselights.core.NoTmdbKeyException
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ScanState(
    val running: Boolean = false,
    val sourceName: String? = null,
    val processed: Int = 0,
    val total: Int = 0,
    val current: String? = null,
    /** Last error per source name, kept after the scan ends. */
    val errors: Map<String, ScanError> = emptyMap(),
)

/**
 * Incremental scanner: lists the files of each source, removes the missing ones (see [MissingFiles]),
 * and identifies new ones on TMDB. Files already known are never looked up again,
 * unless they were never matched (no TMDB key at the time) or a full refresh is asked.
 *
 * Choices made by the user (match, artwork) and their viewing state are never overwritten:
 * every write goes through [LibraryDao.saveScanned].
 */
class LibraryScanner(
    private val dao: LibraryDao,
    /** The TMDB client in use, or null without a key. */
    private val tmdb: () -> TmdbClient?,
    private val scope: CoroutineScope,
    private val artwork: ArtworkCache,
    /** True while a film plays: the scan waits, so as not to read the NAS while the player streams from it. */
    private val playing: StateFlow<Boolean> = MutableStateFlow(false),
    /** How a folder is read; tests hand in a NAS of their own. */
    private val browsers: (SourceEntity) -> RemoteBrowser = RemoteBrowser::of,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state

    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits while scanning, so screens show new entries progressively. */
    val changed: SharedFlow<Unit> = _changed

    private val lock = Mutex()

    private val files = ScanFiles(dao, artwork, browsers)
    private val movies = MovieIndexer(dao, files)
    private val shows = ShowIndexer(dao, files)

    fun scanAll(refreshMetadata: Boolean = false) {
        synchronized(passes) {
            // A folder added or a refresh asked during a scan is not lost: one more pass follows.
            passes.ask(refreshMetadata)
            if (passes.running) return
            passes.running = true
        }
        scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val refresh = synchronized(passes) { passes.next() } ?: break
                    scanAllSources(refresh)
                }
            } finally {
                synchronized(passes) { passes.running = false }
            }
        }
    }

    /** Scans asked for: one running, at most one more waiting. Guarded by its own monitor. */
    private class Passes {
        var running = false
        private var waiting = false
        private var refresh = false

        fun ask(refreshMetadata: Boolean) {
            waiting = true
            refresh = refresh || refreshMetadata
        }

        /** The next pass to run (true: refresh metadata), or null when none is waiting: the loop ends. */
        fun next(): Boolean? {
            if (!waiting) {
                running = false
                return null
            }
            waiting = false
            return refresh.also { refresh = false }
        }
    }

    private val passes = Passes()

    /**
     * Scans unless the last scan ended less than [maxAgeMs] ago. A NAS cannot tell the app that
     * files were added, so the app looks again by itself; an incremental scan only lists the
     * folders and processes what is new, which keeps it light.
     */
    fun scanIfStale(maxAgeMs: Long) {
        if (clock() - lastScanEnd < maxAgeMs) return
        scanAll()
    }

    @Volatile
    private var lastScanEnd = 0L

    /**
     * Removes cached artwork and cast rows nothing refers to anymore. Runs after every scan,
     * and on demand from the settings; never while an item is being processed.
     */
    suspend fun cleanup(): CleanupResult = withContext(Dispatchers.IO) {
        lock.withLock { cleanupLocked() }
    }

    /** Tells screens the library changed outside a scan (a choice made by the user). */
    fun notifyChanged() {
        _changed.tryEmit(Unit)
    }

    /**
     * Matches a film or show with the TMDB title the user chose, or goes back to automatic
     * matching when [tmdbId] is null. Seasons and episodes of a show follow. Throws when TMDB
     * cannot be reached, leaving the item as it was.
     */
    suspend fun identify(itemId: Long, tmdbId: Int?) {
        withContext(Dispatchers.IO) {
            val tmdb = client() ?: throw NoTmdbKeyException()
            lock.withLock {
                val item = dao.item(itemId) ?: return@withLock
                when (item.kind) {
                    ItemKind.MOVIE -> movies.identify(item, tmdbId, tmdb)
                    ItemKind.SERIES -> shows.identify(item, tmdbId, tmdb)
                    else -> error("Only films and shows can be identified")
                }
                // The film may have left its collection for another one.
                dao.deleteEmptyCollections()
            }
        }
        _changed.emit(Unit)
    }

    private fun client(): TmdbClient? = tmdb()

    private suspend fun cleanupLocked(): CleanupResult {
        val orphanCast = dao.deleteOrphanCast()
        // Based on what the library references, not on what the NAS shows: an unreachable
        // NAS never causes artwork to be deleted.
        val result = artwork.sweep(dao.cachedArtworkPaths())
        if (result.deletedFiles > 0 || orphanCast > 0) {
            Log.i(TAG, "Cleanup: ${result.deletedFiles} images (${result.freedBytes / 1024} KB), $orphanCast cast rows")
        }
        return result
    }

    private suspend fun scanAllSources(refreshMetadata: Boolean) {
        movies.startPass()
        shows.startPass()
        val errors = mutableMapOf<String, ScanError>()
        var changed = false
        try {
            for (source in dao.sources()) {
                suspendRunCatching { changed = scan(source, refreshMetadata) || changed }.onFailure { e ->
                    Log.w(TAG, "Scan of ${source.name} failed", e)
                    errors[source.name] = ScanErrors.classify(e)
                }
            }
            lock.withLock {
                suspendRunCatching {
                    // Rows a folder removed during the scan may have received meanwhile.
                    dao.deleteOrphanItems()
                    dao.deleteEmptySeasons()
                    dao.deleteEmptySeries()
                    dao.deleteEmptyCollections()
                    // Pictures only become unused when titles change or go.
                    if (changed) cleanupLocked()
                }.onFailure { Log.w(TAG, "Cleanup failed", it) }
            }
        } finally {
            // Whatever happened, the scan is over: the settings must not show it running forever.
            _state.value = ScanState(errors = errors)
            lastScanEnd = clock()
            SmbBrowser.closeAll()
        }
        // A scan that found nothing new leaves screens as they are.
        if (changed) _changed.emit(Unit)
    }

    /**
     * Removes a folder and everything found in it. Waits for the item being processed; a scan of that
     * folder stops at its next file.
     */
    suspend fun removeSource(source: SourceEntity) {
        withContext(Dispatchers.IO) {
            lock.withLock { dao.removeSource(source.id) }
        }
        _changed.emit(Unit)
    }

    /** True when the library changed: files were added, updated or removed. */
    private suspend fun scan(source: SourceEntity, refreshMetadata: Boolean): Boolean {
        _state.update { it.copy(running = true, sourceName = source.name, processed = 0, total = 0, current = null) }
        val listing = browsers(source).list(source)
        val files = listing.videos
        val sidecars = Sidecars(listing.sidecars, files)
        val listed = dao.filesOf(source.id)
        if (files.isEmpty() && listed.isNotEmpty()) throw EmptyFolderException()
        val missing = settleMissing(source.kind, listed, files)
        // Renamed files took their rows along.
        val known = (if (missing.relinked.isEmpty()) listed else dao.filesOf(source.id)).associateBy { it.filePath }

        val tmdb = client()
        val todo = files.filter { f -> needsWork(known[f.relativePath], f, sidecars.forVideo(f), tmdb != null, refreshMetadata) }
        _state.update { it.copy(total = todo.size) }
        Log.i(
            TAG,
            "${source.name}: ${files.size} files, ${missing.relinked.size} renamed, ${missing.removed.size} removed, " +
                "${missing.held.size} held, ${todo.size} to do",
        )

        val seasons = ShowIndexer.SeasonCache(tmdb)
        var unreachable = 0
        todo.forEachIndexed { index, file ->
            // Bounded, should the player never report back.
            withTimeoutOrNull(MAX_PAUSE_MS) { playing.first { !it } }
            _state.update { it.copy(processed = index, current = file.relativePath.substringAfterLast('/')) }
            suspendRunCatching {
                // One item at a time, so a choice made by the user waits for one item, not
                // for the whole scan. Read again: the item may have been edited meanwhile.
                lock.withLock {
                    // The folder was removed meanwhile: nothing more is added to it.
                    if (dao.source(source.id) == null) return@forEachIndexed
                    val existing = known[file.relativePath]?.let { dao.item(it.id) }
                    when (source.kind) {
                        SourceKind.MOVIES -> movies.process(source, file, existing, tmdb, sidecars, refreshMetadata)
                        SourceKind.SHOWS -> shows.process(source, file, existing, tmdb, seasons, sidecars, refreshMetadata)
                    }
                }
            }.onSuccess {
                unreachable = 0
            }.onFailure { e ->
                Log.w(TAG, "Failed on ${file.relativePath}", e)
                // A NAS that stops answering would fail every remaining file, each after long timeouts.
                if (ScanErrors.isUnreachable(e) && ++unreachable >= MAX_UNREACHABLE) throw e
            }
            if (index % 5 == 4) _changed.emit(Unit)
        }
        dao.markScanned(source.id, clock())
        // A file renamed is not always read again, but screens show its name.
        val changed = missing.relinked.isNotEmpty() || missing.removed.isNotEmpty() || todo.isNotEmpty()
        if (changed) _changed.emit(Unit)
        return changed
    }

    /** Rows of files the folder no longer lists go, or wait a while (see [MissingFiles]). */
    private suspend fun settleMissing(kind: SourceKind, known: List<ItemEntity>, files: List<RemoteFile>): MissingFiles.Outcome {
        val now = clock()
        val missing = MissingFiles.settle(kind, known, files, now)
        lock.withLock { dao.settleMissing(missing.relinked, missing.back, missing.held, missing.removed, now) }
        return missing
    }

    /** The show's name and year as its folder gives them, through one of its episodes. */
    suspend fun showQuery(series: ItemEntity): ParsedMovie = shows.showQuery(series)

    /**
     * A file is processed when new, not described yet or by an older probe, replaced (its size
     * changed), to refresh, or when its NFO or artwork changed.
     */
    private fun needsWork(item: ItemEntity?, file: RemoteFile, local: VideoSidecars, hasTmdb: Boolean, refreshMetadata: Boolean): Boolean =
        item == null || MediaProbe.isStale(item.mediaInfo) || item.sizeChanged(file) || refreshMetadata ||
            (hasTmdb && item.matchedAt == null) ||
            // An NFO or artwork placed next to the file, or removed, since the last scan.
            item.nfoPath != local.nfo?.relativePath ||
            (local.poster != null) != (item.posterPath.isCachedArtwork())

    companion object {
        private const val TAG = "LibraryScanner"
        private const val MAX_UNREACHABLE = 3
        private const val MAX_PAUSE_MS = 4 * 60 * 60_000L

        /** Title and year of a film, from its file name or its folder ("Title (Year)/movie.mkv"). */
        fun parseMovie(relativePath: String): ParsedMovie {
            val fromFile = FilenameParser.parseMovie(relativePath.substringAfterLast('/'))
            // The folder name is often more reliable than the file name.
            val fromFolder = relativePath.split('/').dropLast(1).lastOrNull()?.let(FilenameParser::parseMovie)
            return if (fromFile.year == null && fromFolder?.year != null) fromFolder else fromFile
        }
    }
}
