package app.houselights.ui.util

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.houselights.R
import app.houselights.core.DiskAbsentException
import app.houselights.core.FailureKind
import app.houselights.core.SourceConfig
import app.houselights.core.causeChain
import app.houselights.core.failureKind
import app.houselights.providers.local.Folder
import app.houselights.providers.local.Protocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** What fixes an error, named on its button. Screens try again themselves; the others lead elsewhere. */
enum class ErrorAction(@StringRes val label: Int) {
    RETRY(R.string.retry),

    /** Sign in to the server again. */
    RECONNECT(R.string.error_action_reconnect),

    /** Settings, where a TMDB key is added or changed. */
    ADD_TMDB_KEY(R.string.error_action_add_tmdb_key),
    EDIT_TMDB_KEY(R.string.error_action_edit_tmdb_key),

    /** The Zidoo player changed (#58): its sheet names it and asks, the answer that changes nothing first; trusts nothing. */
    TRUST_PLAYER(R.string.trust_player),
}

/**
 * Leads where an error is fixed, for every action but [ErrorAction.RETRY]; provided with the navigation, the trust sheet
 * opening over the screen that says the failure.
 */
val LocalErrorFix = staticCompositionLocalOf<(ErrorAction) -> Unit> { {} }

/** A screen's error in actionable words: what happened ([title]), what to check ([message]), a fix ([action]). */
enum class UiError(@StringRes val title: Int, @StringRes val message: Int, val action: ErrorAction?) {
    /** The library's server or NAS does not answer: titled by where the library lies ([titleAt]). */
    OFFLINE(R.string.error_offline_title, R.string.error_offline, ErrorAction.RETRY),

    /** A disk of the player's own is no longer plugged in, or not where it was read ([DiskAbsentException]). */
    DISK_ABSENT(R.string.error_disk_absent_title, R.string.disk_absent_usb, ErrorAction.RETRY),

    /** TLS refused the server's or TMDB's certificate: expired, or the device's clock is wrong. */
    CERTIFICATE(R.string.error_certificate_title, R.string.error_certificate, ErrorAction.RETRY),

    /** TMDB does not answer. */
    TMDB_OFFLINE(R.string.error_tmdb_offline_title, R.string.error_tmdb_offline, ErrorAction.RETRY),

    /** Searching TMDB needs a key. */
    NO_TMDB_KEY(R.string.error_no_tmdb_key_title, R.string.error_no_tmdb_key, ErrorAction.ADD_TMDB_KEY),

    /** TMDB refuses the key in use (401). */
    TMDB_KEY_REFUSED(R.string.error_tmdb_key_refused_title, R.string.error_tmdb_key_refused, ErrorAction.EDIT_TMDB_KEY),

    /** TMDB has no entry at the chosen id (removed since the search): another result or a new search fixes it. */
    TMDB_NOT_FOUND(R.string.error_tmdb_not_found_title, R.string.error_tmdb_not_found, null),

    /** The server refused the app (signed out, token revoked). */
    DENIED(R.string.error_denied_title, R.string.error_denied, ErrorAction.RECONNECT),

    /** What was asked for has left the library: Back is the way out. */
    GONE(R.string.error_gone_title, R.string.error_gone, null),

    /**
     * A title held while its file is missing ([app.houselights.core.MediaItem.held]): not tried, said by the ring, as it
     * may be no failure; plugging the disk back or removing the title fixes it.
     */
    HELD(R.string.error_gone_title, R.string.held_gone_fix, null),

    /** None of a person's titles remains in the library: Back is the way out. */
    PERSON_GONE(R.string.error_person_gone_title, R.string.error_person_gone, null),

    /** No video player on this device: the Zidoo player is missing. */
    NO_PLAYER(R.string.play_failed, R.string.no_player, null),

    /** The file needs credentials, and only the Zidoo player may receive them. */
    CREDENTIALS(R.string.play_failed, R.string.player_credentials, null),

    /** The installed Zidoo player is not the one trusted with passwords. */
    PLAYER_CHANGED(R.string.play_failed, R.string.player_changed, null),

    /** The device's Keystore refused to seal a password or a token: nothing was written. */
    KEYSTORE(R.string.error_keystore_title, R.string.error_keystore, ErrorAction.RETRY),

    /** The device's Keystore did not open a saved secret for now: it is kept, and read again. */
    SECRETS_UNREAD(R.string.error_keystore_title, R.string.error_secrets_unread, ErrorAction.RETRY),

    /** The device's storage refused a write (full, or failing): nothing was saved. */
    NOT_SAVED(R.string.error_not_saved_title, R.string.error_not_saved, ErrorAction.RETRY),

    /** The settings file could not be read after repeated attempts: nothing can open until it is. */
    SETTINGS_UNREAD(R.string.error_settings_title, R.string.error_settings, ErrorAction.RETRY),

    /** Anything else: the details are in the log. */
    FAILED(R.string.error_failed_title, R.string.error_failed, ErrorAction.RETRY),
}

/**
 * The [UiError] a failure shows as, by its [failureKind], a disk of the player's own unplugged said as such rather than
 * as a NAS out of reach, as scans count it; callers log the failure themselves.
 */
fun Throwable.toUiError(): UiError = if (causeChain().any { it is DiskAbsentException }) UiError.DISK_ABSENT else failureKind().toUiError()

fun FailureKind.toUiError(): UiError = when (this) {
    FailureKind.UNREACHABLE, FailureKind.IO -> UiError.OFFLINE
    FailureKind.CERTIFICATE -> UiError.CERTIFICATE
    FailureKind.TMDB_UNREACHABLE -> UiError.TMDB_OFFLINE
    FailureKind.NO_TMDB_KEY -> UiError.NO_TMDB_KEY
    FailureKind.TMDB_KEY_REFUSED -> UiError.TMDB_KEY_REFUSED
    FailureKind.TMDB_NOT_FOUND -> UiError.TMDB_NOT_FOUND
    FailureKind.REFUSED -> UiError.DENIED
    FailureKind.GONE -> UiError.GONE
    FailureKind.KEYSTORE -> UiError.KEYSTORE
    FailureKind.SECRET_UNREAD -> UiError.SECRETS_UNREAD
    // Only raised by writes: what was written is not saved.
    FailureKind.STORAGE_FULL -> UiError.NOT_SAVED
    // NAS refusals and missing paths show in Settings, per folder; a failing disk can fail reads too.
    FailureKind.LOGIN_FAILED, FailureKind.ACCESS_DENIED, FailureKind.MISSING, FailureKind.STORAGE_FAILED,
    FailureKind.SERVER_FAILED, FailureKind.UNEXPECTED_ANSWER, FailureKind.OTHER,
    -> UiError.FAILED
}

/** Where the library in use lies, which names it once out of reach ([UiError.OFFLINE]): a NAS, or a media server. */
enum class LibraryPlace { NAS, SERVER }

/**
 * Where the library of [source] lies, the folders' ([folders]) on a NAS when one of them is: null without a source, or
 * for folders on the player's own disks alone, which never say a NAS (a disk unplugged is [UiError.DISK_ABSENT]).
 */
fun libraryPlaceOf(source: SourceConfig?, folders: List<Folder>): LibraryPlace? = when (source) {
    is SourceConfig.Jellyfin -> LibraryPlace.SERVER
    SourceConfig.Local -> LibraryPlace.NAS.takeIf { folders.any { folder -> folder.protocol != Protocol.LOCAL } }
    null -> null
}

/** Where the library lies ([libraryPlaceOf]) as the source in use ([sources]) and its folders ([folders]) change. */
fun libraryPlaces(sources: Flow<SourceConfig?>, folders: Flow<List<Folder>>): Flow<LibraryPlace?> =
    combine(sources, folders, ::libraryPlaceOf).distinctUntilChanged()

/**
 * Where the library in use lies ([libraryPlaceOf]), provided with the navigation ([ProvideLibraryPlace]); null where it
 * is not known.
 */
val LocalLibraryPlace = compositionLocalOf<LibraryPlace?> { null }

/**
 * [content] told where the library lies ([LocalLibraryPlace]) as [place] moves, which names it out of reach wherever an
 * error is said.
 */
@Composable
internal fun ProvideLibraryPlace(place: StateFlow<LibraryPlace?>, content: @Composable () -> Unit) {
    val at by place.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalLibraryPlace provides at, content = content)
}

/**
 * This error's title, the library out of reach ([UiError.OFFLINE]) named by where it lies ([place]): « NAS
 * injoignable », « Serveur injoignable », else « Bibliothèque injoignable »; every other error its own.
 */
@StringRes
fun UiError.titleAt(place: LibraryPlace?): Int = when {
    this != UiError.OFFLINE -> title
    place == LibraryPlace.NAS -> R.string.error_offline_nas_title
    place == LibraryPlace.SERVER -> R.string.error_offline_server_title
    else -> title
}

/** This error's title as the screens say it, the library out of reach named by where it lies ([LocalLibraryPlace]). */
@Composable
@ReadOnlyComposable
fun UiError.titleText(): String = stringResource(titleAt(LocalLibraryPlace.current))
