package app.houselights.ui.util

import androidx.annotation.StringRes
import app.houselights.R
import app.houselights.core.NoTmdbKeyException
import app.houselights.core.NotFoundException
import app.houselights.providers.local.TmdbUnreachableException
import retrofit2.HttpException
import java.io.IOException

/** Why a screen could not show or do what was asked, in words people can act on. */
enum class UiError(@StringRes val message: Int) {
    /** The library's server or NAS does not answer. */
    OFFLINE(R.string.error_offline),

    /** TMDB does not answer. */
    TMDB_OFFLINE(R.string.error_tmdb_offline),

    /** Searching TMDB needs a key. */
    NO_TMDB_KEY(R.string.error_no_tmdb_key),

    /** The server refused the app (signed out, token revoked). */
    DENIED(R.string.error_denied),

    /** What was asked for is no longer in the library. */
    GONE(R.string.error_gone),

    /** Anything else: the details are in the log. */
    FAILED(R.string.error_failed),
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404

/** The [UiError] a failure shows as. Callers log the failure itself. */
fun Throwable.toUiError(): UiError = when {
    this is NoTmdbKeyException -> UiError.NO_TMDB_KEY
    this is TmdbUnreachableException -> UiError.TMDB_OFFLINE
    this is NotFoundException -> UiError.GONE
    this is HttpException && (code() == HTTP_UNAUTHORIZED || code() == HTTP_FORBIDDEN) -> UiError.DENIED
    this is HttpException && code() == HTTP_NOT_FOUND -> UiError.GONE
    this is IOException -> UiError.OFFLINE
    else -> UiError.FAILED
}
