package app.houselights.providers.local

import android.os.SystemClock
import android.util.Log
import app.houselights.core.FailureKind
import app.houselights.core.NfoParser
import app.houselights.core.NoTmdbKeyException
import app.houselights.core.failureKind
import app.houselights.core.suspendRunCatching
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
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class ScanState(
    val running: Boolean = false,
    /** Id of the source being scanned, as two sources may share a name. */
    val sourceId: Long? = null,
    val processed: Int = 0,
    val total: Int = 0,
    val current: String? = null,
    /** Last error per source id, kept after the scan ends. */
    val errors: Map<Long, ScanError> = emptyMap(),
    /** Why the last scan stopped querying TMDB; kept until TMDB is queried again. */
    val tmdbIssue: FailureKind? = null,
)

/** No video while [count] subfolders were denied: a permissions problem, so nothing is removed. */
class UnreadableFolderException(count: Int) : IllegalStateException("No video listed, and $count subfolders could not be read")

/**
 * Incremental scanner: lists sources, handles missing files ([MissingFiles]) and matches new ones on TMDB, which is
 * optional ([TmdbPass]); unmatched titles are retried hourly, or at once on a requested scan. Writes go through
 * [LibraryDao.saveScanned], so user choices and viewing state are never overwritten.
 */
class LibraryScanner(
    private val dao: LibraryDao,
    private val tmdb: () -> TmdbClient?,
    private val scope: CoroutineScope,
    private val artwork: ArtworkCache,
    /** True during playback; the scan pauses so it does not compete with the player for the NAS. */
    private val playing: StateFlow<Boolean> = MutableStateFlow(false),
    private val browsers: (SourceEntity) -> RemoteBrowser,
    /** Closes the pass's NAS sessions and mounts, at its end and before each wait; no default, to avoid leaks. */
    private val closeIdle: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Client for [identify], which never pauses. */
    private val interactiveTmdb: () -> TmdbClient? = tmdb,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    /** Foreground state; in the background the scan waits, as another app may be playing from the NAS. */
    private val started: Flow<Boolean> = flowOf(true),
    /** Told the language of a refresh that read every source again: all details are now in it. */
    private val refreshedIn: suspend (Locale) -> Unit = {},
) {
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state

    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits while scanning, so screens show new entries progressively. */
    val changed: SharedFlow<Unit> = _changed

    private val _passEnded = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)

    /** Emits at the end of each pass, true when it changed the library. */
    val passEnded: SharedFlow<Boolean> = _passEnded

    private val lock = Mutex()

    private val files = ScanFiles(dao, artwork, browsers)
    private val movies = MovieIndexer(dao, files)
    private val shows = ShowIndexer(dao, files)
    private val backfill = Backfill(dao, lock, System::currentTimeMillis) { waitForThePlayer() }

    /** Scans every source; [asked] (user-initiated, unlike [scanIfStale]) retries unmatched titles on TMDB at once. */
    fun scanAll(refreshMetadata: Boolean = false, asked: Boolean = true) {
        if (asked) askTmdbAgain.set(true)
        synchronized(passes) {
            // A request during a scan queues one more pass rather than being lost.
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
                // Only when ended early (cancelled, failed): otherwise next() cleared it, and a newer loop may own it.
                if (!ended) synchronized(passes) { passes.running = false }
            }
        }
    }

    /** Pass requests: one running, at most one more waiting. Guarded by its own monitor. */
    private class Passes {
        var running = false
        private var waiting = false
        private var refresh = false

        fun ask(refreshMetadata: Boolean) {
            waiting = true
            refresh = refresh || refreshMetadata
        }

        /** The next pass (true to refresh metadata), or null when none is waiting, which ends the loop. */
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

    /** Polls for new files, as a NAS cannot notify the app: scans unless one is running or ended within [maxAgeMs]. */
    fun scanIfStale(maxAgeMs: Long) {
        if (synchronized(passes) { passes.running }) return
        val end = lastScanEnd
        if (end != null && elapsed() - end < maxAgeMs) return
        scanAll(asked = false)
    }

    /** End of the last scan on the monotonic [elapsed] clock, so a wall clock set back cannot hold scans off. */
    @Volatile
    private var lastScanEnd: Long? = null

    /** Removes unreferenced cached artwork, credits and search words; never while an item is being processed. */
    suspend fun cleanup(): CleanupResult = withContext(Dispatchers.IO) {
        lock.withLock { cleanupLocked() }
    }

    /** Clears the old key's issue and rescans, retrying unmatched titles at once. */
    fun tmdbKeyChanged() {
        tmdbDown = null
        _state.update { it.copy(tmdbIssue = null) }
        scanAll()
    }

    /** Tells screens the library changed outside a scan (a user action). */
    fun notifyChanged() {
        _changed.tryEmit(Unit)
    }

    /**
     * Matches a film or show with the user's TMDB choice, or automatic matching when [tmdbId] is null. TMDB requests
     * run first, so a failure leaves the item unchanged; writes then run in one non-cancellable transaction.
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
                        // The film may have changed collection, and its copies may now belong to another film.
                        dao.deleteEmptyCollections()
                        dao.groupVersions()
                    }
                    _changed.tryEmit(Unit)
                }
            }
        }
    }

    private fun client(): TmdbClient? = tmdb()

    /** TMDB's failure state persists until a retry, an answer for a new file, or a key change ([tmdbKeyChanged]). */
    private fun tmdbDownAfter(tmdb: TmdbPass, down: TmdbDown?, askedUnmatched: Boolean): TmdbDown? =
        tmdb.issue?.let { TmdbDown(it, tmdb.client, elapsed()) } ?: down.takeUnless { askedUnmatched || tmdb.answered }

    private class TmdbDown(val cause: Throwable, val client: TmdbClient?, val at: Long)

    /** Why TMDB was closed to recent passes; null once a retry was not refused. */
    @Volatile
    private var tmdbDown: TmdbDown? = null

    /** Set by a requested scan: the next pass retries unmatched titles regardless of [tmdbDown]. */
    private val askTmdbAgain = AtomicBoolean(false)

    private suspend fun cleanupLocked(): CleanupResult {
        val orphanCast = dao.deleteOrphanCredits()
        dao.deleteOrphanSearchWords()
        // Based on library references, not on the NAS listing, so an unreachable NAS never deletes artwork.
        val result = artwork.sweep(dao.cachedArtworkPaths())
        if (result.deletedFiles > 0 || orphanCast > 0) {
            Log.i(TAG, "Cleanup: ${result.deletedFiles} images (${result.freedBytes / 1024} KB), $orphanCast cast rows")
        }
        return result
    }

    private suspend fun scanAllSources(refreshMetadata: Boolean) {
        // Under the lock, as an identification reads and fills the indexers' per-pass state.
        lock.withLock {
            movies.startPass()
            shows.startPass()
        }
        val before = _state.value.errors
        val errors = mutableMapOf<Long, ScanError>()
        var changed = false
        val client = client()
        val tmdb = TmdbPass(client) { e -> _state.update { it.copy(tmdbIssue = e.failureKind()) } }
        val seasons = ShowIndexer.SeasonCache(tmdb)
        // After a recent TMDB failure with this key, unmatched titles wait an hour unless the scan was requested.
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
                // Out of space, every other source would fail the same way, file after file.
                if (error?.kind == ScanErrorKind.STORAGE_FULL) break
            }
            // Sources the pass did not reach keep their last error.
            errors += before.filterKeys { id -> id !in reached && sources.any { it.id == id } }
            lock.withLock {
                suspendRunCatching {
                    // Rows added meanwhile under a source removed during the scan.
                    dao.deleteOrphanItems()
                    dao.deleteEmptySeasons()
                    dao.deleteEmptySeries()
                    dao.moveOrphanShows()
                    dao.deleteEmptyCollections()
                    if (dao.groupVersions() > 0) changed = true
                    // Artwork only becomes unused when titles change or go.
                    if (changed) cleanupLocked()
                }.onFailure { Log.w(TAG, "Cleanup failed", it) }
            }
            backfill(tmdb, askTmdb = askUnmatched)
        } finally {
            tmdbDown = tmdbDownAfter(tmdb, down, askUnmatched)
            // Always reset the state, so the settings never show a scan running forever.
            _state.value = ScanState(errors = errors, tmdbIssue = tmdbDown?.takeIf { it.client === client() }?.cause?.failureKind())
            lastScanEnd = elapsed()
            closeIdle()
        }
        // No change, no refresh of the screens.
        if (changed) _changed.emit(Unit)
        _passEnded.tryEmit(changed)
        if (refreshMetadata) refreshed(errors, client)
    }

    /** A refresh that read every source with TMDB leaves every title in [client]'s language. */
    private suspend fun refreshed(errors: Map<Long, ScanError>, client: TmdbClient?) {
        if (errors.isEmpty() && client != null) refreshedIn(client.locale)
    }

    /** Runs the [Backfill] after the sources; a failure is retried at the next pass. */
    private suspend fun backfill(tmdb: TmdbPass, askTmdb: Boolean) {
        suspendRunCatching {
            backfill.run(tmdb, askTmdb).also { written ->
                // Rekeyed people leave search words under their former keys.
                if (written > 0) lock.withLock { dao.deleteOrphanSearchWords() }
            }
        }
            .onSuccess { if (it > 0) Log.i(TAG, "Backfill: $it titles and sagas given their credits and films") }
            .onFailure { Log.w(TAG, "Backfill failed; tried again at the next pass", it) }
    }

    /** Removes a source, its items and their unreferenced artwork; a running scan of it stops at its next file. */
    suspend fun removeSource(source: SourceEntity) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                dao.removeSource(source.id)
                suspendRunCatching { cleanupLocked() }.onFailure { Log.w(TAG, "Cleanup after removing ${source.name} failed", it) }
            }
        }
        _changed.emit(Unit)
    }

    /** Scans one source; true when the library changed. [askUnmatched] retries titles only waiting for TMDB. */
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
        // No video and denied subfolders: report permissions, whatever the library holds.
        if (found.videos.isEmpty() && found.unreadable.isNotEmpty()) throw UnreadableFolderException(found.unreadable.size)
        // A disc's streams form one title and extras none; rows of non-titles are handled as missing files.
        val listing = ScanTitles.of(found)
        val listed = dao.knownFiles(source.id)
        val stacks = stacksOf(source.kind, listing.videos, listed)
        val files = stacks.titles
        val sidecars = Sidecars(listing.sidecars, files, stacks)
        if (found.videos.isEmpty() && listed.isNotEmpty()) throw EmptyFolderException()
        val missing = settleMissing(source.kind, listed, listing, stacks)
        // Renamed files moved their rows, and films their parts, so reload the known files.
        val rewritten = missing.relinked.isNotEmpty() || missing.parts.isNotEmpty()
        val known = (if (rewritten) dao.knownFiles(source.id) else listed).associateBy { it.filePath }
        // Parts of a film whose first part is missing wait with its row instead of becoming titles.
        val waiting = MissingFiles.waitingParts(known.values, files, missing.removed)

        // While TMDB is closed (now or since a recent pass), titles only waiting for it are left alone.
        fun work(f: RemoteFile): Work? {
            val beside = SidecarKey.listed(source.kind, sidecars.forVideo(f))
            return workOn(known[f.relativePath], f, beside, tmdb.open && askUnmatched, refreshMetadata)
        }
        val own = files.filterNot { it.relativePath in waiting }.associateWith { work(it) }.filterValues { it != null }
        // When a show's own sidecars changed and none of its episodes is read in full, one episode reads them.
        val forShows = showsToRead(source, files, own.filterValues { it == Work.FULL }.keys, known, sidecars)
        val todo = own.keys.toList() + forShows.keys.filterNot { it in own }
        _state.update { it.copy(total = todo.size) }
        Log.i(
            TAG,
            "${source.name}: ${files.size} files, ${missing.relinked.size} renamed, ${missing.removed.size} removed, " +
                "${missing.held.size} held, ${todo.size} to do",
        )

        if (source.kind == SourceKind.SHOWS) tellNoEpisode(source, files)

        val folder = Folder(ScanPass(source, sidecars, stacks, tmdb, seasons, refreshMetadata), known)
        val written = processAll(folder, todo, stillDue(::work, forShows))
        dao.markScanned(source.id, clock())
        // Renames and part changes count even without a re-read, as screens show file names and parts.
        val changed = missing.relinked.isNotEmpty() || missing.removed.isNotEmpty() || missing.parts.isNotEmpty() || written > 0
        if (changed) _changed.emit(Unit)
        return changed
    }

    /** Stacks of [videos], except those whose rows were matched with different films ([MissingFiles.split]). */
    private fun stacksOf(kind: SourceKind, videos: List<RemoteFile>, listed: List<KnownFile>): Stacks {
        val all = Stacks.of(kind, videos)
        val apart = MissingFiles.split(listed, all)
        return if (apart.isEmpty()) all else Stacks.of(kind, videos, apart)
    }

    /** Re-evaluates [due] per file, as TMDB may close meanwhile; returns how many files changed the library. */
    private suspend fun processAll(folder: Folder, todo: List<RemoteFile>, due: (RemoteFile) -> Work?): Int {
        var unreachable = 0
        var written = 0
        var told = 0
        todo.forEachIndexed { index, file ->
            val work = due(file) ?: return@forEachIndexed
            waitForThePlayer()
            _state.update { it.copy(processed = index, current = file.relativePath.substringAfterLast('/')) }
            suspendRunCatching { process(folder, file, work) }.onSuccess { wrote ->
                unreachable = 0
                if (wrote) written++
            }.onFailure { e ->
                Log.w(TAG, "Failed on ${file.relativePath}", e)
                // A new file may have been inserted before failing.
                if (folder.known[file.relativePath] == null) written++
                if (ScanErrors.isUnreachable(e)) unreachable++
                // Device full or NAS unreachable: every remaining file would fail the same way, after long timeouts.
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
     * One known episode (with its show id) per show folder and show whose sidecars are due ([SidecarKey.showDue]),
     * unless one there is read [whole]. Skips shows whose key no longer matches the path, as reading an episode
     * could move it to another show ([ShowIndexer]).
     */
    private suspend fun showsToRead(
        source: SourceEntity,
        files: List<RemoteFile>,
        whole: Set<RemoteFile>,
        known: Map<String, KnownFile>,
        sidecars: Sidecars,
    ): Map<RemoteFile, Long> {
        if (source.kind != SourceKind.SHOWS) return emptyMap()
        val byFolder = files.groupBy { f -> known[f.relativePath]?.seriesId?.let { Sidecars.folderOf(f.relativePath) to it } }
            .mapNotNull { (key, episodes) ->
                val seriesId = key?.second ?: return@mapNotNull null
                val path = FilenameParser.parseShowPath(episodes.first().relativePath) ?: return@mapNotNull null
                ShowFolder(path.dir, seriesId, FilenameParser.showKey(path.title)) to episodes
            }
            .groupBy({ it.first }, { it.second }).mapValues { (_, lists) -> lists.flatten() }
        val shows = byFolder.keys.map { it.seriesId }.distinct().chunked(SQL_IN_LIMIT).flatMap { dao.rows(it) }.associateBy { it.id }
        return byFolder.entries.mapNotNull { (folder, episodes) ->
            val show = shows[folder.seriesId]?.takeIf { it.matchKey?.substringBefore(FilenameParser.YEAR_MARK) == folder.name }
                ?: return@mapNotNull null
            val due = episodes.none { it in whole } && SidecarKey.showDue(show.sidecarKey, source.id, folder.dir, sidecars)
            if (due) episodes.first() to folder.seriesId else null
        }.toMap()
    }

    private data class ShowFolder(val dir: String, val seriesId: Long, val name: String)

    /** [work], except that a file picked by [showsToRead] gets a full read unless its show was read this pass. */
    private fun stillDue(work: (RemoteFile) -> Work?, forShows: Map<RemoteFile, Long>): (RemoteFile) -> Work? =
        { file -> Work.FULL.takeIf { forShows[file]?.let(shows::readThisPass) == false } ?: work(file) }

    /** Logs videos that name no episode, which otherwise get no row silently. */
    private fun tellNoEpisode(source: SourceEntity, files: List<RemoteFile>) {
        val refused = files.count { FilenameParser.parseShowPath(it.relativePath) == null }
        if (refused > 0) Log.i(TAG, "${source.name}: $refused of ${files.size} videos name no episode, and are left out")
    }

    private fun errorOf(source: SourceEntity, failure: Throwable): ScanError {
        Log.w(TAG, "Scan of ${source.name} failed", failure)
        return if (outOfSpace(failure)) ScanError(ScanErrorKind.STORAGE_FULL) else ScanErrors.classify(failure)
    }

    private class Folder(val pass: ScanPass, val known: Map<String, KnownFile>)

    /**
     * Processes one file under the lock, so user actions wait for one item, not the whole scan; the row is reloaded
     * as it may have been edited. True when a row was added or its [shown] state changed.
     */
    private suspend fun process(folder: Folder, file: RemoteFile, work: Work): Boolean = lock.withLock {
        val source = folder.pass.source
        // The source was removed meanwhile: add nothing more to it.
        if (dao.source(source.id) == null) return@withLock false
        val existing = folder.known[file.relativePath]?.let { dao.item(it.id) }
        val before = existing?.let { shown(it) }
        if (work == Work.PROBE && existing != null) reprobe(source, file, existing) else when (source.kind) {
            SourceKind.MOVIES -> movies.process(folder.pass, file, existing)
            SourceKind.SHOWS -> shows.process(folder.pass, file, existing)
        }
        if (existing == null) dao.hasFile(source.id, file.relativePath) else dao.item(existing.id)?.let { shown(it) } != before
    }

    /** Re-probes headers only ([Work.PROBE]); the stored NFO fills gaps, and neither sidecars nor TMDB are read. */
    private suspend fun reprobe(source: SourceEntity, file: RemoteFile, existing: ItemEntity) {
        val nfo = existing.nfo?.let(NfoParser::parse)
        dao.saveScanned(files.mediaFields(source, file, existing).withNfoStreams(nfo))
    }

    /** Visible state of a file's row and its season and show, ignoring bookkeeping columns, to detect real changes. */
    private suspend fun shown(item: ItemEntity): List<ItemEntity> =
        (listOf(item) + dao.rows(listOfNotNull(item.parentId, item.seriesId)).sortedBy { it.id }).map { row ->
            row.copy(
                matchedAt = row.matchedAt?.let { MATCHED }, sidecarKey = null, probeVersion = 0, mediaInfo = unstamped(row.mediaInfo),
                creditsAt = null,
            )
        }

    private fun unstamped(mediaInfo: String?): String? =
        MediaProbe.decode(mediaInfo)?.copy(probedWith = 0)?.let(MediaProbe::encode) ?: mediaInfo

    /**
     * Checks free space too: on a full disk SQLite rolls back by itself and Room's rollback then fails ("cannot
     * rollback - no transaction is active"), hiding the cause in [e].
     */
    private fun outOfSpace(e: Throwable): Boolean =
        ScanErrors.classify(e).kind == ScanErrorKind.STORAGE_FULL || artwork.spaceLeft() < NEARLY_FULL_BYTES

    /**
     * Waits during playback (bounded, in case the player never reports its end), then while in the background.
     * NAS sessions are closed first, as a wait can last hours.
     */
    private suspend fun waitForThePlayer() {
        if (!playing.value && started.first()) return
        closeIdle()
        withTimeoutOrNull(MAX_PAUSE_MS) { playing.first { !it } }
        started.first { it }
    }

    private suspend fun settleMissing(kind: SourceKind, known: List<KnownFile>, listing: Listing, stacks: Stacks): MissingFiles.Outcome {
        val now = clock()
        val missing = MissingFiles.settle(kind, known, listing, now, stacks)
        lock.withLock { dao.settleMissing(missing.relinked, missing.back, missing.held, missing.removed, missing.parts, now) }
        return missing
    }

    suspend fun showQuery(series: ItemEntity): ParsedMovie = shows.showQuery(series)

    private enum class Work {
        /** Full read: sidecar NFO and artwork, and TMDB when due. */
        FULL,

        /** Probed by an older version with nothing else due: re-read headers only ([reprobe]). */
        PROBE,
    }

    /** Full read for new, replaced, refreshed, sidecar-changed or (with [askTmdb]) unmatched files. */
    private fun workOn(item: KnownFile?, file: RemoteFile, sidecars: String?, askTmdb: Boolean, refreshMetadata: Boolean): Work? =
        when {
            item == null || item.sizeChanged(file) || refreshMetadata || (askTmdb && item.matchedAt == null) -> Work.FULL
            item.sidecarKey != sidecars -> Work.FULL
            item.probeVersion < MediaProbe.VERSION -> Work.PROBE
            else -> null
        }

    companion object {
        private const val TAG = "LibraryScanner"
        private const val MAX_UNREACHABLE = 3

        /** Files between two change notifications. */
        private const val TELL_EVERY = 5

        /** Stand-in for matchedAt in [shown]: only whether a title was matched matters. */
        private const val MATCHED = 0L

        /** Retry delay for unmatched titles while TMDB refuses the key or is unreachable. */
        private const val UNMATCHED_RETRY_MS = 60 * 60_000L

        /** Below this free space a failure is attributed to the full device. */
        private const val NEARLY_FULL_BYTES = 50L shl 20
        private const val MAX_PAUSE_MS = 4 * 60 * 60_000L

        /** Title and year from the file name or folder ("Title (Year)/movie.mkv"); a disc by its folder. */
        fun parseMovie(relativePath: String): ParsedMovie {
            val path = FilenameParser.discOf(relativePath)?.root?.ifEmpty { null } ?: relativePath
            val fromFile = FilenameParser.parseMovie(path.substringAfterLast('/'))
            // The folder name is often more reliable than the file name.
            val fromFolder = path.split('/').dropLast(1).lastOrNull()?.let(FilenameParser::parseMovie)
            return if (fromFile.year == null && fromFolder?.year != null) fromFolder else fromFile
        }
    }
}
