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
import java.util.concurrent.atomic.AtomicBoolean

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
     * from their files, and wait for their details. Kept after the scan ends, until TMDB is asked again
     * for them (an hour on, or at once at a scan asked for or a key changed).
     */
    val tmdbIssue: UiError? = null,
)

/**
 * A folder listed without a single video, as the NAS refused the device [count] of its subfolders: a
 * matter of rights, told as such ([ScanErrorKind.ACCESS_DENIED]), not a folder emptied. Nothing is removed.
 */
class UnreadableFolderException(count: Int) : IllegalStateException("No video listed, and $count subfolders could not be read")

/**
 * Incremental scanner: lists the files of each source, removes the missing ones (see [MissingFiles]),
 * and identifies new ones on TMDB. Files already known are never looked up again,
 * unless they were never matched (no TMDB key at the time, TMDB out of reach) or a full refresh
 * is asked. TMDB is optional ([TmdbPass]): without it, files are indexed from their NFO and
 * local artwork, and wait for their details; while it refuses the key or is out of reach, they
 * are asked for again once an hour, not at every pass, or at once when a scan is asked for.
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
    /**
     * Closes the sessions and mounts a pass kept open on the NAS: called when it ends, and before it
     * waits for a film or for the app. No default: a scan that never calls it holds them for good.
     */
    private val closeIdle: () -> Unit,
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

    /**
     * Scans every folder. [asked]: by someone (Scan now, a folder added or changed, a key saved), not by
     * the app of itself ([scanIfStale]): titles TMDB left unmatched are asked for at once, not an hour on.
     */
    fun scanAll(refreshMetadata: Boolean = false, asked: Boolean = true) {
        if (asked) askTmdbAgain.set(true)
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
        scanAll(asked = false)
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

    /**
     * The TMDB key changed (saved, or removed for the one built in): what TMDB said of the old one no
     * longer holds, so it is no longer shown, and the titles it left unmatched are asked for at once,
     * not an hour on.
     */
    fun tmdbKeyChanged() {
        tmdbDown = null
        _state.update { it.copy(tmdbIssue = null) }
        scanAll()
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

    /** What TMDB said holds until it is asked again, answers a new file, or the key changes ([tmdbKeyChanged]). */
    private fun tmdbDownAfter(tmdb: TmdbPass, down: TmdbDown?, askedUnmatched: Boolean): TmdbDown? =
        tmdb.issue?.let { TmdbDown(it, tmdb.client, elapsed()) } ?: down.takeUnless { askedUnmatched || tmdb.answered }

    /** Why TMDB was closed to a pass ([TmdbPass.issue]), with which client, and when, on [elapsed]. */
    private class TmdbDown(val cause: Throwable, val client: TmdbClient?, val at: Long)

    /** Why TMDB was closed to the last passes; null once it was asked again and did not refuse. */
    @Volatile
    private var tmdbDown: TmdbDown? = null

    /** A scan was asked for ([scanAll]): the next pass asks TMDB for what it left unmatched, however recent. */
    private val askTmdbAgain = AtomicBoolean(false)

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
        val before = _state.value.errors
        val errors = mutableMapOf<Long, ScanError>()
        var changed = false
        val client = client()
        val tmdb = TmdbPass(client) { e -> _state.update { it.copy(tmdbIssue = e.toUiError()) } }
        val seasons = ShowIndexer.SeasonCache(tmdb)
        // TMDB closed to a recent pass, with this key: titles it left unmatched wait a while, rather
        // than being read again at every pass for an answer that will not come; unless one asked.
        val down = tmdbDown?.takeIf { it.client === client }
        val asked = askTmdbAgain.getAndSet(false)
        val askUnmatched = asked || down == null || elapsed() - down.at >= UNMATCHED_RETRY_MS
        try {
            val sources = dao.sources()
            val reached = mutableSetOf<Long>()
            for (source in sources) {
                reached += source.id
                val failure = suspendRunCatching {
                    changed = scan(source, refreshMetadata, tmdb, seasons, askUnmatched) || changed
                }.exceptionOrNull()
                val error = failure?.let { errorOf(source, it) }
                error?.let { errors[source.id] = it }
                // Out of space, every other folder would fail the same way, file after file.
                if (error?.kind == ScanErrorKind.STORAGE_FULL) break
            }
            // Folders the pass stopped before keep what their last scan said: one off stays off.
            errors += before.filterKeys { id -> id !in reached && sources.any { it.id == id } }
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
            tmdbDown = tmdbDownAfter(tmdb, down, askUnmatched)
            // Whatever happened, the scan is over: the settings must not show it running forever.
            _state.value = ScanState(errors = errors, tmdbIssue = tmdbDown?.takeIf { it.client === client() }?.cause?.toUiError())
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

    /**
     * True when the library changed: files were added, updated or removed. [askUnmatched]: titles
     * waiting for TMDB alone are asked for (see [tmdbDown]).
     */
    private suspend fun scan(
        source: SourceEntity,
        refreshMetadata: Boolean,
        tmdb: TmdbPass,
        seasons: ShowIndexer.SeasonCache,
        askUnmatched: Boolean,
    ): Boolean {
        waitForThePlayer()
        _state.update { it.copy(running = true, sourceId = source.id, processed = 0, total = 0, current = null) }
        val found = browsers(source).list(source)
        // Not a video read, and subfolders the NAS refused: their rights are what to check, whatever the library holds.
        if (found.videos.isEmpty() && found.unreadable.isNotEmpty()) throw UnreadableFolderException(found.unreadable.size)
        // A disc's streams make one title, extras none: rows of what is not one go as files gone do.
        val listing = ScanTitles.of(found)
        // A film in several files (CD1, CD2…) is one title, under its first part.
        val stacks = Stacks.of(source.kind, listing.videos)
        val files = stacks.titles
        val sidecars = Sidecars(listing.sidecars, files, stacks)
        val listed = dao.filesOf(source.id)
        if (found.videos.isEmpty() && listed.isNotEmpty()) throw EmptyFolderException()
        val missing = settleMissing(source.kind, listed, listing, stacks)
        // Renamed files took their rows along, and films their parts (one of a file now of several is asked for again).
        val rewritten = missing.relinked.isNotEmpty() || missing.parts.isNotEmpty()
        val known = (if (rewritten) dao.filesOf(source.id) else listed).associateBy { it.filePath }

        // TMDB closed to the pass, now or since a recent one: a title only waiting for it is left as it is.
        fun wanted(f: RemoteFile): Boolean {
            val beside = SidecarKey.listed(source.kind, sidecars.forVideo(f))
            return needsWork(known[f.relativePath], f, beside, tmdb.open && askUnmatched, refreshMetadata)
        }
        val own = files.filter(::wanted)
        // A show's own sidecars changed, none of its episodes read for themselves: one of them reads them.
        val forShows = showsToRead(source, files, own, known, sidecars)
        val todo = own + forShows.keys
        _state.update { it.copy(total = todo.size) }
        Log.i(
            TAG,
            "${source.name}: ${files.size} files, ${missing.relinked.size} renamed, ${missing.removed.size} removed, " +
                "${missing.held.size} held, ${todo.size} to do",
        )

        if (source.kind == SourceKind.SHOWS) tellNoEpisode(source, files)

        val folder = Folder(source, sidecars, known, refreshMetadata, tmdb, seasons, stacks)
        val written = processAll(folder, todo, stillWanted(::wanted, forShows))
        dao.markScanned(source.id, clock())
        // A file renamed is not always read again, but screens show its name; and a film's parts.
        val changed = missing.relinked.isNotEmpty() || missing.removed.isNotEmpty() || missing.parts.isNotEmpty() || written > 0
        if (changed) _changed.emit(Unit)
        return changed
    }

    /**
     * Processes [todo], each file as long as it is still [wanted] (TMDB may close meanwhile, before its
     * NFO is read), and returns how many changed the library. Screens learn of them a few at a time.
     */
    private suspend fun processAll(folder: Folder, todo: List<RemoteFile>, wanted: (RemoteFile) -> Boolean): Int {
        var unreachable = 0
        var written = 0
        var told = 0
        todo.forEachIndexed { index, file ->
            if (!wanted(file)) return@forEachIndexed
            waitForThePlayer()
            _state.update { it.copy(processed = index, current = file.relativePath.substringAfterLast('/')) }
            suspendRunCatching { process(folder, file) }.onSuccess { wrote ->
                unreachable = 0
                if (wrote) written++
            }.onFailure { e ->
                Log.w(TAG, "Failed on ${file.relativePath}", e)
                // A new file may have got its row before it failed.
                if (folder.known[file.relativePath] == null) written++
                if (ScanErrors.isUnreachable(e)) unreachable++
                // The device out of space, or a NAS that stopped answering: every remaining file would fail
                // the same way, each after long timeouts.
                if (outOfSpace(e) || unreachable >= MAX_UNREACHABLE) throw e
            }
            if (index % TELL_EVERY == TELL_EVERY - 1 && written > told) {
                told = written
                _changed.emit(Unit)
            }
        }
        return written
    }

    /**
     * In a shows' folder, one known episode for each show folder whose show's own sidecars (tvshow.nfo,
     * its poster…) are due ([SidecarKey.showDue]), unless [todo] reads one of its episodes there: by show,
     * as a folder without one ("Show.S01E01.mkv" at the root) holds several. Each with its show's row id.
     */
    private suspend fun showsToRead(
        source: SourceEntity,
        files: List<RemoteFile>,
        todo: List<RemoteFile>,
        known: Map<String?, ItemEntity>,
        sidecars: Sidecars,
    ): Map<RemoteFile, Long> {
        if (source.kind != SourceKind.SHOWS) return emptyMap()
        val reading = todo.toSet()
        // Known episodes by show folder and show; the show's folder parsed once for each folder they lie in.
        val byFolder = files.groupBy { f -> known[f.relativePath]?.seriesId?.let { Sidecars.folderOf(f.relativePath) to it } }
            .mapNotNull { (key, episodes) ->
                val seriesId = key?.second ?: return@mapNotNull null
                FilenameParser.parseShowPath(episodes.first().relativePath)?.let { (it.dir to seriesId) to episodes }
            }
            .groupBy({ it.first }, { it.second }).mapValues { (_, lists) -> lists.flatten() }
        val shows = byFolder.keys.map { it.second }.distinct().chunked(SQL_IN_LIMIT).flatMap { dao.rows(it) }.associateBy { it.id }
        return byFolder.entries.mapNotNull { (key, episodes) ->
            val (dir, seriesId) = key
            val show = shows[seriesId] ?: return@mapNotNull null
            val due = episodes.none { it in reading } && SidecarKey.showDue(show.sidecarKey, source.id, dir, sidecars)
            if (due) episodes.first() to seriesId else null
        }.toMap()
    }

    /** Files [wanted] still, and those read for their show ([showsToRead]) unless another episode read it meanwhile. */
    private fun stillWanted(wanted: (RemoteFile) -> Boolean, forShows: Map<RemoteFile, Long>): (RemoteFile) -> Boolean =
        { file -> wanted(file) || forShows[file]?.let(shows::readThisPass) == false }

    /**
     * Videos of a shows' folder that name no episode get no row, and nothing else tells of them: the
     * log does, once a pass, for whoever wonders where an episode went.
     */
    private fun tellNoEpisode(source: SourceEntity, files: List<RemoteFile>) {
        val refused = files.count { FilenameParser.parseShowPath(it.relativePath) == null }
        if (refused > 0) Log.i(TAG, "${source.name}: $refused of ${files.size} videos name no episode, and are left out")
    }

    /** Why [source] could not be scanned, as the settings tell it; the failure itself goes to the log. */
    private fun errorOf(source: SourceEntity, failure: Throwable): ScanError {
        Log.w(TAG, "Scan of ${source.name} failed", failure)
        return if (outOfSpace(failure)) ScanError(ScanErrorKind.STORAGE_FULL) else ScanErrors.classify(failure)
    }

    /** A folder as one pass reads it: what each of its files is processed with. */
    @Suppress("LongParameterList") // What a pass hands each file.
    private class Folder(
        val source: SourceEntity,
        val sidecars: Sidecars,
        /** Its files the library knows, by path. */
        val known: Map<String?, ItemEntity>,
        val refreshMetadata: Boolean,
        val tmdb: TmdbPass,
        val seasons: ShowIndexer.SeasonCache,
        /** Its films in several files. */
        val stacks: Stacks,
    )

    /**
     * One file, under the lock: a choice made by the user waits for one item, not for the whole scan.
     * Its row is read again, as it may have been edited meanwhile. True when it changed the library:
     * a row added, or rows written with other values than they held ([shown]). A file read again to no
     * avail (a lock, a refusal, an image the device has no room for) changes nothing.
     */
    private suspend fun process(folder: Folder, file: RemoteFile): Boolean = lock.withLock {
        val source = folder.source
        // The folder was removed meanwhile: nothing more is added to it.
        if (dao.source(source.id) == null) return@withLock false
        val existing = folder.known[file.relativePath]?.let { dao.item(it.id) }
        val before = existing?.let { shown(it) }
        when (source.kind) {
            SourceKind.MOVIES -> movies.process(
                source, file, existing, folder.tmdb, folder.sidecars, folder.refreshMetadata, folder.stacks.stackAt(file.relativePath),
            )
            SourceKind.SHOWS -> shows.process(source, file, existing, folder.tmdb, folder.seasons, folder.sidecars, folder.refreshMetadata)
        }
        if (existing == null) dao.hasFile(source.id, file.relativePath) else dao.item(existing.id)?.let { shown(it) } != before
    }

    /**
     * What screens show of a file's row, and of its season's and its show's: matched or not, whenever
     * it was. A title TMDB answered again with the same details is the same title; sidecars read again
     * to no change ([ItemEntity.sidecarKey]) change nothing shown.
     */
    private suspend fun shown(item: ItemEntity): List<ItemEntity> =
        (listOf(item) + dao.rows(listOfNotNull(item.parentId, item.seriesId)).sortedBy { it.id })
            .map { row -> row.copy(matchedAt = row.matchedAt?.let { MATCHED }, sidecarKey = null) }

    /**
     * The device ran out of space: said by [e], or by the space left. SQLite rolls a transaction back
     * by itself when the disk is full, and the rollback Room then asks for fails in turn
     * ("cannot rollback - no transaction is active"), so that [e] may no longer say it.
     */
    private fun outOfSpace(e: Throwable): Boolean =
        ScanErrors.classify(e).kind == ScanErrorKind.STORAGE_FULL || artwork.spaceLeft() < NEARLY_FULL_BYTES

    /**
     * Waits while a player may stream from the NAS: while a film plays (bounded, should the player
     * never report back), then while the app is away. Before each folder and each file. A wait may
     * last hours: the pass's sessions on the NAS are closed first, and opened again after.
     */
    private suspend fun waitForThePlayer() {
        if (!playing.value && started.first()) return
        closeIdle()
        withTimeoutOrNull(MAX_PAUSE_MS) { playing.first { !it } }
        started.first { it }
    }

    /** Rows of files the folder no longer lists go, or wait a while (see [MissingFiles]). */
    private suspend fun settleMissing(kind: SourceKind, known: List<ItemEntity>, listing: Listing, stacks: Stacks): MissingFiles.Outcome {
        val now = clock()
        val missing = MissingFiles.settle(kind, known, listing, now, stacks)
        lock.withLock { dao.settleMissing(missing.relinked, missing.back, missing.held, missing.removed, missing.parts, now) }
        return missing
    }

    /** The show's name and year as its folder gives them, through one of its episodes. */
    suspend fun showQuery(series: ItemEntity): ParsedMovie = shows.showQuery(series)

    /**
     * A file is processed when new, not described yet or by an older probe, replaced (its size
     * changed), to refresh, when its NFO or artwork changed ([sidecars], what the folder lists beside
     * it: see [SidecarKey]), or unmatched while TMDB is [askTmdb].
     */
    private fun needsWork(item: ItemEntity?, file: RemoteFile, sidecars: String?, askTmdb: Boolean, refreshMetadata: Boolean): Boolean =
        item == null || MediaProbe.isStale(item.mediaInfo) || item.sizeChanged(file) || refreshMetadata ||
            (askTmdb && item.matchedAt == null) ||
            // An NFO or artwork placed next to the file, rewritten in place or removed, since it was last read.
            item.sidecarKey != sidecars

    companion object {
        private const val TAG = "LibraryScanner"
        private const val MAX_UNREACHABLE = 3

        /** Files processed between two notices to screens, while some changed. */
        private const val TELL_EVERY = 5

        /** What [shown] keeps of when a title was matched: that it was. */
        private const val MATCHED = 0L

        /** How long titles TMDB left unmatched wait before it is asked for them again, while it refuses or is out of reach. */
        private const val UNMATCHED_RETRY_MS = 60 * 60_000L

        /** Space left under which a failure is the device's: too little for the database to grow. */
        private const val NEARLY_FULL_BYTES = 50L shl 20
        private const val MAX_PAUSE_MS = 4 * 60 * 60_000L

        /**
         * Title and year of a film, from its file name or its folder ("Title (Year)/movie.mkv"). A disc
         * is named by its folder, as a file by its name: "Title (Year)/BDMV/STREAM/00800.m2ts".
         */
        fun parseMovie(relativePath: String): ParsedMovie {
            val path = FilenameParser.discOf(relativePath)?.root?.ifEmpty { null } ?: relativePath
            val fromFile = FilenameParser.parseMovie(path.substringAfterLast('/'))
            // The folder name is often more reliable than the file name.
            val fromFolder = path.split('/').dropLast(1).lastOrNull()?.let(FilenameParser::parseMovie)
            return if (fromFile.year == null && fromFolder?.year != null) fromFolder else fromFile
        }
    }
}
