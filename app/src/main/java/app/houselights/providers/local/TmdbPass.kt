package app.houselights.providers.local

import android.util.Log
import app.houselights.core.suspendRunCatching

/** What TMDB said to a question: its answer (null: it has no such entry), or nothing, as it could not be asked. */
internal sealed interface TmdbAnswer<out T> {
    data class Given<out T>(val value: T?) : TmdbAnswer<T>

    data object Unanswered : TmdbAnswer<Nothing>
}

/** The answer given, or null when TMDB had none or could not be asked. */
internal val <T> TmdbAnswer<T>.value: T? get() = (this as? TmdbAnswer.Given)?.value

/**
 * TMDB as one scan pass asks it: optional. A question it does not answer leaves the file to its NFO
 * and local artwork, unmatched, so that a later pass asks again. Once it refuses the key, or fails
 * [MAX_FAILURES] times in a row, the pass asks no more; [issue] says why from the first failure.
 * A question the scan's client did not even ask, paused a minute after the network failed, is no
 * failure of its own: TMDB is asked again once the pause is over.
 *
 * [strict]: what the user asked for (an identification), which TMDB must answer: a failure throws,
 * before anything is written.
 */
internal class TmdbPass(
    val client: TmdbClient?,
    private val strict: Boolean = false,
    /** Told when [issue] is set. */
    private val onIssue: (Throwable) -> Unit = {},
) {
    /**
     * Why TMDB left questions of the pass unanswered, from its first failure on; null while it
     * answered them all. A key refused takes the place of a failure told before: it is what the
     * user is to change.
     */
    @Volatile
    var issue: Throwable? = null
        private set

    /** Failures in a row. */
    private var failures = 0

    /** True once the pass asks no more: the key refused, or [MAX_FAILURES] failures in a row. */
    @Volatile
    private var stopped = false

    /** True while TMDB is asked: a key, and the pass not stopped. */
    val open: Boolean get() = client != null && !stopped

    suspend fun <T : Any> ask(question: suspend (TmdbClient) -> T?): TmdbAnswer<T> {
        val tmdb = client?.takeIf { !stopped } ?: return TmdbAnswer.Unanswered
        if (strict) return TmdbAnswer.Given(question(tmdb))
        return suspendRunCatching { question(tmdb) }.fold(
            onSuccess = {
                failures = 0
                TmdbAnswer.Given(it)
            },
            onFailure = { e ->
                failed(e)
                TmdbAnswer.Unanswered
            },
        )
    }

    private fun failed(e: Throwable) {
        // Not asked: the scan's client waits out a failure of a moment ago, counted then.
        if (e is TmdbPausedException) return
        Log.w(TAG, "TMDB did not answer; asked again at the next scan", e)
        val refused = e is TmdbKeyRefusedException
        if (!refused && e !is TmdbUnreachableException) return
        if (refused || ++failures >= MAX_FAILURES) stopped = true
        if (issue == null || refused) {
            issue = e
            onIssue(e)
        }
    }

    private companion object {
        const val TAG = "TmdbPass"
        const val MAX_FAILURES = 3
    }
}
