package app.houselights.ui.util

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Where a play was asked: a screen, by its back stack entry's id, and on a title's page the [episode] whose card asked. */
@Immutable
data class PlayOrigin(val screen: String, val episode: String? = null)

/** Why a play asked from [origin] did not start. */
@Immutable
data class PlaybackProblem(val error: UiError, val origin: PlayOrigin)

/** A play request's outcome: it ended ([playbackEnded]) or why it failed ([playbackProblem], by MainActivity). */
class PlaybackOutcome(val playbackEnded: SharedFlow<Unit>) {
    private val problem = MutableStateFlow<PlaybackProblem?>(null)

    /** Why the last play did not start, said where it was asked until an OK there, another play or another screen. */
    val playbackProblem: StateFlow<PlaybackProblem?> = problem

    fun reportProblem(problem: PlaybackProblem) {
        this.problem.value = problem
    }

    fun clearProblem() {
        problem.value = null
    }

    /** The screen of back stack entry [screen] shows: a problem asked from another one goes, never to show there. */
    fun screenShown(screen: String) = problem.update { it?.takeIf { asked -> asked.origin.screen == screen } }
}
