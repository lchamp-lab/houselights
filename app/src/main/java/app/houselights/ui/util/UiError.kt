package app.houselights.ui.util

import androidx.annotation.StringRes
import androidx.compose.runtime.staticCompositionLocalOf
import app.houselights.R
import app.houselights.core.NoTmdbKeyException
import app.houselights.core.NotFoundException
import app.houselights.providers.local.TmdbKeyRefusedException
import app.houselights.providers.local.TmdbUnreachableException
import retrofit2.HttpException
import java.io.IOException

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

    /** The server refused the app (signed out, token revoked). */
    DENIED(R.string.error_denied_title, R.string.error_denied, ErrorAction.RECONNECT),

    /** What was asked for is no longer in the library: Back is the way out. */
    GONE(R.string.error_gone_title, R.string.error_gone, null),

    /** No video player on this device: the Zidoo player is missing. */
    NO_PLAYER(R.string.play_failed, R.string.no_player, null),

    /** The file needs credentials, and only the Zidoo player may receive them. */
    CREDENTIALS(R.string.play_failed, R.string.player_credentials, null),

    /** Anything else: the details are in the log. */
    FAILED(R.string.error_failed_title, R.string.error_failed, ErrorAction.RETRY),
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404

/** The [UiError] a failure shows as. Callers log the failure itself. */
fun Throwable.toUiError(): UiError = when {
    this is NoTmdbKeyException -> UiError.NO_TMDB_KEY
    this is TmdbKeyRefusedException -> UiError.TMDB_KEY_REFUSED
    this is TmdbUnreachableException -> UiError.TMDB_OFFLINE
    this is NotFoundException -> UiError.GONE
    this is HttpException && (code() == HTTP_UNAUTHORIZED || code() == HTTP_FORBIDDEN) -> UiError.DENIED
    this is HttpException && code() == HTTP_NOT_FOUND -> UiError.GONE
    this is IOException -> UiError.OFFLINE
    else -> UiError.FAILED
}
