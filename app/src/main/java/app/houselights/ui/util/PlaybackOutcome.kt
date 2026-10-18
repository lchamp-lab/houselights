package app.houselights.ui.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** A play request's outcome: it ended ([playbackEnded]) or why it failed ([playbackProblem], by MainActivity). */
class PlaybackOutcome(val playbackEnded: SharedFlow<Unit>) {
    private val problem = MutableStateFlow<UiError?>(null)

    /** Why the last title did not play, shown on its detail page until the next action or screen clears it. */
    val playbackProblem: StateFlow<UiError?> = problem

    fun reportProblem(error: UiError) {
        problem.value = error
    }

    fun clearProblem() {
        problem.value = null
    }
}
