package app.houselights.providers.trakt

import android.os.SystemClock
import android.util.Log
import app.houselights.core.FailureKind
import app.houselights.core.HttpStatus
import app.houselights.core.failureKind
import app.houselights.core.suspendRunCatching
import app.houselights.providers.local.Marked
import app.houselights.providers.local.SyncRow
import app.houselights.providers.local.TraktAction
import app.houselights.providers.local.TraktDao
import app.houselights.providers.local.TraktPendingEntity
import app.houselights.providers.local.TraktStateEntity
import app.houselights.providers.local.TraktSyncError
import app.houselights.providers.local.WatchHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Headers
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** How much a sync does: [CHECK] reads Trakt only if it changed, [PULL] reads it, [UNION] also sends what Trakt lacks. */
enum class SyncMode { CHECK, PULL, UNION }

/** Where the sync with Trakt stands, as the pane says it. */
data class TraktSyncState(
    val running: Boolean = false,
    /** End of the last sync (ms); null before the first. */
    val syncedAt: Long? = null,
    val watched: Int = 0,
    val inProgress: Int = 0,
    val error: TraktSyncError? = null,
)

/** What the Trakt pane sees of the sync; an interface so tests can stand in for it. */
interface TraktSyncing {
    val state: StateFlow<TraktSyncState>

    /** Joins both sides at once: what Trakt has comes here, what only the library has goes to Trakt. */
    fun syncNow()
}

/** A waiting intent and the title it names. */
private typealias Queued = Pair<TraktPendingEntity, TraktTitle>

/**
 * Keeps the folders' history and the Trakt account's together, in the background. Connecting joins both sides
 * ([SyncMode.UNION]), as every sync does until one completes; then each mark and each stop goes to Trakt at once,
 * queued while Trakt cannot be reached and sent at the next occasion (launch, end of a scan, a sync, a stop). Trakt is
 * read again at launch after [STALE_MS] and at the end of each scan, only when it changed unless the scan found
 * titles, and read even when what waits cannot be sent. One exchange at a time; Trakt's limits are waited out
 * ([RETRY_AFTER]), its writes spaced by [WRITE_GAP_MS], and a write it keeps failing dropped after [MAX_ATTEMPTS].
 */
class TraktSync(
    private val account: TraktAccount,
    private val dao: TraktDao,
    private val scope: CoroutineScope,
    /** Tells screens a sync changed the library. */
    private val changed: () -> Unit = {},
    /** Whether the settings have Trakt keep the folders' history, read as a mark or a stop is queued; else the last [follow]. */
    private val following: (suspend () -> Boolean)? = null,
    private val now: () -> Long = System::currentTimeMillis,
    /** A monotonic clock (ms) that spaces Trakt's writes. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : TraktSyncing, WatchHistory {
    private val _state = MutableStateFlow(TraktSyncState())
    override val state: StateFlow<TraktSyncState> = _state

    /** One exchange with Trakt at a time: a sync, or the queue. */
    private val busy = Mutex()

    /** The library's watched state, one change at a time: a mark with its intents, or what a sync applies. */
    private val local = Mutex()

    /** Connected with the folders as the source: only then is Trakt read or written. */
    @Volatile
    private var active = false

    /** An account, even one Trakt ended, with the folders as the source: marks and stops are queued. */
    @Volatile
    private var follows = false

    /** Sync requests: one running, at most one more waiting, the larger [SyncMode] kept. Guarded by [runs]. */
    private val runs = Any()
    private var running = false
    private var again: SyncMode? = null

    private var lastWrite: Long? = null

    /** The last call to Trakt that failed, by its route, so that the log says where a failure came from. */
    private var failed: Pair<String, Throwable>? = null

    /**
     * The account and the source as the settings have them: a connection starts; signing out forgets what waits,
     * which stays for the next connection when Trakt ended this one.
     */
    fun follow(connected: Boolean, folders: Boolean, signedOut: Boolean = !connected) {
        active = connected && folders
        follows = !signedOut && folders
        when {
            signedOut -> scope.launch { disconnected(keepQueue = false) }
            !connected -> scope.launch { disconnected(keepQueue = true) }
            folders -> scope.launch { begin() }
        }
    }

    /** The folders are no longer the source: the account is disconnected, what waits and the sync's state forgotten. */
    suspend fun leave() {
        active = false
        follows = false
        suspendRunCatching { account.signOut() }.onFailure { Log.w(TAG, "Trakt could not be disconnected: ${described(it)}") }
        disconnected(keepQueue = false)
    }

    /** A scan ended: Trakt is read for the titles it found, else only if Trakt changed. */
    fun scanned(changedLibrary: Boolean) = request(if (changedLibrary) SyncMode.PULL else SyncMode.CHECK)

    override fun syncNow() = request(SyncMode.UNION)

    override suspend fun mark(what: Marked, played: Boolean, write: suspend () -> Unit) {
        // Once written, the mark's intents are queued whatever becomes of the caller.
        val queued = withContext(NonCancellable) {
            local.withLock {
                if (!keepsHistory()) {
                    write()
                    return@withLock false
                }
                val at = now()
                val before = read("rows marked") { rowsOf(what) }.orEmpty()
                write()
                queued(intentsOf(before, played, at))
            }
        }
        if (queued) scope.launch { sendQueue() }
    }

    override suspend fun stopped(id: Long, percent: Float) {
        if (percent < SyncPlan.MIN_PERCENT) return
        val queued = withContext(NonCancellable) {
            local.withLock {
                if (!keepsHistory()) return@withLock false
                val at = now()
                val titles = read("row stopped") { rowsOf(Marked.Row(id)).filter { it.id == id } }.orEmpty().flatMap { it.titles() }
                queued(
                    when {
                        titles.size == 1 -> listOf(TraktPendingEntity(titles.single().key, TraktAction.PROGRESS, at, percent))
                        // A file holding several episodes is watched or not: Trakt pauses one episode at a time.
                        percent >= SyncPlan.WATCHED_PERCENT -> titles.map { TraktPendingEntity(it.key, TraktAction.WATCHED, at) }
                        else -> emptyList()
                    }
                )
            }
        }
        if (queued) scope.launch { sendQueue() }
    }

    /** Whether a mark or a stop is queued for Trakt now. */
    private suspend fun keepsHistory(): Boolean = following?.invoke() ?: follows

    private suspend fun rowsOf(what: Marked): List<SyncRow> = when (what) {
        is Marked.Row -> dao.rowsOf(what.id)
        is Marked.Saga -> dao.rowsOfSaga(what.id)
    }

    /** Queues [entries] in place of what each title had waiting, in the order told; true once queued. */
    private suspend fun queued(entries: List<TraktPendingEntity>): Boolean =
        entries.isNotEmpty() && read("queuing") { queue(entries) } != null

    private suspend fun begin() {
        val saved = read("sync state") { state() }
        _state.update { it.shown(saved) }
        val at = saved?.syncedAt
        when {
            at == null -> request(SyncMode.UNION)
            now() - at >= STALE_MS -> request(SyncMode.CHECK)
            else -> sendQueue()
        }
    }

    /** Forgets the sync's state, and what waits unless [keepQueue], after the exchange running and a mark being queued. */
    private suspend fun disconnected(keepQueue: Boolean) {
        busy.withLock { local.withLock { read("forgetting") { if (keepQueue) clearState() else forget() } } }
        _state.update { TraktSyncState(running = it.running) }
    }

    private suspend fun sendQueue() = busy.withLock {
        if (!active) return@withLock
        val result = suspendRunCatching { send() }
        val saved = read("sync state") { state() } ?: TraktStateEntity()
        // A queue sent clears a failure; one that fails is said, the last sync's date kept.
        if (result.isFailure || result.getOrDefault(0) > 0 && saved.error != null) settle(saved, result.map { saved.copy(error = null) })
    }

    private fun request(mode: SyncMode) {
        if (!active) return
        synchronized(runs) {
            if (running) {
                again = maxOf(again ?: mode, mode)
                return
            }
            running = true
            _state.update { it.copy(running = true) }
        }
        scope.launch {
            var next: SyncMode? = mode
            try {
                while (next != null) {
                    syncOnce(next)
                    next = nextRun()
                }
            } finally {
                // Only when cut short: otherwise nextRun ended it, and a newer run may own it.
                if (next != null) synchronized(runs) { endRuns() }
            }
        }
    }

    private fun nextRun(): SyncMode? = synchronized(runs) {
        again.also {
            again = null
            if (it == null) endRuns()
        }
    }

    private fun endRuns() {
        running = false
        again = null
        _state.update { it.copy(running = false) }
    }

    private suspend fun syncOnce(mode: SyncMode) = busy.withLock {
        if (!active) return@withLock
        val saved = read("sync state") { state() } ?: TraktStateEntity()
        settle(saved, suspendRunCatching { exchange(mode, saved) })
    }

    /**
     * What waits goes first, so Trakt has the latest; then Trakt is read and applied, even when that failed, leaving
     * the titles still waiting as they are; then what Trakt lacks is sent, unless sending already failed.
     */
    private suspend fun exchange(mode: SyncMode, saved: TraktStateEntity): TraktStateEntity {
        var unsent = sendOrFailure()
        val activities = trakt(LAST_ACTIVITIES) { it.lastActivities() }.key()
        // Trakt answers: what it did not take counts against those titles.
        unsent?.let { counted(it) }
        if (mode == SyncMode.CHECK && saved.syncedAt != null && saved.activities == activities) {
            return saved.copy(syncedAt = now(), error = unsent?.let(::errorOf))
        }
        val movies = trakt(WATCHED_MOVIES) { it.watchedMovies() }
        val shows = trakt(WATCHED_SHOWS) { it.watchedShows() }
        val paused = trakt(PLAYBACK) { it.playback() }
        val rows = dao.rows()
        val played = rows.mapNotNullTo(HashSet()) { it.versionOf }.mapNotNullTo(HashSet()) { dao.playedCopy(it) }
        val union = mode == SyncMode.UNION || saved.syncedAt == null
        val plan = withContext(Dispatchers.Default) { SyncPlan.of(rows, movies.watched() + shows.watched(), paused, union, played) }
        local.withLock { dao.apply(plan.watched, plan.resumes, plan.titles) }
        if (plan.changes) changed()
        // Never in place of a mark waiting since the queue was sent.
        dao.queue(plan.send, replace = false)
        if (unsent == null) unsent = sendOrFailure()?.also { counted(it) }
        val watched = plan.watched.values.sumOf { it.size }
        Log.i(TAG, "Synced: $watched rows watched and ${plan.resumes.size} resumed from Trakt, ${plan.send.size} titles sent")
        return TraktStateEntity(
            syncedAt = now(),
            watched = plan.watchedCount,
            inProgress = plan.inProgressCount,
            error = unsent?.let(::errorOf),
            activities = activities,
        )
    }

    /** Sends what waits; a failure is logged and returned, unless Trakt ended the connection. */
    private suspend fun sendOrFailure(): Throwable? = suspendRunCatching { send() }.exceptionOrNull()?.also {
        if (it is TraktSignedOutException) throw it
        Log.w(TAG, "What waits could not be sent to Trakt: ${said(it)}")
    }

    /** A failed write counts against its titles, unless Trakt refused the account; those failed too often are dropped. */
    private suspend fun counted(failure: Throwable) {
        val unsent = failure as? Unsent ?: return
        val status = (unsent.cause as? HttpException)?.code()
        if (status != null && status in ACCOUNT) return
        val dropped = read("failed writes") { failed(unsent.tried, MAX_ATTEMPTS) } ?: return
        if (dropped > 0) Log.w(TAG, "Trakt failed $dropped titles $MAX_ATTEMPTS times: not sent again")
    }

    /** Saves where the sync stands after [result]: a failure is kept with the last sync; signed out, nothing is. */
    private suspend fun settle(saved: TraktStateEntity, result: Result<TraktStateEntity>) {
        val next = result.getOrElse { e ->
            if (e is TraktSignedOutException) return
            Log.w(TAG, "Trakt could not be synced: ${said(e)}")
            saved.copy(error = errorOf(e))
        }
        val stored = read("sync state") { saveState(next) } != null
        _state.update { it.shown(if (stored) next else next.copy(error = TraktSyncError.STORAGE)) }
    }

    /**
     * Sends what waits: watched titles in batches, played titles removed and paused ones dropped, stops one by one.
     * What Trakt refuses as such leaves the queue, a batch's titles only once each was tried alone; any other failure
     * stops the sending ([Unsent]), the rest kept.
     */
    private suspend fun send(): Int {
        val pending = dao.pending()
        if (pending.isEmpty()) return 0
        val titled = pending.mapNotNull { entry -> TraktTitle.parse(entry.title)?.let { entry to it } }
        dao.sent(pending - titled.map { it.first }.toSet())
        val byAction = titled.groupBy { it.first.action }
        val lookup = Lookup()
        byAction[TraktAction.WATCHED]?.let { watch(it, lookup) }
        unpause(byAction[TraktAction.UNWATCHED].orEmpty() + byAction[TraktAction.UNPAUSED].orEmpty(), lookup)
        byAction[TraktAction.UNPAUSED]?.let { dao.sent(it.map { (entry, _) -> entry }) }
        byAction[TraktAction.UNWATCHED]?.chunked(BATCH)?.forEach { history(it, remove = true) }
        byAction[TraktAction.PROGRESS]?.forEach { (entry, title) -> stop(entry, title, lookup) }
        return pending.size
    }

    /** Watched titles in batches; one sent before without an answer goes again only if Trakt lacks it, so no play doubles. */
    private suspend fun watch(entries: List<Queued>, lookup: Lookup) {
        val (reached, left) = entries.partition { (entry, title) ->
            val sentAt = entry.sentAt
            sentAt != null && lookup.watched(title, entry.at, since = sentAt)
        }
        dao.sent(reached.map { it.first })
        left.chunked(BATCH).forEach { history(it, remove = false) }
    }

    /** Adds [batch]'s plays at their dates, or removes every play; refused whole, each title is tried alone. */
    private suspend fun history(batch: List<Queued>, remove: Boolean) {
        val entries = batch.map { it.first }
        val items = historyOf(batch.map { (entry, title) -> title to entry.at }, dated = !remove)
        val taken = if (remove) {
            write(entries, HISTORY_REMOVE) { it.removeFromHistory(items) }
        } else {
            write(entries, HISTORY) { it.addToHistory(items) }
        }
        if (taken || batch.size == 1) dao.sent(entries) else batch.forEach { history(listOf(it), remove) }
    }

    /** Drops the pauses Trakt keeps of [entries]' titles; one already gone is no failure. */
    private suspend fun unpause(entries: List<Queued>, lookup: Lookup) {
        if (entries.isEmpty()) return
        val byTitle = entries.associate { (entry, title) -> title to entry }
        for (pause in lookup.paused()) {
            val entry = pause.title()?.let(byTitle::get) ?: continue
            pause.id?.let { id -> write(listOf(entry), PLAYBACK) { it.removePlayback(id) } }
        }
    }

    /**
     * A stop as Trakt's scrobble. One sent late is no longer now: watched, it goes to the history at its date, unless
     * Trakt saw the title watched since; paused, it goes unless Trakt paused or watched the title since.
     */
    private suspend fun stop(entry: TraktPendingEntity, title: TraktTitle, lookup: Lookup) {
        val percent = entry.progress ?: return dao.sent(listOf(entry))
        val late = now() - entry.at > LATE_MS
        val watched = percent >= SyncPlan.WATCHED_PERCENT
        when {
            late && (lookup.watched(title, entry.at) || !watched && lookup.paused(title, entry.at)) -> Unit
            late && watched -> write(listOf(entry), HISTORY) { it.addToHistory(historyOf(listOf(title to entry.at))) }
            else -> write(listOf(entry), SCROBBLE_STOP) { it.scrobbleStop(scrobbleOf(title, percent)) }
        }
        dao.sent(listOf(entry))
    }

    /**
     * Sends a write to [route] spaced from the last one, noting when [tried] went: true once Trakt took it, false when
     * it refused what it carries; any other failure is thrown as [Unsent].
     */
    private suspend fun write(tried: List<TraktPendingEntity>, route: String, request: suspend (TraktApi) -> Unit): Boolean {
        lastWrite?.let { delay(it + WRITE_GAP_MS - clock()) }
        // Noted before it goes: Trakt may take a write whose answer is lost, or whose process dies waiting.
        read("sending") { sending(tried, now()) }
        val failure = try {
            trakt(route, request)
            return true
        } catch (e: HttpException) {
            if (e.code() !in ACCOUNT && e.code() != TIMEOUT && e.code() < SERVER_ERRORS) {
                Log.w(TAG, "Trakt refused ${tried.size} titles (HTTP ${e.code()})")
                return false
            }
            e
        } catch (e: IOException) {
            e
        } finally {
            lastWrite = clock()
        }
        if (unreached(failure)) read("sending") { unsent(tried) }
        throw Unsent(tried, failure)
    }

    /** Calls Trakt on [route] ([waited]); a failure is kept with its route for the log ([said]). */
    private suspend fun <T> trakt(route: String, block: suspend (TraktApi) -> T): T =
        suspendRunCatching { waited(block) }.onFailure { failed = route to it }.getOrThrow()

    /** A failure as the log says it: the route it came from when a call to Trakt threw it, then what it is. */
    private fun said(e: Throwable): String {
        val failure = (e as? Unsent)?.cause ?: e
        val route = failed?.takeIf { it.second === failure }?.first
        return if (route == null) described(failure) else "$route, ${described(failure)}"
    }

    /** Calls Trakt, waiting out its limit ([RETRY_AFTER], else [X_RATELIMIT]'s end) a few times, never for long. */
    private suspend fun <T> waited(block: suspend (TraktApi) -> T): T {
        var waits = 0
        while (true) {
            try {
                return account.call(block)
            } catch (e: HttpException) {
                val wait = if (e.code() == HttpStatus.TOO_MANY_REQUESTS) waitOf(e.response()?.headers(), waits) else null
                if (wait == null || waits >= MAX_WAITS || wait > MAX_WAIT_MS) throw e
                Log.i(TAG, "Trakt asks to wait ${wait / MS_PER_S} s")
                waits++
                delay(wait)
            }
        }
    }

    private fun waitOf(headers: Headers?, waits: Int): Long {
        headers?.get(RETRY_AFTER)?.trim()?.toLongOrNull()?.let { return it * MS_PER_S }
        val until = headers?.get(X_RATELIMIT)?.let { UNTIL.find(it)?.groupValues?.get(1) }?.let(::millisOf)
        return until?.let { (it - now()).coerceAtLeast(MS_PER_S) } ?: (BACKOFF_MS shl waits)
    }

    /** Runs a database step; a failure is logged and read as null. */
    private suspend fun <T> read(what: String, step: suspend TraktDao.() -> T): T? =
        suspendRunCatching { dao.step() }.onFailure { Log.w(TAG, "Trakt's $what: ${described(it)}") }.getOrNull()

    /** What a send reads of Trakt to decide, each at most once and only when needed. */
    private inner class Lookup {
        private var activities: LastActivities? = null
        private var movies: Map<TraktTitle, Long>? = null
        private var shows: Map<TraktTitle, Long>? = null
        private var pauses: List<PlaybackProgress>? = null

        suspend fun paused(): List<PlaybackProgress> = pauses ?: trakt(PLAYBACK) { it.playback() }.also { pauses = it }

        /** Whether Trakt paused [title] after [at] (ms). */
        suspend fun paused(title: TraktTitle, at: Long): Boolean =
            paused().any { it.title() == title && (millisOf(it.pausedAt) ?: 0L) > at }

        /**
         * Whether Trakt has a play of [title] at [at] (ms) or later; its history is read only once its last activities
         * say it changed [since] then, give or take [CLOCK_SKEW_MS] of the device's clock.
         */
        suspend fun watched(title: TraktTitle, at: Long, since: Long = at): Boolean {
            val last = activities ?: trakt(LAST_ACTIVITIES) { it.lastActivities() }.also { activities = it }
            val movie = title is TraktTitle.Movie
            val changed = millisOf(if (movie) last.movies.watchedAt else last.episodes.watchedAt) ?: return false
            if (changed < since - CLOCK_SKEW_MS) return false
            val seen = if (movie) {
                movies ?: trakt(WATCHED_MOVIES) { it.watchedMovies() }.watched().also { movies = it }
            } else {
                shows ?: trakt(WATCHED_SHOWS) { it.watchedShows() }.watched().also { shows = it }
            }
            // Trakt keeps a play's date to the second.
            return (seen[title] ?: return false) >= at - MS_PER_S
        }
    }

    /** A write that failed, [tried] kept for later. */
    private class Unsent(val tried: List<TraktPendingEntity>, cause: Throwable) : Exception(cause)

    /** What changes Trakt's history or pauses, in one value. */
    private fun LastActivities.key() = listOf(movies.watchedAt, movies.pausedAt, episodes.watchedAt, episodes.pausedAt).joinToString("|")

    private fun TraktSyncState.shown(saved: TraktStateEntity?) = copy(
        syncedAt = saved?.syncedAt,
        watched = saved?.watched ?: 0,
        inProgress = saved?.inProgress ?: 0,
        error = saved?.error,
    )

    private companion object {
        const val TAG = "Trakt"
        const val MS_PER_S = 1_000L

        /** Trakt is read again at launch past this age. */
        const val STALE_MS = 6 * 60 * 60_000L

        /** Titles per history request. */
        const val BATCH = 100

        /** Trakt takes one write a second per account. */
        const val WRITE_GAP_MS = 1_000L

        /** A stop sent later than this did not just happen. */
        const val LATE_MS = 10 * 60_000L

        /** How far the device's clock may stand from Trakt's. */
        const val CLOCK_SKEW_MS = 10 * 60_000L

        /** Writes Trakt fails for a title, while answering reads, before the title is dropped. */
        const val MAX_ATTEMPTS = 5

        // Routes, as the log names them.
        const val LAST_ACTIVITIES = "sync/last_activities"
        const val WATCHED_MOVIES = "sync/watched/movies"
        const val WATCHED_SHOWS = "sync/watched/shows"
        const val PLAYBACK = "sync/playback"
        const val HISTORY = "sync/history"
        const val HISTORY_REMOVE = "sync/history/remove"
        const val SCROBBLE_STOP = "scrobble/stop"

        const val RETRY_AFTER = "Retry-After"
        const val X_RATELIMIT = "X-Ratelimit"
        val UNTIL = Regex(""""until"\s*:\s*"([^"]+)"""")
        const val MAX_WAITS = 3
        const val MAX_WAIT_MS = 60_000L
        const val BACKOFF_MS = 2_000L

        const val TIMEOUT = 408
        const val LOCKED = 423
        const val SERVER_ERRORS = 500

        /** Refusals of the account, not of the titles sent: kept for later, never counted against them. */
        val ACCOUNT = setOf(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, LOCKED, HttpStatus.TOO_MANY_REQUESTS)

        /** A write that never reached Trakt, or that Trakt refused before taking anything. */
        fun unreached(e: Exception): Boolean = when (e) {
            is HttpException -> e.code() in ACCOUNT
            else -> e is UnknownHostException || e is ConnectException || e is NoRouteToHostException || e is SSLHandshakeException
        }

        fun errorOf(e: Throwable): TraktSyncError {
            val failure = (e as? Unsent)?.cause ?: e
            return when ((failure as? HttpException)?.code()) {
                HttpStatus.TOO_MANY_REQUESTS -> TraktSyncError.BUSY
                LOCKED -> TraktSyncError.REFUSED
                else -> when (failure.failureKind()) {
                    FailureKind.UNREACHABLE, FailureKind.IO -> TraktSyncError.OFFLINE
                    FailureKind.CERTIFICATE -> TraktSyncError.CERTIFICATE
                    FailureKind.REFUSED -> TraktSyncError.REFUSED
                    FailureKind.UNEXPECTED_ANSWER -> TraktSyncError.UNREADABLE
                    FailureKind.STORAGE_FULL, FailureKind.STORAGE_FAILED -> TraktSyncError.STORAGE
                    else -> TraktSyncError.FAILED
                }
            }
        }

        /** What a mark changes on Trakt, one intent per title: a play added or every play removed, else its pause dropped. */
        fun intentsOf(rows: List<SyncRow>, played: Boolean, at: Long): List<TraktPendingEntity> = rows
            .flatMap { row ->
                val action = when {
                    row.played != played -> if (played) TraktAction.WATCHED else TraktAction.UNWATCHED
                    // A position the mark resets: a title in progress unmarked, or one watched again marked watched.
                    row.positionMs > 0 -> TraktAction.UNPAUSED
                    else -> null
                }
                action?.let { a -> row.titles().map { it.key to a } }.orEmpty()
            }
            .groupBy({ it.first }, { it.second })
            // A copy's play outweighs another's pause.
            .map { (title, actions) -> TraktPendingEntity(title, actions.minBy { it == TraktAction.UNPAUSED }, at) }
    }
}
