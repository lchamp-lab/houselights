package app.houselights.providers.local

import android.util.Log
import app.houselights.core.TmdbKeyRefusedException
import app.houselights.core.TmdbUnreachableException
import app.houselights.core.suspendRunCatching
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/** TMDB answered with a 5xx or an unparsable body; reported as unreachable, the closest UI message. */
internal class TmdbFailedException(cause: Throwable) : TmdbUnreachableException(cause, "TMDB failed")

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

    /** True while there is a client and the pass has not stopped. */
    val open: Boolean get() = client != null && !stopped

    /**
     * [issue] when the pass ends on it: stopped, or TMDB not answering since its last failure. Null once TMDB answered
     * after it, back: what a moment out of reach left unanswered is asked again by the next pass.
     */
    val endIssue: Throwable? get() = issue?.takeIf { stopped || failures > 0 }

    suspend fun <T : Any> ask(question: suspend (TmdbClient) -> T?): TmdbAnswer<T> {
        val tmdb = client?.takeIf { !stopped } ?: return TmdbAnswer.Unanswered
        if (strict) return TmdbAnswer.Given(question(tmdb)).also { answered = true }
        return suspendRunCatching { question(tmdb) }.fold(
            onSuccess = {
                failures = 0
                answered = true
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
