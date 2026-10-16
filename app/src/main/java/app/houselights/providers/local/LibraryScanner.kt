package app.houselights.providers.local

import android.os.SystemClock
import android.util.Log
import app.houselights.core.NoTmdbKeyException
import app.houselights.core.suspendRunCatching
import app.houselights.ui.util.UiError
import app.houselights.ui.util.toUiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ScanState(
    val running: Boolean = false,
    /** The folder being read ([SourceEntity.id]): two folders may bear the same name. */
    val sourceId: Long? = null,
    val processed: Int = 0,
    val total: Int = 0,
    val current: String? = null,
    /** Last error per folder ([SourceEntity.id]), kept after the scan ends. */
    val errors: Map<Long, ScanError> = emptyMap(),
    /**
     * Why the last scan stopped asking TMDB (the key refused, TMDB out of reach): titles were indexed
     * from their files, and wait for their details. Kept after the scan ends.
     */
    val tmdbIssue: UiError? = null,
)

/**
 * Incremental scanner: lists the files of each source, removes the missing ones (see [MissingFiles]),
 * and identifies new ones on TMDB. Files already known are never looked up again,
 * unless they were never matched (no TMDB key at the time, TMDB out of reach) or a full refresh
 * is asked. TMDB is optional ([TmdbPass]): without it, files are indexed from their NFO and
 * local artwork, and wait for their details.
 *
 * Choices made by the user (match, artwork) and their viewing state are never overwritten:
 * every write goes through [LibraryDao.saveScanned].
 */
class LibraryScanner(
    private val dao: LibraryDao,
    /** The TMDB client of scans, or null without a key. */
    private val tmdb: () -> TmdbClient?,
    private val scope: CoroutineScope,
    private val artwork: ArtworkCache,
    /** True while a film plays: the scan waits, so as not to read the NAS while the player streams from it. */
    private val playing: StateFlow<Boolean> = MutableStateFlow(false),
    /** How a folder is read; tests hand in a NAS of their own. */
    private val browsers: (SourceEntity) -> RemoteBrowser,
    /** Closes the sessions and mounts a pass kept open; called when it ends. */
    private val closeIdle: () -> Unit = {},
    /** The date, for what the library keeps: when a folder was scanned, a file first missed. */
    private val clock: () -> Long = System::currentTimeMillis,
    /** The TMDB client of what people ask ([identify]): one that asks TMDB every time. */
    private val interactiveTmdb: () -> TmdbClient? = tmdb,
    /** The clock that only moves forward, for how long ago the last scan ended ([scanIfStale]). */
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    /**
     * True while the app is on screen. Away, the scan waits: a film may play from the NAS in another
     * app, which the scan would compete with on the network and on the NAS's disks.
     */
    private val started: Flow<Boolean> = flowOf(true),
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
            var ended = false
            try {
                while (!ended) {
                    val refresh = synchronized(passes) { passes.next() }
                    if (refresh == null) ended = true else scanAllSources(refresh)
                }
            } finally {
                // Ended early (cancelled, failed): the next scan asked starts again. Ended by next(),
                // which cleared the flag, the loop leaves it alone: a scan asked since may already
                // have started another loop, whose flag is not this one's to clear.
                if (!ended) synchronized(passes) { passes.running = false }
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
        val end = lastScanEnd
        if (end != null && elapsed() - end < maxAgeMs) return
        scanAll()
    }

    /**
     * When the last scan ended, on [elapsed]: the date set back (by hand, or by the network once the
     * player started ahead) would hold scans for as long. Null until one ended.
     */
    @Volatile
    private var lastScanEnd: Long? = null

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
     * matching when [tmdbId] is null. Seasons and episodes of a show follow. Everything is asked to
     * TMDB first: when it cannot answer, this throws and the item stays as it was. Then everything is
     * written in one transaction, which leaving the screen does not cut short.
     */
    suspend fun identify(itemId: Long, tmdbId: Int?) {
        withContext(Dispatchers.IO) {
            val tmdb = interactiveTmdb() ?: throw NoTmdbKeyException()
            lock.withLock {
                val item = dao.item(itemId) ?: return@withLock
                val writes = when (item.kind) {
                    ItemKind.MOVIE -> movies.identify(item, tmdbId, tmdb)
                    ItemKind.SERIES -> shows.identify(item, tmdbId, tmdb)
                    else -> error("Only films and shows can be identified")
                }
                withContext(NonCancellable) {
                    dao.inTransaction {
                        writes()
                        // The film may have left its collection for another one.
                        dao.deleteEmptyCollections()
                    }
                    _changed.tryEmit(Unit)
                }
            }
        }
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
        val errors = mutableMapOf<Long, ScanError>()
        var changed = false
        val tmdb = TmdbPass(client()) { e -> _state.update { it.copy(tmdbIssue = e.toUiError()) } }
        val seasons = ShowIndexer.SeasonCache(tmdb)
        try {
            for (source in dao.sources()) {
                val failure = suspendRunCatching { changed = scan(source, refreshMetadata, tmdb, seasons) || changed }.exceptionOrNull()
                val error = failure?.let { errorOf(source, it) }
                error?.let { errors[source.id] = it }
                // Out of space, every other folder would fail the same way, file after file.
                if (error?.kind == ScanErrorKind.STORAGE_FULL) break
            }
            lock.withLock {
                suspendRunCatching {
                    // Rows a folder removed during the scan may have received meanwhile.
                    dao.deleteOrphanItems()
                    dao.deleteEmptySeasons()
                    dao.deleteEmptySeries()
                    dao.moveOrphanShows()
                    dao.deleteEmptyCollections()
                    // Pictures only become unused when titles change or go.
                    if (changed) cleanupLocked()
                }.onFailure { Log.w(TAG, "Cleanup failed", it) }
            }
        } finally {
            // Whatever happened, the scan is over: the settings must not show it running forever.
            _state.value = ScanState(errors = errors, tmdbIssue = tmdb.issue?.toUiError())
            lastScanEnd = elapsed()
            closeIdle()
        }
        // A scan that found nothing new leaves screens as they are.
        if (changed) _changed.emit(Unit)
    }

    /**
     * Removes a folder and everything found in it, its copied artwork included (what a show it shares
     * with another folder still shows stays). Waits for the item being processed; a scan of that
     * folder stops at its next file.
     */
    suspend fun removeSource(source: SourceEntity) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                dao.removeSource(source.id)
                suspendRunCatching { cleanupLocked() }.onFailure { Log.w(TAG, "Cleanup after removing ${source.name} failed", it) }
            }
        }
        _changed.emit(Unit)
    }

    /** True when the library changed: files were added, updated or removed. */
    private suspend fun scan(source: SourceEntity, refreshMetadata: Boolean, tmdb: TmdbPass, seasons: ShowIndexer.SeasonCache): Boolean {
        waitForThePlayer()
        _state.update { it.copy(running = true, sourceId = source.id, processed = 0, total = 0, current = null) }
        val listing = browsers(source).list(source)
        val files = listing.videos
        val sidecars = Sidecars(listing.sidecars, files)
        val listed = dao.filesOf(source.id)
        if (files.isEmpty() && listed.isNotEmpty()) throw EmptyFolderException()
        val missing = settleMissing(source.kind, listed, listing)
        // Renamed files took their rows along.
        val known = (if (missing.relinked.isEmpty()) listed else dao.filesOf(source.id)).associateBy { it.filePath }

        val todo = files.filter { f -> needsWork(known[f.relativePath], f, sidecars.forVideo(f), tmdb.client != null, refreshMetadata) }
        _state.update { it.copy(total = todo.size) }
        Log.i(
            TAG,
            "${source.name}: ${files.size} files, ${missing.relinked.size} renamed, ${missing.removed.size} removed, " +
                "${missing.held.size} held, ${todo.size} to do",
        )

        val folder = Folder(source, sidecars, known, refreshMetadata, tmdb, seasons)
        var unreachable = 0
        todo.forEachIndexed { index, file ->
            waitForThePlayer()
            _state.update { it.copy(processed = index, current = file.relativePath.substringAfterLast('/')) }
            suspendRunCatching { process(folder, file) }.onSuccess {
                unreachable = 0
            }.onFailure { e ->
                Log.w(TAG, "Failed on ${file.relativePath}", e)
                if (ScanErrors.isUnreachable(e)) unreachable++
                // The device out of space, or a NAS that stopped answering: every remaining file would fail
                // the same way, each after long timeouts.
                if (outOfSpace(e) || unreachable >= MAX_UNREACHABLE) throw e
            }
            if (index % 5 == 4) _changed.emit(Unit)
        }
        dao.markScanned(source.id, clock())
        // A file renamed is not always read again, but screens show its name.
        val changed = missing.relinked.isNotEmpty() || missing.removed.isNotEmpty() || todo.isNotEmpty()
        if (changed) _changed.emit(Unit)
        return changed
    }

    /** Why [source] could not be scanned, as the settings tell it; the failure itself goes to the log. */
    private fun errorOf(source: SourceEntity, failure: Throwable): ScanError {
        Log.w(TAG, "Scan of ${source.name} failed", failure)
        return if (outOfSpace(failure)) ScanError(ScanErrorKind.STORAGE_FULL) else ScanErrors.classify(failure)
    }

    /** A folder as one pass reads it: what each of its files is processed with. */
    private class Folder(
        val source: SourceEntity,
        val sidecars: Sidecars,
        /** Its files the library knows, by path. */
        val known: Map<String?, ItemEntity>,
        val refreshMetadata: Boolean,
        val tmdb: TmdbPass,
        val seasons: ShowIndexer.SeasonCache,
    )

    /**
     * One file, under the lock: a choice made by the user waits for one item, not for the whole scan.
     * Its row is read again, as it may have been edited meanwhile.
     */
    private suspend fun process(folder: Folder, file: RemoteFile) = lock.withLock {
        val source = folder.source
        // The folder was removed meanwhile: nothing more is added to it.
        if (dao.source(source.id) == null) return@withLock
        val existing = folder.known[file.relativePath]?.let { dao.item(it.id) }
        when (source.kind) {
            SourceKind.MOVIES -> movies.process(source, file, existing, folder.tmdb, folder.sidecars, folder.refreshMetadata)
            SourceKind.SHOWS -> shows.process(source, file, existing, folder.tmdb, folder.seasons, folder.sidecars, folder.refreshMetadata)
        }
    }

    /**
     * The device ran out of space: said by [e], or by the space left. SQLite rolls a transaction back
     * by itself when the disk is full, and the rollback Room then asks for fails in turn
     * ("cannot rollback - no transaction is active"), so that [e] may no longer say it.
     */
    private fun outOfSpace(e: Throwable): Boolean =
        ScanErrors.classify(e).kind == ScanErrorKind.STORAGE_FULL || artwork.spaceLeft() < NEARLY_FULL_BYTES

    /**
     * Waits while a player may stream from the NAS: while a film plays (bounded, should the player
     * never report back), then while the app is away. Before each folder and each file.
     */
    private suspend fun waitForThePlayer() {
        withTimeoutOrNull(MAX_PAUSE_MS) { playing.first { !it } }
        started.first { it }
    }

    /** Rows of files the folder no longer lists go, or wait a while (see [MissingFiles]). */
    private suspend fun settleMissing(kind: SourceKind, known: List<ItemEntity>, listing: Listing): MissingFiles.Outcome {
        val now = clock()
        val missing = MissingFiles.settle(kind, known, listing, now)
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

        /** Space left under which a failure is the device's: too little for the database to grow. */
        private const val NEARLY_FULL_BYTES = 50L shl 20
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
