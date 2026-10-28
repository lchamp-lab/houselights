package app.houselights.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.houselights.player.PlayerStatus
import app.houselights.ui.util.LatestJob

/** Device status for About: the [player] playback uses, and whether the control API answers ([api], null meanwhile). */
internal class AboutViewModel(private val playerStatus: () -> PlayerStatus, private val apiAnswers: suspend () -> Boolean) : ViewModel() {
    /** The player as playback decides, asked once, and again once the user trusted it ([readPlayer]). */
    var player: PlayerStatus by mutableStateOf(playerStatus())
        private set

    var api by mutableStateOf<Boolean?>(null)
        private set

    private val asking = LatestJob(viewModelScope)

    /** Checks whether the control API answers; [api] is null until it does. */
    fun check() {
        api = null
        asking.launch { api = apiAnswers() }
    }

    /** Asks again what playback decides of the player, once the user trusted it: About then says Détecté. */
    fun readPlayer() {
        player = playerStatus()
    }

    /** Cancels a pending check and clears [api], so the next [check] starts over. */
    fun forget() {
        asking.cancel()
        api = null
    }
}
