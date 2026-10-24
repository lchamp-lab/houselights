package app.houselights

import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import app.houselights.core.PlaybackPlan
import app.houselights.core.TrackPlan
import app.houselights.core.suspendRunCatching
import app.houselights.player.PlaybackManager
import app.houselights.ui.util.UiError
import app.houselights.ui.util.toUiError

/**
 * Hands [plan] to the player with its [tracks]: the intent first, so a file the player may not have leaves the pictures
 * in memory; then the memory freed for the player ([freeMemory]) and the player started ([start]). Any failure is said
 * through [failed] and forgets the session once it was made; cancellation is not a failure.
 */
internal suspend fun PlaybackManager.handOff(
    plan: PlaybackPlan.Launch,
    tracks: suspend () -> TrackPlan?,
    freeMemory: () -> Unit,
    start: (Intent) -> Unit,
    failed: (UiError) -> Unit,
) {
    suspendRunCatching {
        val intent = intentFor(plan, tracks())
        // The player needs the memory more than pictures that reload from disk.
        freeMemory()
        start(intent)
        onPlayerLaunched()
    }.onFailure { e ->
        // Refused before any session was made: nothing to forget.
        if (e is PlaybackManager.CredentialsNotShared) {
            failed(if (e.playerChanged) UiError.PLAYER_CHANGED else UiError.CREDENTIALS)
            return
        }
        onLaunchFailed()
        // A player missing, or one that refuses to be started by other apps, is unusable here.
        val missing = e is ActivityNotFoundException || e is SecurityException
        if (!missing) Log.w(TAG, "Handing ${plan.title} to the player failed", e)
        failed(if (missing) UiError.NO_PLAYER else e.toUiError())
    }
}

private const val TAG = "Playback"
