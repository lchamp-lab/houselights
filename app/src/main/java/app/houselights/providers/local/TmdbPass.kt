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
 * and local artwork, unmatched, so that a later pass asks again. Once it refuses the key, or cannot
 * be reached [MAX_UNREACHABLE] times in a row, the pass asks no more, and [issue] says why.
 *
 * [strict]: what the user asked for (an identification), which TMDB must answer: a failure throws,
 * before anything is written.
 */
internal class TmdbPass(
    val client: TmdbClient?,
    private val strict: Boolean = false,
    /** Told once, when the pass stops asking. */
    private val onIssue: (Throwable) -> Unit = {},
) {
    /** Why the pass stopped asking TMDB; null while it asks. */
    @Volatile
    var issue: Throwable? = null
        private set

    private var unreachable = 0

    /** True while TMDB is asked: a key, and no [issue]. */
    val open: Boolean get() = client != null && issue == null

    suspend fun <T : Any> ask(question: suspend (TmdbClient) -> T?): TmdbAnswer<T> {
        val tmdb = client?.takeIf { issue == null } ?: return TmdbAnswer.Unanswered
        if (strict) return TmdbAnswer.Given(question(tmdb))
        return suspendRunCatching { question(tmdb) }.fold(
            onSuccess = {
                unreachable = 0
                TmdbAnswer.Given(it)
            },
            onFailure = { e ->
                failed(e)
                TmdbAnswer.Unanswered
            },
        )
    }

    private fun failed(e: Throwable) {
        Log.w(TAG, "TMDB did not answer; asked again at the next scan", e)
        val stop = e is TmdbKeyRefusedException || (e is TmdbUnreachableException && ++unreachable >= MAX_UNREACHABLE)
        if (stop && issue == null) {
            issue = e
            onIssue(e)
        }
    }

    private companion object {
        const val TAG = "TmdbPass"
        const val MAX_UNREACHABLE = 3
    }
}
