package app.houselights.providers.local

import android.os.SystemClock
import android.util.Log
import app.houselights.core.FailureKind
import app.houselights.core.NfoParser
import app.houselights.core.NoTmdbKeyException
import app.houselights.core.RepeatedFailures
import app.houselights.core.TmdbUnreachableException
import app.houselights.core.failureKind
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class ScanState(
    val running: Boolean = false,
    /** Id of the source being scanned, as two sources may share a name. */
    val sourceId: Long? = null,
    val processed: Int = 0,
    val total: Int = 0,
    val current: String? = null,
    /**
     * The videos the listing of the folder under way has found so far, counted as the pass counts titles where their
     * paths tell ([VideoCount]): written at most once a second ([LibraryScanner.LISTED_EVERY_MS]), none in the
     * listing's first, so a folder read at once says no count; 0 before. Screens say it until the files are counted.
     */
    val listed: Int = 0,
    /** Last error per source id, kept after the scan ends. */
    val errors: Map<Long, ScanError> = emptyMap(),
    /** Why the last scan stopped querying TMDB; kept until TMDB is queried again. */
    val tmdbIssue: FailureKind? = null,
    /**
     * Whether home is still filling with the library's very first titles: the pass under way began on a library
     * holding none, and home's row of latest films or of latest shows is not full yet ([LocalProvider.LATEST]); screens
     * follow it closer meanwhile.
     */
    val homeFilling: Boolean = false,
    /**
     * Whether the pass under way began on a library holding no title, the library's very first scan: kept until the pass
     * ends, where [homeFilling] goes once home's rows are full.
     */
    val firstPass: Boolean = false,
    /** Whether the pass under way has read every folder's files: what is left of it (titles asked again, credits) goes on. */
    val finishing: Boolean = false,
)

/** No video while [count] subfolders were denied: a permissions problem, so nothing is removed. */
class UnreadableFolderException(count: Int) : IllegalStateException("No video listed, and $count subfolders could not be read")

/**
 * Incremental scanner: lists sources, handles missing files ([MissingFiles]) and matches new ones on TMDB, which is
 * optional ([TmdbPass]); unmatched titles are retried hourly, or at once on a requested scan, and titles TMDB found
 * nothing for or hesitated on are asked again daily, then weekly ([Reidentify]). Writes go through
 * [LibraryDao.saveScanned], so user choices and viewing state are never overwritten. Each file is read under the
 * library's lock from what the NAS and TMDB answered with it released ([Rounds]): the user's actions never wait for
 * the network, and what they changed meanwhile is read before anything is written.
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
    /**
     * Whether Android's screensaver shows over the app, begun while it was on screen: the scan goes on under it as on
     * screen, no player reading the NAS meanwhile, so a first scan of hours does not stop at the first one. One begun
     * over another app is not the app's: that app may play from the NAS again once woken.
     */
    private val dreaming: Flow<Boolean> = flowOf(false),
) {
    /** What the scan goes on for, playback aside: the app on screen, or Android's screensaver over it ([dreaming]). */
    private val present: Flow<Boolean> = combine(started, dreaming) { onScreen, dream -> onScreen || dream }

    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state

    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits for a change screens show at once: an edit, a folder removed, the end of a pass that changed the library. */
    val changed: SharedFlow<Unit> = _changed

    private val _progress = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits while a pass writes, so screens show new entries progressively. */
    val progress: SharedFlow<Unit> = _progress

    private val _passEnded = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)

    /** Emits at the end of each pass, true when it changed the library. */
    val passEnded: SharedFlow<Boolean> = _passEnded

    /**
     * Serialises the library's writes: a pass's and the user's. A pass holds it only to read and write the library, its
     * NAS reads and TMDB requests collected with it released ([Rounds]), so a user action waits for no network.
     */
    private val lock = Mutex()

    private val rounds = Rounds(dao, lock)

    private val files = ScanFiles(artwork, browsers)

    /** Files whose processing fails, often at every pass: each logged whole the first time, then counted. */
    private val failures = RepeatedFailures(TAG)
    private val movies = MovieIndexer(dao, files, clock)
    private val shows = ShowIndexer(dao, files, clock)
    private val placing = EpisodePlacing(dao, clock)
    private val backfill = Backfill(dao, lock, System::currentTimeMillis) { waitForThePlayer() }
    private val reidentify = Reidentify(dao, rounds, movies, shows, clock) { waitForThePlayer() }

    /** What the indexers keep in memory through a pass, with [orders], its shows' numberings: put back with a run ([Rounds]). */
    private fun passState(orders: EpisodeOrders) = PassState {
        val films = movies.mark()
        val series = shows.mark()
        val numberings = orders.mark()
        return@PassState {
            films()
            series()
            numberings()
        }
    }

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

    /**
     * Removes unreferenced cached artwork, credits and search words, between two runs of a pass's work ([Rounds]); the
     * copies a file being read holds are spared ([ArtworkCache.sweep]).
     */
    suspend fun cleanup(): CleanupResult = withContext(Dispatchers.IO) {
        lock.withLock { cleanupLocked() }
    }

    /**
     * Asks every folder to be read again whole in [locale], the details' new language, and scans: each folder stays
     * due until a pass in that language reads it, so one out of reach is read once back, the others left alone.
     */
    suspend fun refreshAllIn(locale: Locale) {
        dao.refreshAllIn(locale.toLanguageTag())
        scanAll()
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
     * run first, so a failure leaves the item unchanged; writes then run in one non-cancellable transaction. All under
     * the lock, as the user waits for them anyway: a pass waits for them, never the other way.
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
                    if (item.kind == ItemKind.SERIES) placedAfterIdentify(item.id, tmdb)
                    _changed.tryEmit(Unit)
                }
            }
        }
    }

    /**
     * A show identified places its episodes numbered from its start in the new show's seasons at once; TMDB failing, they
     * wait for the next pass, which reads them again ([ShowIndexer]).
     */
    private suspend fun placedAfterIdentify(seriesId: Long, tmdb: TmdbClient) {
        suspendRunCatching { placing.reorder(seriesId, TmdbPass(tmdb, strict = true)) }
            .onFailure { Log.w(TAG, "Episodes of show $seriesId wait for the next pass to be placed", it) }
    }

    /**
     * Places show [itemId]'s episodes in the order [orderId] ([EpisodeOrderEntity.AIRED] or a group's id), or the
     * automatic one when null, as [identify] does: TMDB asked first, a failure leaving the show as it was, then the writes
     * in one non-cancellable transaction; what was watched stays, row by row ([EpisodeRemap]).
     */
    suspend fun chooseOrder(itemId: Long, orderId: String?) {
        withContext(Dispatchers.IO) {
            val tmdb = interactiveTmdb() ?: throw NoTmdbKeyException()
            lock.withLock {
                // Outliving the page that asked: TMDB's answers are written whole or not at all.
                withContext(NonCancellable) {
                    placing.reorder(itemId, TmdbPass(tmdb, strict = true), EpisodePlacing.Choice(orderId))
                        ?: throw TmdbUnreachableException(null, "TMDB did not answer for show $itemId")
                    _changed.tryEmit(Unit)
                }
            }
        }
    }

    private fun client(): TmdbClient? = tmdb()

    /**
     * TMDB's failure state persists until a retry, an answer for a new file, or a key change ([tmdbKeyChanged]); a pass
     * that saw TMDB fail, then answer again, ends without it ([TmdbPass.endIssue]).
     */
    private fun tmdbDownAfter(tmdb: TmdbPass, down: TmdbDown?, askedUnmatched: Boolean): TmdbDown? =
        tmdb.endIssue?.let { TmdbDown(it, tmdb.client, elapsed()) } ?: down.takeUnless { askedUnmatched || tmdb.answered }

    private class TmdbDown(val cause: Throwable, val client: TmdbClient?, val at: Long)

    /** Why TMDB was closed to recent passes; null once a retry was not refused. */
    @Volatile
    private var tmdbDown: TmdbDown? = null

    /** Set by a requested scan: the next pass retries unmatched titles regardless of [tmdbDown]. */
    private val askTmdbAgain = AtomicBoolean(false)

    private suspend fun cleanupLocked(): CleanupResult {
        // One transaction for them all, on a device where each costs a sync to storage.
        var orphanCast = 0
        dao.inTransaction {
            orphanCast = dao.deleteOrphanCredits()
            dao.deleteOrphanSearchWords()
            dao.deleteOrphanTrackChoices()
            dao.deleteOrphanSuggestions()
            dao.deleteOrphanEpisodeOrders()
            dao.deleteOrphanIgnored()
        }
        // Based on library references, not on the NAS listing, so an unreachable NAS never deletes artwork.
        val result = artwork.sweep(dao.cachedArtworkPaths())
        if (result.deletedFiles > 0 || orphanCast > 0) {
            Log.i(TAG, "Cleanup: ${result.deletedFiles} images (${result.freedBytes / KIB} KB), $orphanCast cast rows")
        }
        cleanedAt = elapsed()
        return result
    }

    /** When the last cleanup ended, on the monotonic [elapsed] clock; null before the first. */
    @Volatile
    private var cleanedAt: Long? = null

    /**
     * Whether a pass ends with a cleanup: once it removed rows or made them parts of a film ([rowsLeft]), or matched
     * titles anew ([identified]), which leaves artwork, credits and words unused; else once a [CLEANUP_EVERY_MS], as a
     * pass that only adds or reads again leaves little, and the cleanup reads every reference and every cached image.
     * Before the first cleanup since the app started, which [cleanedAt] alone remembers, a pass cleans up only when it
     * [changed] the library: the pass of every cold start, the NAS unchanged, leaves « Identifier », « Garder » or
     * « Retirer » no cleanup to wait for.
     */
    private fun cleanupDue(identified: Boolean, changed: Boolean): Boolean {
        val last = cleanedAt
        return rowsLeft || identified || if (last == null) changed else elapsed() - last >= CLEANUP_EVERY_MS
    }

    private suspend fun scanAllSources(refreshMetadata: Boolean) {
        stoppedAfterWriting = false
        rowsLeft = false
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
        val orders = EpisodeOrders(dao, tmdb, clock)
        val down = tmdbDown?.takeIf { it.client === client }
        val asked = askTmdbAgain.getAndSet(false)
        val askUnmatched = askUnmatched(down, asked)
        try {
            val sources = dao.sources()
            val first = !dao.anyItem()
            _state.update { it.copy(homeFilling = first, firstPass = first, finishing = false) }
            val reached = mutableSetOf<Long>()
            for (source in sources) {
                reached += source.id
                val failure = suspendRunCatching {
                    changed = scanFolder(source, refreshMetadata, ShowsTmdb(tmdb, seasons, orders), askUnmatched) || changed
                }.exceptionOrNull()
                val error = failure?.let { errorOf(source, it) }
                error?.let { errors[source.id] = it }
                // Out of space, every other source would fail the same way, file after file.
                if (error?.kind == ScanErrorKind.STORAGE_FULL) break
            }
            // Every folder's files read: what is left (titles asked again, credits, cleanup) goes on behind.
            _state.update { it.copy(finishing = true) }
            // Sources the pass did not reach keep their last error.
            errors += before.filterKeys { id -> id !in reached && sources.any { it.id == id } }
            // Titles to identify are asked again while TMDB answers, as titles waiting for it are, and episodes placed.
            val identified = askUnmatched && askedAgain(ShowsTmdb(tmdb, seasons, orders), asked)
            changed = identified || changed
            lock.withLock {
                suspendRunCatching {
                    // Rows added meanwhile under a source removed during the scan.
                    dao.deleteOrphanItems()
                    dao.deleteEmptyShows(handedOver = shows.handedOver())
                    dao.moveOrphanShows()
                    dao.deleteEmptyCollections()
                    if (dao.groupVersions() > 0) changed = true
                    // Artwork, credits and words only become unused when titles go or are matched anew.
                    if (cleanupDue(identified, changed)) cleanupLocked()
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
        // No change, no refresh of the screens; titles a folder wrote before it stopped are one, its progress showed them.
        if (changed || stoppedAfterWriting) _changed.emit(Unit)
        _passEnded.tryEmit(changed)
    }

    /** After a recent TMDB failure with this key ([down]), unmatched titles wait an hour unless the scan was [asked] for. */
    private fun askUnmatched(down: TmdbDown?, asked: Boolean): Boolean =
        asked || down == null || elapsed() - down.at >= UNMATCHED_RETRY_MS

    /**
     * Runs [Reidentify] after the sources while TMDB answers, sooner on a scan the user [asked] for; true when a title
     * changed. A failure is retried at the next pass.
     */
    private suspend fun reidentified(shows: ShowsTmdb, asked: Boolean): Boolean = shows.pass.open &&
        suspendRunCatching { reidentify.run(shows.pass, asked, passState(shows.orders)) }
            .onSuccess { if (it > 0) Log.i(TAG, "To identify: $it titles asked again changed") }
            .onFailure { Log.w(TAG, "Asking TMDB again for titles to identify failed; tried again at the next pass", it) }
            .getOrDefault(0) > 0

    /** [reidentified], then [numbered]: true when either changed the library. */
    private suspend fun askedAgain(shows: ShowsTmdb, asked: Boolean): Boolean {
        val identified = reidentified(shows, asked)
        return numbered(shows) || identified
    }

    /**
     * After the sources, while TMDB answers: episodes waiting for TMDB to list them placed once it does, a few shows a day
     * ([WAITING_PER_PASS], [EpisodePlacing.placeWaiting]), and the groups of a few shows listed for the first time or again
     * ([GROUPS_PER_PASS], every [GROUPS_EVERY_MS]), so a show matched before they were kept offers its orders; true when
     * an episode moved. A failure is retried at the next pass.
     */
    private suspend fun numbered(tmdb: ShowsTmdb): Boolean {
        if (!tmdb.pass.open) return false
        val now = clock()
        return suspendRunCatching {
            var moved = false
            for (show in dao.waitingShows(before = now - WAITING_EVERY_MS, now = now, limit = WAITING_PER_PASS)) {
                if (!tmdb.pass.open) break
                waitForThePlayer()
                moved = rounds.run(tmdb.pass, passState(tmdb.orders)) { placing.placeWaiting(show, tmdb.orders, tmdb.seasons) } || moved
            }
            for (show in dao.groupsDue(before = now - GROUPS_EVERY_MS, now = now, limit = GROUPS_PER_PASS)) {
                if (!tmdb.pass.open) break
                rounds.run(tmdb.pass, passState(tmdb.orders)) { tmdb.orders.list(show) }
            }
            moved
        }.onFailure { Log.w(TAG, "Placing episodes TMDB lists, or listing shows' orders, failed; tried again at the next pass", it) }
            .getOrDefault(false)
    }

    /** What a pass asks TMDB through, for shows: its [pass], the seasons it read and how it numbers each show. */
    private class ShowsTmdb(val pass: TmdbPass, val seasons: ShowIndexer.SeasonCache, val orders: EpisodeOrders)

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

    /**
     * « Garder »: the titles held in folder [sourceId] ([MissingFiles.isDrop]) wait for their files, nothing asking again
     * until another drop; only while they are those the user was [shown], so that a drop held meanwhile is asked about.
     */
    suspend fun keepHeld(sourceId: Long, shown: Collection<Long>) {
        withContext(Dispatchers.IO) { lock.withLock { withContext(NonCancellable) { keepHeldLocked(sourceId, shown) } } }
    }

    private suspend fun keepHeldLocked(sourceId: Long, shown: Collection<Long>) {
        val held = dao.heldIn(sourceId)
        if (held.isNotEmpty() && shown.toHashSet().containsAll(held)) dao.setHeldKept(sourceId, true)
    }

    /**
     * « Retirer »: the rows among [ids] still held leave the library, as a pass removes files gone, what the user did kept
     * by title; nothing is deleted on the NAS. Rows whose files came back meanwhile stay.
     */
    suspend fun removeHeld(ids: Collection<Long>) {
        val removed = withContext(Dispatchers.IO) { lock.withLock { withContext(NonCancellable) { removeHeldLocked(ids) } } }
        if (removed > 0) _changed.emit(Unit)
    }

    /** Removes the rows among [ids] still held, then the artwork only they used; how many went. */
    private suspend fun removeHeldLocked(ids: Collection<Long>): Int {
        val count = dao.removeHeld(ids, handedOver = shows.handedOver())
        if (count > 0) suspendRunCatching { cleanupLocked() }.onFailure { Log.w(TAG, "Cleanup after removing titles held failed", it) }
        return count
    }

    /**
     * Removes title [itemId] alone while it is held: a film's copies held, a show's episodes held, or an episode. What
     * holds it after: its own row, the copy now holding the film once that row went, an episode's show once the episode
     * went; null once nothing of it is left.
     */
    suspend fun removeHeldTitle(itemId: Long): Long? {
        val (removed, stays) = withContext(Dispatchers.IO) {
            lock.withLock {
                withContext(NonCancellable) {
                    val rows = dao.titleRows(itemId)
                    val show = dao.rows(listOf(itemId)).singleOrNull()?.seriesId
                    val count = removeHeldLocked(dao.heldOf(itemId))
                    count to (dao.titleAmong(rows) ?: show?.let { dao.titleAmong(listOf(it)) })
                }
            }
        }
        if (removed > 0) _changed.emit(Unit)
        return stays
    }

    /** [scan], whole for a folder due in this pass's language ([refreshAllIn]), which is then no longer due. */
    private suspend fun scanFolder(source: SourceEntity, refreshMetadata: Boolean, shows: ShowsTmdb, askUnmatched: Boolean): Boolean {
        // Due in another language than this pass's TMDB speaks: a later pass reads it.
        val due = source.refreshIn?.takeIf { it == shows.pass.client?.locale?.toLanguageTag() }
        val changed = scan(source, refreshMetadata || due != null, shows, askUnmatched)
        if (due != null) dao.refreshedIn(source.id, due)
        return changed
    }

    /** Scans one source; true when the library changed. [askUnmatched] retries titles only waiting for TMDB. */
    private suspend fun scan(source: SourceEntity, refreshMetadata: Boolean, shows: ShowsTmdb, askUnmatched: Boolean): Boolean {
        val tmdb = shows.pass
        waitForThePlayer()
        _state.update { it.copy(running = true, sourceId = source.id, processed = 0, total = 0, current = null, listed = 0) }
        val found = suspendRunCatching { listing(source) }.onFailure { e -> notListed(source, e) }.getOrThrow()
        goneOnce -= source.id
        val listed = dao.knownFiles(source.id)
        checkListed(found, listed)
        // A disc's streams form one title and extras none; rows of non-titles are handled as missing files.
        val listing = ScanTitles.of(found)
        val stacks = stacksOf(source.kind, listing.videos, listed)
        val files = stacks.titles
        val sidecars = Sidecars(listing.sidecars, files, stacks)
        val missing = settleMissing(source, listed, listing, stacks)
        if (missing.removed.isNotEmpty() || missing.absorbed.isNotEmpty()) rowsLeft = true
        // Renamed files moved their rows, and films their parts, so reload the known files.
        val rewritten = missing.relinked.isNotEmpty() || missing.parts.isNotEmpty()
        val known = (if (rewritten) dao.knownFiles(source.id) else listed).associateBy { it.filePath }
        // Parts of a film whose first part is missing wait with its row instead of becoming titles.
        val waiting = MissingFiles.waitingParts(known.values, files, missing.removed + missing.absorbed)

        // While TMDB is closed (now or since a recent pass), titles only waiting for it are left alone.
        fun read(f: RemoteFile): Work? {
            val beside = SidecarKey.listed(source.kind, sidecars.forVideo(f))
            return workOn(known[f.relativePath], f, beside, tmdb.open && askUnmatched, refreshMetadata)
        }
        val ready = files.filterNot { it.relativePath in waiting }
        val names = Names(source, ready, stacks, renamed = missing.relinked.mapTo(HashSet()) { it.first }, open = tmdb.open && askUnmatched)
        val films = names.films(known, ::read)
        fun work(f: RemoteFile): Work? = Work.FULL.takeIf { f.relativePath in films && tmdb.open } ?: read(f)
        val own = ready.associateWith { work(it) }.filterValues { it != null }
        // When a show's own sidecars changed, or its folder's name gives another id, and none of its episodes is read in
        // full, one episode reads them.
        val folders = showFolders(source, files, known)
        val whole = own.filterValues { it == Work.FULL }.keys
        val hintedShows = names.shows(folders, whole)
        val forShows = showsToRead(source, folders, whole, sidecars, hintedShows)
        val todo = inOrder(own, forShows)
        _state.update { it.copy(total = todo.size) }
        Log.i(
            TAG,
            "${source.name}: ${files.size} files, ${missing.relinked.size} renamed, ${missing.removed.size} removed, " +
                "${missing.absorbed.size} now parts, ${missing.held.size} held, ${missing.waiting.size} still held, ${todo.size} to do",
        )

        noteIgnored(source, files)

        val pass = ScanPass(
            source, sidecars, stacks, tmdb, shows.seasons, shows.orders, refreshMetadata, names.single, films.values.toSet() + hintedShows,
        )
        val folder = Folder(pass, known)
        val written = processAll(folder, todo, stillDue(::work, forShows))
        dao.markScanned(source.id, clock())
        // Renames and part changes count even without a re-read, as screens show file names and parts.
        val changed = missing.changed || written > 0
        if (changed) tellProgress()
        return changed
    }

    /**
     * [source]'s videos and sidecars. A film started while they are listed, or the app leaving the screen (another app
     * may play from the NAS, [present]), stops the listing between two folders, which leaves its session or mount, closed
     * as the scan waits ([waitForThePlayer]), and begins anew once the player stops and the app is back: no listing reads
     * the NAS through a film's start. A wait the player outlasted ([MAX_PAUSE_MS]) lets the listing go on through it, as
     * the files are then read. The count screens say never goes back meanwhile.
     */
    private suspend fun listing(source: SourceEntity): Listing = coroutineScope {
        val away = AtomicBoolean(false)
        // Unconfined: told as the app leaves, before the walk next asks whether to stop.
        val watch = launch(Dispatchers.Unconfined) { present.collect { away.set(!it) } }
        try {
            listing(source, away::get)
        } finally {
            watch.cancel()
        }
    }

    /** [listing], [away] saying whether the app left the screen. */
    private suspend fun listing(source: SourceEntity, away: () -> Boolean): Listing {
        val pace = listedPace()
        var most = 0
        var waitedOut = false
        while (true) {
            try {
                return browsers(source).list(source, pauses = { (playing.value && !waitedOut) || away() }) { count ->
                    if (count > most) {
                        most = count
                        pace(count)
                    }
                }
            } catch (_: ListingPausedException) {
                Log.i(TAG, "${source.name}: listed again once the film ends and the app is back")
                waitedOut = !waitForThePlayer()
            }
        }
    }

    /**
     * Tells screens how many videos a folder's listing found so far ([ScanState.listed]), at most once a
     * [LISTED_EVERY_MS], its start counting as one: a word that changes, never figures scrolling.
     */
    private fun listedPace(): (Int) -> Unit {
        var toldAt = elapsed()
        return { count ->
            val now = elapsed()
            if (now - toldAt >= LISTED_EVERY_MS) {
                toldAt = now
                _state.update { it.copy(listed = count) }
            }
        }
    }

    /**
     * Throws when [found] is no listing to scan by: no video and denied subfolders, a permissions problem whatever the
     * library holds; no video in a folder that had some ([listed]), which leaves the library as it is.
     */
    private fun checkListed(found: Listing, listed: List<KnownFile>) {
        if (found.videos.isNotEmpty()) return
        if (found.unreadable.isNotEmpty()) throw UnreadableFolderException(found.unreadable.size)
        if (listed.isNotEmpty()) throw EmptyFolderException()
    }

    /** Stacks of [videos], except those whose rows were matched with different films ([MissingFiles.split]). */
    private fun stacksOf(kind: SourceKind, videos: List<RemoteFile>, listed: List<KnownFile>): Stacks {
        val all = Stacks.of(kind, videos)
        val apart = MissingFiles.split(listed, all)
        return if (apart.isEmpty()) all else Stacks.of(kind, videos, apart)
    }

    /**
     * Tells screens of the titles a pass wrote so far ([progress]), once home's row of latest films or of latest shows
     * is full no longer as closely as while it filled ([ScanState.homeFilling]).
     */
    private suspend fun tellProgress() {
        if (_state.value.homeFilling && homeFilled()) _state.update { it.copy(homeFilling = false) }
        _progress.emit(Unit)
    }

    /** Whether home's row of latest films or of latest shows holds all it shows ([LocalProvider.LATEST]). */
    private suspend fun homeFilled(): Boolean =
        dao.count(ItemKind.MOVIE) >= LocalProvider.LATEST || dao.count(ItemKind.SERIES) >= LocalProvider.LATEST

    /**
     * Whether a folder of the pass under way stopped after writing titles, or holding them ([holdGone]), which its end
     * then tells screens of.
     */
    private var stoppedAfterWriting = false

    /** Whether a folder of the pass under way removed rows, or made them parts of a film: its end cleans up ([cleanupDue]). */
    private var rowsLeft = false

    /**
     * Re-evaluates [due] per file, as TMDB may close meanwhile; returns how many files changed the library. A file
     * that failed at an earlier pass and fails again the same way is counted in one line rather than logged whole.
     */
    private suspend fun processAll(folder: Folder, todo: List<RemoteFile>, due: (RemoteFile) -> Work?): Int {
        var unreachable = 0
        var written = 0
        var told = 0
        val source = folder.pass.source
        try {
            todo.forEachIndexed { index, file ->
                val work = due(file) ?: return@forEachIndexed
                waitForThePlayer()
                _state.update { it.copy(processed = index, current = file.relativePath.substringAfterLast('/')) }
                val key = "${source.id}/${file.relativePath}"
                suspendRunCatching { process(folder, file, work) }.onSuccess { wrote ->
                    unreachable = 0
                    failures.recovered(key)
                    if (wrote) written++
                }.onFailure { e ->
                    failures.failed(key, "Failed on ${file.relativePath}", e)
                    // A new film's row is written before it is read.
                    if (folder.known[file.relativePath] == null && dao.hasFile(source.id, file.relativePath)) written++
                    if (ScanErrors.isUnreachable(e)) unreachable++
                    // Device full or NAS unreachable: every remaining file would fail the same way, after long timeouts.
                    if (outOfSpace(e) || unreachable >= MAX_UNREACHABLE) {
                        if (written > 0) stoppedAfterWriting = true
                        throw e
                    }
                }
                if (index % TELL_EVERY == TELL_EVERY - 1 && written > told) {
                    told = written
                    tellProgress()
                }
            }
        } finally {
            failures.summarize { "${source.name}: files failing again, each logged at an earlier pass: $it" }
            files.summarize(source)
        }
        return written
    }

    /** The path a film at [file] is named by, for its ids ([NameIds.ofFilm]): a stack's without its part, a disc's folder. */
    private fun filmPath(stacks: Stacks, file: RemoteFile): String =
        NameIds.filmPath(stacks.stackAt(file.relativePath)?.namedPath ?: file.relativePath)

    /**
     * Titles of a pass of [source] looked up by the ids their names give ([NameIds.dueFor]), those [renamed] at once,
     * all while TMDB is [open]: those read anyway, and [NameIds.PER_PASS] more films and shows, so a library named with
     * ids is asked a few titles at a time after an upgrade. [ready] are its titles to read, named as [stacks] tell.
     */
    private inner class Names(
        private val source: SourceEntity,
        private val ready: List<RemoteFile>,
        private val stacks: Stacks,
        val renamed: Set<Long>,
        val open: Boolean,
    ) {
        private val now = clock()

        /** Folders of films holding one film ([NameIds.ofFilm]). */
        val single: Set<String> = if (source.kind == SourceKind.MOVIES) {
            ScanTitles.holdingOneFilm(ready.map { filmPath(stacks, it) })
        } else {
            emptySet()
        }

        /** Films due by their name's ids, their rows by path, of those [known] ([read] says which are read anyway). */
        fun films(known: Map<String, KnownFile>, read: (RemoteFile) -> Work?): Map<String, Long> {
            if (!open || source.kind != SourceKind.MOVIES) return emptyMap()
            val rows = ready.mapNotNull { f -> known[f.relativePath]?.let { f to it } }
            val due = rows.filter { (f, row) ->
                NameIds.ofFilm(filmPath(stacks, f), single).dueFor(row.hinted, show = false, now = now, renamed = row.id in renamed)
            }
            val (anyway, only) = due.partition { read(it.first) == Work.FULL }
            return (anyway + only.take(NameIds.PER_PASS)).associate { (f, row) -> f.relativePath to row.id }
        }

        /** Shows due by their folders' names' ids, of [folders] ([whole]: episodes read anyway). */
        fun shows(folders: List<ShowFolder>, whole: Set<RemoteFile>): Set<Long> {
            if (!open) return emptySet()
            val due = folders.filter { folder ->
                val moved = folder.episodes.any { it.id in renamed }
                NameIds.ofShow(folder.files.first().relativePath).dueFor(folder.show.hinted, show = true, now = now, renamed = moved)
            }
            val (anyway, only) = due.partition { folder -> folder.files.any { it in whole } }
            return (anyway.map { it.show.id } + only.map { it.show.id }.distinct().take(NameIds.PER_PASS)).toSet()
        }
    }

    /**
     * Each show folder's known episodes and their show, a show in two folders twice. Skips shows whose key no longer
     * matches the path, as reading an episode could move it to another show ([ShowIndexer]).
     */
    private suspend fun showFolders(source: SourceEntity, files: List<RemoteFile>, known: Map<String, KnownFile>): List<ShowFolder> {
        if (source.kind != SourceKind.SHOWS) return emptyList()
        val byFolder = files.groupBy { f -> known[f.relativePath]?.seriesId?.let { Sidecars.folderOf(f.relativePath) to it } }
            .mapNotNull { (key, episodes) ->
                val seriesId = key?.second ?: return@mapNotNull null
                val path = FilenameParser.parseShowPath(episodes.first().relativePath) ?: return@mapNotNull null
                Triple(path.dir, seriesId, FilenameParser.showKey(path.title)) to episodes
            }
            .groupBy({ it.first }, { it.second }).mapValues { (_, lists) -> lists.flatten() }
        val shows = byFolder.keys.map { it.second }.distinct().chunked(SQL_IN_LIMIT).flatMap { dao.rows(it) }.associateBy { it.id }
        return byFolder.mapNotNull { (folder, episodes) ->
            val (dir, seriesId, name) = folder
            val show = shows[seriesId]?.takeIf { it.matchKey?.substringBefore(FilenameParser.YEAR_MARK) == name } ?: return@mapNotNull null
            ShowFolder(dir, show, episodes, episodes.mapNotNull { known[it.relativePath] })
        }
    }

    /** A show [dir] and its show, with its [files] and their rows ([episodes]). */
    private class ShowFolder(val dir: String, val show: ItemEntity, val files: List<RemoteFile>, val episodes: List<KnownFile>)

    /**
     * One known episode (with its show id) per show folder whose show's sidecars are due ([SidecarKey.showDue]) or which
     * is [hinted], to be looked up by its folder name's ids, unless one there is read [whole].
     */
    private fun showsToRead(
        source: SourceEntity,
        folders: List<ShowFolder>,
        whole: Set<RemoteFile>,
        sidecars: Sidecars,
        hinted: Set<Long>,
    ): Map<RemoteFile, Long> = folders.mapNotNull { folder ->
        val show = folder.show
        val due = folder.files.none { it in whole } &&
            (show.id in hinted || SidecarKey.showDue(show.sidecarKey, source.id, folder.dir, sidecars))
        if (due) folder.files.first() to show.id else null
    }.toMap()

    /**
     * Files to read in full first, then those of [forShows], then headers to read again ([Work.PROBE]): after a new
     * probe version, these may take the whole pass, and a new film should not wait for them.
     */
    private fun inOrder(own: Map<RemoteFile, Work?>, forShows: Map<RemoteFile, Long>): List<RemoteFile> {
        val (probes, reads) = own.keys.partition { own[it] == Work.PROBE && it !in forShows }
        return reads + forShows.keys.filterNot { it in own } + probes
    }

    /** [work], except that a file picked by [showsToRead] gets a full read unless its show was read this pass. */
    private fun stillDue(work: (RemoteFile) -> Work?, forShows: Map<RemoteFile, Long>): (RemoteFile) -> Work? =
        { file -> Work.FULL.takeIf { forShows[file]?.let(shows::readThisPass) == false } ?: work(file) }

    /**
     * Keeps the videos of shows folder [source] whose names place them nowhere, and why ([FilenameParser.refusal]), as
     * Réglages lists them; written only when they changed, so a pass finding the same writes nothing. A films folder
     * leaves none. Run once its listing was read: a folder out of reach keeps its last list.
     */
    private suspend fun noteIgnored(source: SourceEntity, files: List<RemoteFile>) {
        val ignored = if (source.kind == SourceKind.SHOWS) {
            files.mapNotNull { f -> FilenameParser.refusal(f.relativePath)?.let { IgnoredFileEntity(source.id, f.relativePath, it) } }
        } else {
            emptyList()
        }
        if (ignored.isNotEmpty()) Log.i(TAG, "${source.name}: ${ignored.size} of ${files.size} videos name no episode, and are left out")
        lock.withLock {
            if (dao.ignoredOf(source.id).toSet() != ignored.toSet()) dao.replaceIgnored(source.id, ignored)
        }
    }

    private fun errorOf(source: SourceEntity, failure: Throwable): ScanError {
        Log.w(TAG, "Scan of ${source.name} failed", failure)
        return if (outOfSpace(failure)) ScanError(ScanErrorKind.STORAGE_FULL) else ScanErrors.classify(failure)
    }

    private class Folder(val pass: ScanPass, val known: Map<String, KnownFile>)

    /**
     * Processes one file: a new film's row first, so screens show it meanwhile; then the file read in one transaction
     * under the lock, its NAS reads and TMDB requests collected with the lock released ([Rounds]), so user actions wait
     * for the library's reads and writes, never the network. Rows are reloaded at each run, as they may have been
     * edited. True when a row was added or its [shown] state changed.
     */
    private suspend fun process(folder: Folder, file: RemoteFile, work: Work): Boolean {
        val pass = folder.pass
        val source = pass.source
        val known = folder.known[file.relativePath]
        val born = if (known == null && source.kind == SourceKind.MOVIES) {
            lock.withLock { if (dao.source(source.id) == null) null else movies.born(pass, file) } ?: return false
        } else {
            null
        }
        try {
            val aside = header(pass, file, work, known?.let { dao.item(it.id) } ?: born)
            return rounds.run(pass.tmdb, passState(pass.orders), aside) { read(pass, file, work, known, born) }
        } finally {
            artwork.letGo()
        }
    }

    /** One run of [process], under the lock: [file] read from the rows as they are now, [born] its new film's row. */
    private suspend fun read(pass: ScanPass, file: RemoteFile, work: Work, known: KnownFile?, born: ItemEntity?): Boolean {
        val source = pass.source
        // The source was removed meanwhile: add nothing more to it.
        if (dao.source(source.id) == null) return false
        val existing = known?.let { dao.item(it.id) }
        val before = existing?.let { shown(it) }
        if (work == Work.PROBE && existing != null) {
            reprobe(source, file, existing)
        } else {
            when (source.kind) {
                SourceKind.MOVIES -> movies.process(pass, file, existing, born?.let { dao.item(it.id) ?: return false })
                SourceKind.SHOWS -> shows.process(pass, file, existing)
            }
        }
        return if (existing == null) dao.hasFile(source.id, file.relativePath) else dao.item(existing.id)?.let { shown(it) } != before
    }

    /**
     * A film's header, when reading [row] (as it is now, null for none) reads it ([ScanFiles.withMediaInfo], [reprobe]):
     * read as TMDB answers ([Rounds.Aside]).
     */
    private fun header(pass: ScanPass, file: RemoteFile, work: Work, row: ItemEntity?): Rounds.Aside? {
        val source = pass.source
        if (source.kind != SourceKind.MOVIES) return null
        val reads = work == Work.PROBE || row == null || MediaProbe.isStale(row.mediaInfo) || row.sizeChanged(file)
        return if (reads) Rounds.Aside(files.probeOf(source, file)) { files.probe(source, file) } else null
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
                matchedAt = row.matchedAt?.let { MATCHED },
                sidecarKey = null,
                probeVersion = 0,
                mediaInfo = unstamped(row.mediaInfo),
                creditsAt = null,
            )
        }

    /** Without what screens never show: the probe's version and whether the tracks are in the file's order. */
    private fun unstamped(mediaInfo: String?): String? =
        MediaProbe.decode(mediaInfo)?.copy(probedWith = 0, tracksInOrder = false)?.let(MediaProbe::encode) ?: mediaInfo

    /**
     * Checks free space too: on a full disk SQLite rolls back by itself and Room's rollback then fails ("cannot
     * rollback - no transaction is active"), hiding the cause in [e].
     */
    private fun outOfSpace(e: Throwable): Boolean =
        ScanErrors.classify(e).kind == ScanErrorKind.STORAGE_FULL || artwork.spaceLeft() < NEARLY_FULL_BYTES

    /**
     * Waits during playback (bounded, in case the player never reports its end), then while in the background, unless
     * Android's screensaver shows ([present]). NAS sessions are closed first, as a wait can last hours. False when the
     * player still played as the bound ran out ([MAX_PAUSE_MS]).
     */
    private suspend fun waitForThePlayer(): Boolean {
        if (!playing.value && present.first()) return true
        closeIdle()
        val ended = withTimeoutOrNull(MAX_PAUSE_MS) { playing.first { !it } } != null
        present.first { it }
        return ended
    }

    private suspend fun settleMissing(
        source: SourceEntity,
        known: List<KnownFile>,
        listing: Listing,
        stacks: Stacks,
    ): MissingFiles.Outcome {
        val missing = MissingFiles.settle(source.kind, known, listing, stacks)
        write(source, known, missing)
        return missing
    }

    /**
     * After [source]'s listing failed with [e]: a folder its NAS says is gone ([ScanErrors.isFolderGone]) is held from
     * the second pass in a row that finds it so ([goneOnce]), as a folder renamed and named back, or a NAS that answered
     * oddly once, asks nothing. A failure of holding is kept with [e], which stays the one the folder shows.
     */
    private suspend fun notListed(source: SourceEntity, e: Throwable) {
        if (!ScanErrors.isFolderGone(source.protocol, e)) {
            goneOnce -= source.id
        } else if (goneOnce.add(source.id)) {
            Log.i(TAG, "${source.name}: the folder is gone from the NAS; its titles are held if it still is at the next pass")
        } else {
            suspendRunCatching { holdGone(source) }.onFailure { e.addSuppressed(it) }
        }
    }

    /** Folders the last pass that reached them found gone ([notListed]); kept while the app runs. */
    private val goneOnce: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /**
     * Holds the titles of [source], whose folder its NAS says is gone ([ScanErrors.isFolderGone]), as a sudden drop
     * holds them ([MissingFiles.folderGone]): asked about once, those held already waiting; the pass's end tells screens.
     */
    private suspend fun holdGone(source: SourceEntity) {
        val known = dao.knownFiles(source.id)
        val missing = MissingFiles.folderGone(known)
        Log.i(TAG, "${source.name}: the folder is gone from the NAS, ${missing.held.size} held, ${missing.waiting.size} still held")
        write(source, known, missing)
        if (missing.changed) stoppedAfterWriting = true
    }

    /** Writes [missing], decided for [source]'s rows [known], in one transaction. */
    private suspend fun write(source: SourceEntity, known: List<KnownFile>, missing: MissingFiles.Outcome) {
        lock.withLock {
            dao.inTransaction {
                dao.settleMissing(missing.relinked, missing.back, missing.held, missing.removed, missing.parts, clock(), missing.absorbed)
                movies.relinked(missing.relinked, known)
                // A new drop is asked about, with what an earlier one held and the user kept.
                if (missing.held.isNotEmpty()) dao.setHeldKept(source.id, false)
            }
        }
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

        /** The least time between two counts of a folder's listing told ([ScanState.listed]). */
        internal const val LISTED_EVERY_MS = 1_000L

        /** Stand-in for matchedAt in [shown]: only whether a title was matched matters. */
        private const val MATCHED = 0L

        /** The longest a pass goes without a cleanup when none removed rows nor matched titles anew ([cleanupDue]). */
        internal const val CLEANUP_EVERY_MS = 24 * 60 * 60_000L

        /** Retry delay for unmatched titles while TMDB refuses the key or is unreachable. */
        private const val UNMATCHED_RETRY_MS = 60 * 60_000L

        /** Shows whose episodes wait for TMDB to list them asked about a pass, each once a day at most. */
        private const val WAITING_PER_PASS = 10
        private const val WAITING_EVERY_MS = 24 * 60 * 60_000L

        /** Shows whose episode groups are listed a pass, each again after a month. */
        private const val GROUPS_PER_PASS = 5
        private const val GROUPS_EVERY_MS = 30 * 24 * 60 * 60_000L

        /** Below this free space a failure is attributed to the full device. */
        private const val NEARLY_FULL_BYTES = 50L shl 20
        private const val MAX_PAUSE_MS = 4 * 60 * 60_000L
        private const val KIB = 1024

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
