package app.houselights.ui.util

import androidx.annotation.StringRes
import androidx.compose.runtime.staticCompositionLocalOf
import app.houselights.R
import app.houselights.core.FailureKind
import app.houselights.core.failureKind

/** What fixes an error, named on its button. Screens try again themselves; the others lead elsewhere. */
enum class ErrorAction(@StringRes val label: Int) {
    RETRY(R.string.retry),

    /** Sign in to the server again. */
    RECONNECT(R.string.error_action_reconnect),

    /** Settings, where a TMDB key is added or changed. */
    ADD_TMDB_KEY(R.string.error_action_add_tmdb_key),
    EDIT_TMDB_KEY(R.string.error_action_edit_tmdb_key),
}

/**
 * Leads where an error is fixed, for every action but [ErrorAction.RETRY]: provided once with the
 * navigation, so any screen that shows an error offers what fixes it.
 */
val LocalErrorFix = staticCompositionLocalOf<(ErrorAction) -> Unit> { {} }

/**
 * Why a screen could not show or do what was asked, in words people can act on: what happened
 * ([title]), what to check ([message]), and what fixes it, when something does ([action]).
 */
enum class UiError(@StringRes val title: Int, @StringRes val message: Int, val action: ErrorAction?) {
    /** The library's server or NAS does not answer. */
    OFFLINE(R.string.error_offline_title, R.string.error_offline, ErrorAction.RETRY),

    /** TMDB does not answer. */
    TMDB_OFFLINE(R.string.error_tmdb_offline_title, R.string.error_tmdb_offline, ErrorAction.RETRY),

    /** Searching TMDB needs a key. */
    NO_TMDB_KEY(R.string.error_no_tmdb_key_title, R.string.error_no_tmdb_key, ErrorAction.ADD_TMDB_KEY),

    /** TMDB refuses the key in use (401). */
    TMDB_KEY_REFUSED(R.string.error_tmdb_key_refused_title, R.string.error_tmdb_key_refused, ErrorAction.EDIT_TMDB_KEY),

    /** TMDB has no entry at the id chosen (removed since the search): another result, or a search again, fixes it. */
    TMDB_NOT_FOUND(R.string.error_tmdb_not_found_title, R.string.error_tmdb_not_found, null),

    /** The server refused the app (signed out, token revoked). */
    DENIED(R.string.error_denied_title, R.string.error_denied, ErrorAction.RECONNECT),

    /** What was asked for is no longer in the library: Back is the way out. */
    GONE(R.string.error_gone_title, R.string.error_gone, null),

    /** No video player on this device: the Zidoo player is missing. */
    NO_PLAYER(R.string.play_failed, R.string.no_player, null),

    /** The file needs credentials, and only the Zidoo player may receive them. */
    CREDENTIALS(R.string.play_failed, R.string.player_credentials, null),

    /** The Zidoo player trusted with passwords before is not the one installed now. */
    PLAYER_CHANGED(R.string.play_failed, R.string.player_changed, null),

    /** The device's Keystore refused to seal a password or a token: nothing was written. */
    KEYSTORE(R.string.error_keystore_title, R.string.error_keystore, ErrorAction.RETRY),

    /** The device's storage refused a write (full, or failing): nothing was saved. */
    NOT_SAVED(R.string.error_not_saved_title, R.string.error_not_saved, ErrorAction.RETRY),

    /** The settings file could not be read, again and again: nothing can open before it is. */
    SETTINGS_UNREAD(R.string.error_settings_title, R.string.error_settings, ErrorAction.RETRY),

    /** Anything else: the details are in the log. */
    FAILED(R.string.error_failed_title, R.string.error_failed, ErrorAction.RETRY),
}

/** The [UiError] a failure shows as, from what it comes down to ([failureKind]). Callers log the failure itself. */
fun Throwable.toUiError(): UiError = failureKind().toUiError()

/** How a screen says a [FailureKind]. */
fun FailureKind.toUiError(): UiError = when (this) {
    FailureKind.UNREACHABLE, FailureKind.IO, FailureKind.CERTIFICATE -> UiError.OFFLINE
    FailureKind.TMDB_UNREACHABLE -> UiError.TMDB_OFFLINE
    FailureKind.NO_TMDB_KEY -> UiError.NO_TMDB_KEY
    FailureKind.TMDB_KEY_REFUSED -> UiError.TMDB_KEY_REFUSED
    FailureKind.TMDB_NOT_FOUND -> UiError.TMDB_NOT_FOUND
    FailureKind.REFUSED -> UiError.DENIED
    FailureKind.GONE -> UiError.GONE
    FailureKind.KEYSTORE -> UiError.KEYSTORE
    // Only ever met writing: what was written is not saved.
    FailureKind.STORAGE_FULL -> UiError.NOT_SAVED
    // The NAS's refusals and absences are said in Settings, folder by folder; a disk failing may fail a read as well.
    FailureKind.LOGIN_FAILED, FailureKind.ACCESS_DENIED, FailureKind.MISSING, FailureKind.STORAGE_FAILED,
    FailureKind.SERVER_FAILED, FailureKind.UNEXPECTED_ANSWER, FailureKind.OTHER -> UiError.FAILED
}
