package app.houselights.providers.local

import android.util.Log
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What the NAS and TMDB answered for one piece of a pass's work (a file, a title asked again, a show's episodes placed),
 * by request: a file's NFO, header or artwork ([ScanFiles]), a TMDB request by what it asks ([TmdbPass.ask]).
 */
internal class Collected private constructor(private val answers: Map<Any, Any?>) {
    constructor() : this(emptyMap())

    operator fun contains(key: Any): Boolean = key in answers

    /** The answer to [key], which [contains] it: of the type its request gives. */
    @Suppress("UNCHECKED_CAST") // Each key is answered by its own request, whose type it stands for.
    fun <T> answer(key: Any): T = answers[key] as T

    operator fun plus(answer: Pair<Any, Any?>): Collected = Collected(answers + answer)
}

/**
 * A request that the work running under the lock made, which [Collected] does not answer: [ask] asks it, never under
 * the lock. [overTmdb] for one of TMDB's.
 */
internal class Uncollected(val key: Any, val overTmdb: Boolean, val ask: suspend () -> Any?) : Exception() {
    override val message: String get() = "Not collected: $key"

    // Thrown to roll a run back, once per request: no stack to fill.
    override fun fillInStackTrace(): Throwable = this
}

/**
 * One run of a piece of work under the lock ([Rounds]): its answers ([collected]), and whether the pass's TMDB ([tmdb])
 * has stopped as the requests it replays left it ([tmdbStopped], [TmdbPass.open]). Read on the thread the run is on.
 */
internal class Applying(val collected: Collected, val tmdb: TmdbPass?, var tmdbStopped: Boolean) {
    companion object {
        private val running = ThreadLocal<Applying?>()

        /** The run under way on this thread; null outside one, where requests are asked at once. */
        fun current(): Applying? = running.get()

        /** What a coroutine's context holds for [run] to be [current] on whichever thread it runs. */
        fun element(run: Applying) = running.asContextElement(run)
    }
}

/** [key]'s answer: [Collected]'s within a run under the lock ([Applying]), else [ask]'s at once. */
internal suspend fun <T> collected(key: Any, ask: suspend () -> T): T {
    val run = Applying.current() ?: return ask()
    if (key in run.collected) return run.collected.answer(key)
    throw Uncollected(key, overTmdb = false, ask)
}

/** This result, unless it failed for a request not collected yet ([Uncollected]): that one rolls the run back. */
internal fun <T> Result<T>.uncollectedThrown(): Result<T> = onFailure { if (it is Uncollected) throw it }

/** What a pass holds in memory across its pieces of work: [mark] saves it, the function it returns puts it back. */
internal fun interface PassState {
    fun mark(): () -> Unit
}

/**
 * Runs a piece of a pass's work under [lock], in one transaction, the NAS and TMDB answered from what was collected
 * ([Collected]); the lock is held only while the library is read and written. A request not collected yet rolls the
 * run back ([Uncollected]), with what [PassState] holds in memory, is asked with the lock released, and the work runs
 * again from the library as it then is: a user's choice made meanwhile is read by the work itself, which asks what
 * that choice needs, so an answer collected for another state is never written.
 */
internal class Rounds(private val dao: LibraryDao, private val lock: Mutex) {
    /** A request asked alongside the work's first TMDB request ([run]): a film's header, read as TMDB answers. */
    class Aside(val key: Any, val ask: suspend () -> Any?)

    /**
     * [work]'s result, the requests asked through [tmdb] (the pass's) replayed as they were answered; [aside] is asked
     * as the work's first TMDB request is, so the NAS and TMDB answer at once.
     */
    suspend fun <T> run(tmdb: TmdbPass?, state: PassState, aside: Aside? = null, work: suspend () -> T): T = coroutineScope {
        val stopped = tmdb?.client != null && !tmdb.open
        var collected = Collected()
        var last: Any? = null
        // Its failure fails the work only once the work needs it.
        var reading: Deferred<Result<Any?>>? = null
        try {
            repeat(MAX_ROUNDS) {
                val missing = lock.withLock {
                    val restore = state.mark()
                    var done = false
                    try {
                        var result: Any? = null
                        withContext(Applying.element(Applying(collected, tmdb, stopped))) {
                            dao.inTransaction { result = work() }
                        }
                        done = true
                        @Suppress("UNCHECKED_CAST") // The work's, set by the transaction that ended without throwing.
                        return@coroutineScope result as T
                    } catch (e: Uncollected) {
                        e
                    } finally {
                        if (!done) restore()
                    }
                }
                val read = reading
                val answer = when {
                    aside != null && missing.key == aside.key -> if (read != null) read.await().getOrThrow() else aside.ask()
                    else -> {
                        if (aside != null && missing.overTmdb && read == null) reading = async { suspendRunCatching { aside.ask() } }
                        missing.ask()
                    }
                }
                collected += missing.key to answer
                last = missing.key
            }
            Log.w(TAG, "Still asking the NAS or TMDB after $MAX_ROUNDS requests, the last $last: left for the next pass")
            error("Still asking after $MAX_ROUNDS requests")
        } finally {
            reading?.cancel()
        }
    }

    private companion object {
        const val TAG = "Rounds"

        /** More requests than a piece of work makes: a show's every season and orders, its first episode's. */
        const val MAX_ROUNDS = 256
    }
}
