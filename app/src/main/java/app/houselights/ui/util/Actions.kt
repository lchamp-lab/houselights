package app.houselights.ui.util

import android.util.Log
import app.houselights.core.FailureKind
import app.houselights.core.failureKind
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs a write the viewer asked for in this scope; a failure is logged under [tag] and passed to [onError]
 * through [errorOf] instead of crashing, as screen and ViewModel scopes have no handler. Cancellation is not
 * a failure.
 */
fun CoroutineScope.launchAction(
    tag: String,
    what: String,
    onError: (UiError) -> Unit = {},
    errorOf: (Throwable) -> UiError = Throwable::toUiError,
    block: suspend CoroutineScope.() -> Unit,
): Job = launch {
    suspendRunCatching { block() }.onFailure { e ->
        Log.w(tag, "$what failed", e)
        onError(errorOf(e))
    }
}

/**
 * The [UiError] for a failed local write: storage, I/O, network and missing-file failures are all
 * [UiError.NOT_SAVED]; other database failures (a constraint, a lock) map as usual.
 */
fun Throwable.toWriteError(): UiError = when (val kind = failureKind()) {
    FailureKind.STORAGE_FULL, FailureKind.STORAGE_FAILED -> UiError.NOT_SAVED
    FailureKind.IO, FailureKind.UNREACHABLE, FailureKind.CERTIFICATE, FailureKind.MISSING -> UiError.NOT_SAVED
    else -> kind.toUiError()
}
