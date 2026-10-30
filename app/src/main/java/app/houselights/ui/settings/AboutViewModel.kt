package app.houselights.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.houselights.player.PlayerStatus
import app.houselights.player.ZidooPlayer.Decision
import app.houselights.ui.util.LatestJob

/**
 * Device status for About: the [player] playback uses and whether the control API answers ([api]), each null while
 * asked. [playerStatus] asks Android's package manager, off the main thread.
 */
internal class AboutViewModel(
    private val playerStatus: suspend () -> PlayerStatus,
    private val apiAnswers: suspend () -> Boolean,
) : ViewModel() {
    /** The player as playback decides, asked once, and again each time the trust sheet closes ([readPlayer]). */
    var player by mutableStateOf<PlayerStatus?>(null)
        private set

    var api by mutableStateOf<Boolean?>(null)
        private set

    private val reading = LatestJob(viewModelScope)
    private val asking = LatestJob(viewModelScope)

    init {
        reading.launch { player = playerStatus() }
    }

    /** Checks whether the control API answers; [api] is null until it does. */
    fun check() {
        api = null
        asking.launch { api = apiAnswers() }
    }

    /**
     * Asks again what playback decides of the player, each time the trust sheet closes or finds nothing to ask (#58):
     * trusted, or its updates uninstalled meanwhile, About then says Détecté. Once it was refused as changed and no
     * longer is, [left] runs, as the row that asked to trust it leaves.
     */
    fun readPlayer(left: () -> Unit) {
        reading.launch {
            val now = playerStatus()
            val was = player.changed
            player = now
            if (was && !now.changed) left()
        }
    }

    /** Cancels a pending check and clears [api], so the next [check] starts over. */
    fun forget() {
        asking.cancel()
        api = null
    }
}

/** Refused as changed, the one refusal trusting the player is for. */
private val PlayerStatus?.changed: Boolean get() = this is PlayerStatus.Unrecognized && reason == Decision.CHANGED
