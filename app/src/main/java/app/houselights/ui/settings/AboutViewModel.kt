package app.houselights.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.houselights.ui.util.LatestJob

/**
 * What About says of the device: whether the Zidoo player is installed ([player], asked once), and
 * whether its control API answers ([api]), asked each time About shows: null while it is asked.
 */
internal class AboutViewModel(isPlayerInstalled: () -> Boolean, private val apiAnswers: suspend () -> Boolean) : ViewModel() {
    val player: Boolean = isPlayerInstalled()

    var api by mutableStateOf<Boolean?>(null)
        private set

    private val asking = LatestJob(viewModelScope)

    /** About shows: the control API is asked whether it answers. */
    fun check() {
        api = null
        asking.launch { api = apiAnswers() }
    }

    /** About has gone: the answer is not waited for, and the next time it shows it asks again. */
    fun forget() {
        asking.cancel()
        api = null
    }
}
