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
 * in memory; then the memory freed for the player ([freeMemory]) and the player started ([start]). The app must still be
 * [onScreen] once the tracks are read, the last wait. Any failure is said through [failed] and forgets the session once
 * it was made; cancellation is not a failure. True when the player was started.
 */
internal suspend fun PlaybackManager.handOff(
    plan: PlaybackPlan.Launch,
    tracks: suspend () -> TrackPlan?,
    onScreen: () -> Boolean = { true },
    freeMemory: () -> Unit,
    start: (Intent) -> Unit,
    failed: (UiError) -> Unit,
): Boolean {
    var started = false
    suspendRunCatching {
        val asked = tracks()
        // Home pressed meanwhile: nothing opens over the app gone to, and no session starts.
        if (onScreen()) {
            val intent = intentFor(plan, asked)
            // The player needs the memory more than pictures that reload from disk.
            freeMemory()
            start(intent)
            onPlayerLaunched()
            started = true
        }
    }.onFailure { e ->
        // Refused before any session was made: nothing to forget.
        if (e is PlaybackManager.CredentialsNotShared) {
            failed(if (e.playerChanged) UiError.PLAYER_CHANGED else UiError.CREDENTIALS)
            return false
        }
        onLaunchFailed()
        // A player missing, or one that refuses to be started by other apps, is unusable here.
        val missing = e is ActivityNotFoundException || e is SecurityException
        if (!missing) Log.w(TAG, "Handing ${plan.title} to the player failed", e)
        failed(if (missing) UiError.NO_PLAYER else e.toUiError())
    }
    return started
}

private const val TAG = "Playback"
