package app.houselights.providers.local

import android.util.Log
import app.houselights.core.TmdbKeyRefusedException
import app.houselights.core.TmdbUnreachableException
import app.houselights.core.suspendRunCatching
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/** TMDB answered with a 5xx or an unparsable body; reported as unreachable, the closest UI message. */
internal class TmdbFailedException(cause: Throwable) : TmdbUnreachableException(cause, "TMDB failed")

/** What a TMDB request asks ([TmdbPass.ask]), by which it is collected ([Collected]): [what], a season say, and its [terms]. */
internal data class TmdbKey(val what: String, val terms: Any?)

/** A TMDB result: [Given] (null value when TMDB has no such entry) or [Unanswered]. */
internal sealed interface TmdbAnswer<out T> {
    data class Given<out T>(val value: T?) : TmdbAnswer<T>

    data object Unanswered : TmdbAnswer<Nothing>
}

/** The given value; null when TMDB had none or did not answer. */
internal val <T> TmdbAnswer<T>.value: T? get() = (this as? TmdbAnswer.Given)?.value

/**
 * Optional TMDB access for one scan pass. An unanswered request leaves the file unmatched, indexed from NFO and
 * local artwork, for a later pass. A refused key or [MAX_FAILURES] consecutive failures stop the pass's requests;
 * [issue] keeps the reason. Requests skipped by the client's pause ([TmdbPausedException]) are not failures.
 * [strict] (user identification) makes any failure throw before anything is written.
 */
internal class TmdbPass(
    val client: TmdbClient?,
    private val strict: Boolean = false,
    private val onIssue: (Throwable) -> Unit = {},
) {
    /** The first failure of the pass; null while all answered. A refused key overrides it, as the user must fix it. */
    @Volatile
    var issue: Throwable? = null
        private set

    /** True once TMDB answered in this pass, i.e. it is back, whatever earlier passes saw. */
    @Volatile
    var answered = false
        private set

    /** Consecutive failures: those since TMDB last answered. */
    @Volatile
    private var failures = 0

    /** Set when the key is refused or after [MAX_FAILURES] consecutive failures. */
    @Volatile
    private var stopped = false

    /** True while there is a client and the pass has not stopped, as far as a run under the lock replays it ([replayed]). */
    val open: Boolean get() = client != null && !(replayed()?.tmdbStopped ?: stopped)

    /**
     * [issue] when the pass ends on it: stopped, or TMDB not answering since its last failure. Null once TMDB answered
     * after it, back: what a moment out of reach left unanswered is asked again by the next pass.
     */
    val endIssue: Throwable? get() = issue?.takeIf { stopped || failures > 0 }

    /**
     * TMDB's answer to [question], [key] saying what it asks: within a run under the scan's lock ([Rounds]), the answer
     * collected for that key, the pass stopping or not as it did once it was asked; none collected, [Uncollected]. A
     * request without a key is never asked there.
     */
    suspend fun <T : Any> ask(key: TmdbKey? = null, question: suspend (TmdbClient) -> T?): TmdbAnswer<T> {
        val run = replayed() ?: return asked(question)
        checkNotNull(key) { "TMDB asked under the lock" }
        if (key in run.collected) {
            val asked = run.collected.answer<Asked<T>>(key)
            run.tmdbStopped = asked.stopped
            return asked.answer
        }
        if (client == null || run.tmdbStopped) return TmdbAnswer.Unanswered
        throw Uncollected(key, overTmdb = true) { Asked(asked(question), stopped) }
    }

    /** An answer collected ([ask]), and whether the pass had stopped once it came. */
    private class Asked<T>(val answer: TmdbAnswer<T>, val stopped: Boolean)

    /** The run under the lock this pass's requests are answered in; never another pass's, which would ask under the lock. */
    private fun replayed(): Applying? = Applying.current()?.also { check(it.tmdb === this) { "TMDB asked under the lock" } }

    private suspend fun <T : Any> asked(question: suspend (TmdbClient) -> T?): TmdbAnswer<T> {
        val tmdb = client?.takeIf { !stopped } ?: return TmdbAnswer.Unanswered
        if (strict) return TmdbAnswer.Given(question(tmdb)).also { answered = true }
        val before = tmdb.answers
        return suspendRunCatching { question(tmdb) }.fold(
            onSuccess = {
                // Answered from what the pass kept ([MovieIndexer.FilmCache]), TMDB not asked: it says nothing of TMDB.
                if (tmdb.answers != before) {
                    failures = 0
                    answered = true
                }
                TmdbAnswer.Given(it)
            },
            onFailure = { e ->
                failed(e)
                TmdbAnswer.Unanswered
            },
        )
    }

    private fun failed(e: Throwable) {
        // Skipped during the client's pause; the original failure was already counted.
        if (e is TmdbPausedException) return
        Log.w(TAG, "TMDB did not answer; asked again at the next scan", e)
        val refused = e is TmdbKeyRefusedException
        val failure = when {
            refused || e is TmdbUnreachableException -> e
            // Would hit every next request too, leaving the library unmatched without a word: report it.
            e is HttpException && e.code() in SERVER_ERRORS || e is SerializationException -> TmdbFailedException(e)
            // A request-specific error (e.g. 4xx): affects this file only.
            else -> return
        }
        if (refused || ++failures >= MAX_FAILURES) stopped = true
        if (issue == null || refused) {
            issue = failure
            onIssue(failure)
        }
    }

    private companion object {
        const val TAG = "TmdbPass"
        const val MAX_FAILURES = 3
        val SERVER_ERRORS = 500..599
    }
}
