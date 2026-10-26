package app.houselights.ui.settings

import androidx.annotation.StringRes
import app.houselights.R
import app.houselights.update.UpdateFailure
import app.houselights.update.UpdateState
import app.houselights.update.UpdateStep

// The update's words (DESIGN.md, the update sheet): each failure by its title, what to do, and the row that does it.

/** What mends a failure, as the sheet's action row offers it. */
internal enum class UpdateRemedy {
    /** « Réessayer »: the network, GitHub, the room, the file. */
    RETRY,

    /** « Autoriser l'installation »: Android blocked it. */
    ALLOW,

    /** Nothing in the app: « Rechercher maintenant » keeps a row to focus, a newer release may mend it. */
    NONE,
}

/** A failure's title, by its attention mark: the sheet's head and the state row's description. */
@get:StringRes
internal val UpdateFailure.title: Int
    get() = when (this) {
        UpdateFailure.OFFLINE -> R.string.update_offline_title
        UpdateFailure.CERTIFICATE -> R.string.error_certificate_title
        UpdateFailure.BUSY -> R.string.update_busy_title
        UpdateFailure.GITHUB_FAILED -> R.string.update_github_failed_title
        UpdateFailure.UNREADABLE -> R.string.update_unreadable_title
        UpdateFailure.STORAGE -> R.string.update_storage_title
        UpdateFailure.DOWNLOAD_CUT -> R.string.update_cut_title
        UpdateFailure.CORRUPT -> R.string.update_corrupt_title
        UpdateFailure.NOT_GENUINE -> R.string.update_not_genuine_title
        UpdateFailure.NOT_NEWER -> R.string.update_not_newer_title
        UpdateFailure.BLOCKED -> R.string.update_blocked_title
        UpdateFailure.CONFLICT -> R.string.update_conflict_title
        UpdateFailure.INCOMPATIBLE -> R.string.update_incompatible_title
        UpdateFailure.INSTALL_FAILED -> R.string.update_install_failed_title
    }

/** What happened and what to do, under the sheet's head; BUSY's takes the wait, STORAGE's the room needed. */
@get:StringRes
internal val UpdateFailure.message: Int
    get() = when (this) {
        UpdateFailure.OFFLINE -> R.string.update_offline_message
        UpdateFailure.CERTIFICATE -> R.string.update_certificate_message
        UpdateFailure.BUSY -> R.string.update_busy_message
        UpdateFailure.GITHUB_FAILED -> R.string.update_github_failed_message
        UpdateFailure.UNREADABLE -> R.string.update_unreadable_message
        UpdateFailure.STORAGE -> R.string.update_storage_message
        UpdateFailure.DOWNLOAD_CUT -> R.string.update_cut_message
        UpdateFailure.CORRUPT -> R.string.update_corrupt_message
        UpdateFailure.NOT_GENUINE -> R.string.update_not_genuine_message
        UpdateFailure.NOT_NEWER -> R.string.update_not_newer_message
        UpdateFailure.BLOCKED -> R.string.update_blocked_message
        UpdateFailure.CONFLICT -> R.string.update_conflict_message
        UpdateFailure.INCOMPATIBLE -> R.string.update_incompatible_message
        UpdateFailure.INSTALL_FAILED -> R.string.update_install_failed_message
    }

internal val UpdateFailure.remedy: UpdateRemedy
    get() = when (this) {
        UpdateFailure.BLOCKED -> UpdateRemedy.ALLOW
        UpdateFailure.UNREADABLE, UpdateFailure.NOT_GENUINE, UpdateFailure.NOT_NEWER, UpdateFailure.CONFLICT,
        UpdateFailure.INCOMPATIBLE,
        -> UpdateRemedy.NONE
        else -> UpdateRemedy.RETRY
    }

/** Nothing in the app mends it, but installing by hand may: the release page's address is said. */
internal val UpdateFailure.byHand: Boolean
    get() = remedy == UpdateRemedy.NONE && this != UpdateFailure.NOT_NEWER

/**
 * What the sheet's action row does in [s] at [now]: its words follow, focus stays on it, and OK does nothing where it
 * only says what is under way ([acts]).
 */
internal enum class UpdateAction(val acts: Boolean) {
    CHECK(true),
    CHECKING(false),
    RETRY(true),

    /** « Réessayer » while GitHub asks to wait: OK does nothing until then. */
    WAIT(false),
    ALLOW(true),
    UPDATE(true),
    DOWNLOADING(false),
    VERIFYING(false),
    INSTALL(true),
    INSTALLING(false),

    /** Android's window, left with the Home key, opened again. */
    REOPEN(true),
}

internal fun actionOf(s: UpdateState, now: Long): UpdateAction {
    val failure = s.failure
    val step = s.step
    return when {
        failure != null -> failureAction(failure, waiting = s.waitUntil?.let { it > now } == true)
        step is UpdateStep.NeedsPermission -> if (step.stuck) UpdateAction.CHECK else UpdateAction.ALLOW
        step == UpdateStep.Checking -> UpdateAction.CHECKING
        s.offer == null -> UpdateAction.CHECK
        else -> stepAction(step)
    }
}

private fun failureAction(failure: UpdateFailure, waiting: Boolean): UpdateAction = when {
    failure == UpdateFailure.BUSY && waiting -> UpdateAction.WAIT
    failure.remedy == UpdateRemedy.RETRY -> UpdateAction.RETRY
    failure.remedy == UpdateRemedy.ALLOW -> UpdateAction.ALLOW
    else -> UpdateAction.CHECK
}

private fun stepAction(step: UpdateStep): UpdateAction = when (step) {
    is UpdateStep.Downloading -> UpdateAction.DOWNLOADING
    UpdateStep.Verifying -> UpdateAction.VERIFYING
    UpdateStep.Ready -> UpdateAction.INSTALL
    is UpdateStep.Installing -> if (step.reopen) UpdateAction.REOPEN else UpdateAction.INSTALLING
    else -> UpdateAction.UPDATE
}
