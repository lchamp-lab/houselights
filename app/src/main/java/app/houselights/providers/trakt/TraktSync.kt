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

/**
 * Keeps the folders' history and the Trakt account's together, in the background. Connecting joins both sides
 * ([SyncMode.UNION]); then each mark and each stop goes to Trakt at once, queued while Trakt cannot be reached and
 * sent at the next occasion (launch, end of a scan, a sync, a stop). Trakt is read again at launch after
 * [STALE_MS] and at the end of each scan, only when it changed unless the scan found titles. One exchange at a time;
 * Trakt's limits are waited out ([RETRY_AFTER]), its writes spaced by [WRITE_GAP_MS].
 */
class TraktSync(
    private val account: TraktAccount,
    private val dao: TraktDao,
    private val scope: CoroutineScope,
    /** Tells screens a sync changed the library. */
    private val changed: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    /** A monotonic clock (ms) that spaces Trakt's writes. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : TraktSyncing, WatchHistory {
    private val _state = MutableStateFlow(TraktSyncState())
    override val state: StateFlow<TraktSyncState> = _state

    /** One exchange with Trakt at a time: a sync, or the queue. */
    private val busy = Mutex()

    /** Connected with the folders as the source: only then is anything read, queued or sent. */
    @Volatile
    private var active = false

    /** Sync requests: one running, at most one more waiting, the larger [SyncMode] kept. Guarded by [runs]. */
    private val runs = Any()
    private var running = false
    private var again: SyncMode? = null

    private var lastWrite: Long? = null

    /** The account and the source as the settings have them: a connection starts, a disconnection forgets the queue. */
    fun follow(connected: Boolean, folders: Boolean) {
        active = connected && folders
        when {
            !connected -> scope.launch { disconnected() }
            folders -> scope.launch { begin() }
        }
    }

    /** A scan ended: Trakt is read for the titles it found, else only if Trakt changed. */
    fun scanned(changedLibrary: Boolean) = request(if (changedLibrary) SyncMode.PULL else SyncMode.CHECK)

    override fun syncNow() = request(SyncMode.UNION)

    override suspend fun mark(what: Marked, played: Boolean, write: suspend () -> Unit) {
        if (!active) return write()
        val at = now()
        val before = read("rows marked") { rowsOf(what) }
        write()
        // Only the titles the mark changes: marking a show watched adds no play to what Trakt already has.
        val touched = before.orEmpty().filter { if (played) !it.played else it.played || it.positionMs > 0 }
        val action = if (played) TraktAction.WATCHED else TraktAction.UNWATCHED
        enqueue(touched.flatMap { it.titles() }.distinct().map { TraktPendingEntity(it.key, action, at) })
    }

    override suspend fun stopped(id: Long, percent: Float) {
        if (!active || percent < SyncPlan.MIN_PERCENT) return
        val at = now()
        val titles = read("row stopped") { rowsOf(Marked.Row(id)).filter { it.id == id } }.orEmpty().flatMap { it.titles() }
        enqueue(
            when {
                titles.size == 1 -> listOf(TraktPendingEntity(titles.single().key, TraktAction.PROGRESS, at, percent))
                // A file holding several episodes is watched or not: Trakt pauses one episode at a time.
                percent >= SyncPlan.WATCHED_PERCENT -> titles.map { TraktPendingEntity(it.key, TraktAction.WATCHED, at) }
                else -> emptyList()
            }
        )
    }

    private suspend fun rowsOf(what: Marked): List<SyncRow> = when (what) {
        is Marked.Row -> dao.rowsOf(what.id)
        is Marked.Saga -> dao.rowsOfSaga(what.id)
    }

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

    private suspend fun disconnected() {
        busy.withLock { read("forgetting") { forget() } }
        _state.update { TraktSyncState(running = it.running) }
    }

    /** Queues [entries] in place of what each title had waiting, in the order told, then sends the queue. */
    private suspend fun enqueue(entries: List<TraktPendingEntity>) {
        if (entries.isEmpty()) return
        if (read("queuing") { queue(entries) } != null) scope.launch { sendQueue() }
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

    /** The queue first, so Trakt has the latest; then Trakt read and applied, and what it lacks sent. */
    private suspend fun exchange(mode: SyncMode, saved: TraktStateEntity): TraktStateEntity {
        send()
        val activities = trakt { it.lastActivities() }.key()
        if (mode == SyncMode.CHECK && saved.syncedAt != null && saved.activities == activities) {
            return saved.copy(syncedAt = now(), error = null)
        }
        val movies = trakt { it.watchedMovies() }
        val shows = trakt { it.watchedShows() }
        val paused = trakt { it.playback() }
        val rows = dao.rows()
        val plan = withContext(Dispatchers.Default) {
            SyncPlan.of(rows, movies.watched() + shows.watched(), paused, union = mode == SyncMode.UNION)
        }
        dao.apply(plan.watched, plan.resumes)
        if (plan.changes) changed()
        // Never in place of a mark waiting since the queue was sent.
        dao.queue(plan.send, replace = false)
        send()
        val watched = plan.watched.values.sumOf { it.size }
        Log.i(TAG, "Synced: $watched rows watched and ${plan.resumes.size} resumed from Trakt, ${plan.send.size} titles sent")
        return TraktStateEntity(syncedAt = now(), watched = plan.watchedCount, inProgress = plan.inProgressCount, activities = activities)
    }

    /** Saves where the sync stands after [result]: a failure is kept with the last sync; signed out, nothing is. */
    private suspend fun settle(saved: TraktStateEntity, result: Result<TraktStateEntity>) {
        val next = result.getOrElse { e ->
            if (e is TraktSignedOutException) return
            Log.w(TAG, "Trakt could not be synced: ${described(e)}")
            saved.copy(error = errorOf(e))
        }
        val stored = read("sync state") { saveState(next) } != null
        _state.update { it.shown(if (stored) next else next.copy(error = TraktSyncError.STORAGE)) }
    }

    /**
     * Sends what waits for Trakt: watched titles in batches, unmarked ones with their paused playbacks, stops one by
     * one. What Trakt refuses as such ([KEPT] aside) is dropped; anything else stops the sending, the rest kept.
     */
    private suspend fun send(): Int {
        val pending = dao.pending()
        if (pending.isEmpty()) return 0
        val titled = pending.mapNotNull { entry -> TraktTitle.parse(entry.title)?.let { entry to it } }
        dao.sent(pending - titled.map { it.first }.toSet())
        val byAction = titled.groupBy { it.first.action }
        byAction[TraktAction.WATCHED]?.chunked(BATCH)?.forEach { batch ->
            write(batch.map { it.first }) { it.addToHistory(historyOf(batch.map { (entry, title) -> title to entry.at })) }
        }
        byAction[TraktAction.UNWATCHED]?.let { unwatch(it) }
        byAction[TraktAction.PROGRESS]?.forEach { (entry, title) -> stop(entry, title) }
        return pending.size
    }

    private suspend fun unwatch(entries: List<Pair<TraktPendingEntity, TraktTitle>>) {
        val titles = entries.map { it.second }.toSet()
        trakt { it.playback() }.filter { it.title() in titles }.forEach { paused -> write(emptyList()) { it.removePlayback(paused.id) } }
        entries.chunked(BATCH).forEach { batch ->
            write(batch.map { it.first }) { it.removeFromHistory(historyOf(batch.map { (_, title) -> title to null }, dated = false)) }
        }
    }

    /** A stop as Trakt's scrobble; one replayed late and watched goes to the history at its date, as a scrobble is dated now. */
    private suspend fun stop(entry: TraktPendingEntity, title: TraktTitle) {
        val percent = entry.progress ?: return dao.sent(listOf(entry))
        if (percent >= SyncPlan.WATCHED_PERCENT && now() - entry.at > LATE_MS) {
            write(listOf(entry)) { it.addToHistory(historyOf(listOf(title to entry.at))) }
        } else {
            write(listOf(entry)) { it.scrobbleStop(scrobbleOf(title, percent)) }
        }
    }

    /** Sends a write, spaced from the last one, then takes [done] off the queue. */
    private suspend fun write(done: List<TraktPendingEntity>, request: suspend (TraktApi) -> Any) {
        lastWrite?.let { delay(it + WRITE_GAP_MS - clock()) }
        try {
            trakt(request)
        } catch (e: HttpException) {
            if (e.code() in KEPT || e.code() >= SERVER_ERRORS) throw e
            Log.w(TAG, "Trakt refused ${done.size} titles (HTTP ${e.code()}): not sent again")
        } finally {
            lastWrite = clock()
        }
        dao.sent(done)
    }

    /** Calls Trakt, waiting out its limit ([RETRY_AFTER], else [X_RATELIMIT]'s end) a few times, never for long. */
    private suspend fun <T> trakt(block: suspend (TraktApi) -> T): T {
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

        /** A stop sent later than this is no longer one that just happened. */
        const val LATE_MS = 10 * 60_000L

        const val RETRY_AFTER = "Retry-After"
        const val X_RATELIMIT = "X-Ratelimit"
        val UNTIL = Regex(""""until"\s*:\s*"([^"]+)"""")
        const val MAX_WAITS = 3
        const val MAX_WAIT_MS = 60_000L
        const val BACKOFF_MS = 2_000L

        const val SERVER_ERRORS = 500

        /** Refusals of the account, not of the titles sent: kept for later. */
        val KEPT = setOf(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, 408, 423, HttpStatus.TOO_MANY_REQUESTS)

        fun errorOf(e: Throwable): TraktSyncError = when {
            e is HttpException && e.code() == HttpStatus.TOO_MANY_REQUESTS -> TraktSyncError.BUSY
            else -> when (e.failureKind()) {
                FailureKind.UNREACHABLE, FailureKind.IO, FailureKind.CERTIFICATE -> TraktSyncError.OFFLINE
                FailureKind.STORAGE_FULL, FailureKind.STORAGE_FAILED -> TraktSyncError.STORAGE
                else -> TraktSyncError.FAILED
            }
        }

        /** A failure for the log: its type and status, never its message, which may quote an answer. */
        fun described(e: Throwable): String = if (e is HttpException) "HTTP ${e.code()}" else e.javaClass.simpleName
    }
}
