package app.houselights.ui.util

import android.util.Log
import app.houselights.core.FailureKind
import app.houselights.core.failureKind
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs a write the user asked for from a screen (a setting, a folder, the storage's upkeep) in this
 * scope. A failure (a full disk, the Keystore at fault, the database) is logged under [tag], as
 * [what] failed, and handed to [onError] as [errorOf] says it: never left to take the app down, as a
 * screen's scope or a ViewModel's has no handler. A cancellation is not a failure.
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
 * The [UiError] a write to the device itself shows as: a read or a write that failed there, and
 * names nothing else (TMDB asked on the way), is its storage, not the network, whatever the network
 * would make of it; the database full or its disk failing is said the same way. Any other database
 * failure (a constraint, a lock) is no reason to free space.
 */
fun Throwable.toWriteError(): UiError = when (val kind = failureKind()) {
    FailureKind.STORAGE_FULL, FailureKind.STORAGE_FAILED -> UiError.NOT_SAVED
    FailureKind.IO, FailureKind.UNREACHABLE, FailureKind.CERTIFICATE, FailureKind.MISSING -> UiError.NOT_SAVED
    else -> kind.toUiError()
}
