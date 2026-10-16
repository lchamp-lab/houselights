package app.houselights.ui.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What came of a title asked to play, as screens see it: that it ended ([playbackEnded]: screens
 * reload what it changed), or why it did not play ([playbackProblem]). The player
 * ([app.houselights.player.PlaybackManager]) keeps no screen's state: MainActivity, which asks it to
 * play, says here what failed.
 */
class PlaybackOutcome(val playbackEnded: SharedFlow<Unit>) {
    private val problem = MutableStateFlow<UiError?>(null)

    /**
     * Why the last title asked for did not play, shown where it was asked for (the detail page) until
     * the next action or the next screen clears it.
     */
    val playbackProblem: StateFlow<UiError?> = problem

    fun reportProblem(error: UiError) {
        problem.value = error
    }

    fun clearProblem() {
        problem.value = null
    }
}
