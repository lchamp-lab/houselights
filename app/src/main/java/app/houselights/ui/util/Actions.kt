package app.houselights.ui.util

import android.database.sqlite.SQLiteException
import android.util.Log
import app.houselights.core.SecretsUnavailableException
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

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
 * The [UiError] a write to the device itself shows as: an IOException there that names nothing else
 * (TMDB asked on the way) is its storage, not the network; the database full or failing is said the
 * same way.
 */
fun Throwable.toWriteError(): UiError = when {
    this is SecretsUnavailableException -> UiError.KEYSTORE
    this is SQLiteException -> UiError.NOT_SAVED
    this is IOException && toUiError() == UiError.OFFLINE -> UiError.NOT_SAVED
    else -> toUiError()
}
