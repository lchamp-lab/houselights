package app.houselights.ui.util

import android.util.Log
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Something a screen loads: still coming, there, or why it isn't. */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>

    /** [value], shown; [refreshing] while it is read again. */
    data class Ready<T>(val value: T, val refreshing: Boolean = false) : LoadState<T>

    /** Why it isn't there; [retrying] while it is read again, the error shown meanwhile with its button busy. */
    data class Failed(val error: UiError, val retrying: Boolean = false) : LoadState<Nothing>
}

/** The value loaded, or null while it loads or when it failed. */
val <T> LoadState<T>.valueOrNull: T? get() = (this as? LoadState.Ready)?.value

val LoadState<*>.errorOrNull: UiError? get() = (this as? LoadState.Failed)?.error

/** Reloading: a shown error gives way at once; a shown value stays until the new one comes. */
fun <T> LoadState<T>.reloading(): LoadState<T> = if (this is LoadState.Ready) this else LoadState.Loading

/** Read again with what shows kept: a value [LoadState.Ready.refreshing], an error [LoadState.Failed.retrying]. */
fun <T> LoadState<T>.readingAgain(): LoadState<T> = when (this) {
    is LoadState.Ready -> copy(refreshing = true)
    is LoadState.Failed -> copy(retrying = true)
    LoadState.Loading -> LoadState.Loading
}

/** What shows once [loaded] comes: it, unless it failed where a value showed, which then stays. */
fun <T> LoadState<T>.after(loaded: LoadState<T>): LoadState<T> =
    if (loaded is LoadState.Failed && this is LoadState.Ready) copy(refreshing = false) else loaded

inline fun <T, R> LoadState<T>.map(transform: (T) -> R): LoadState<R> = when (this) {
    is LoadState.Ready -> LoadState.Ready(transform(value), refreshing)
    is LoadState.Failed -> this
    LoadState.Loading -> LoadState.Loading
}

/** [load]'s value, or its failure logged under [tag]; cancellation propagates, as a newer load took over. */
inline fun <T> loadState(tag: String, what: String, load: () -> T): LoadState<T> =
    suspendRunCatching { load() }.fold({ LoadState.Ready(it) }, { LoadState.Failed(it.reported(tag, what)) })

/** Logs this failure under [tag] as [what] failed, and returns how a screen shows it ([toUiError]). */
fun Throwable.reported(tag: String, what: String): UiError {
    Log.w(tag, "$what failed", this)
    return toUiError()
}

/** The latest of one kind of work in [scope]: launching cancels the previous, so an older answer never wins. */
class LatestJob(private val scope: CoroutineScope) {
    private var job: Job? = null

    /** True while the latest one runs. */
    val isActive: Boolean get() = job?.isActive == true

    fun launch(block: suspend CoroutineScope.() -> Unit): Job {
        job?.cancel()
        return scope.launch(block = block).also { job = it }
    }

    fun cancel() {
        job?.cancel()
    }
}
