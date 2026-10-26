package app.houselights.ui.util

import androidx.compose.runtime.Immutable
import app.houselights.core.MediaItem
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

    /**
     * Why the last play did not start, said where it was asked until dismissed there (an OK on a page, a click on home's
     * case), another play or another screen.
     */
    val playbackProblem: StateFlow<PlaybackProblem?> = problem

    fun reportProblem(problem: PlaybackProblem) {
        this.problem.value = problem
    }

    fun clearProblem() {
        problem.value = null
    }

    /**
     * A play of [item] asked from [origin]: the last play's problem goes, then [start] dims the house lights and plays.
     * A title held while its file is missing ([MediaItem.held]) is not tried, [start] never called and the room staying
     * lit: where play was asked says it is gone and what to do ([UiError.HELD]).
     */
    fun tryPlay(item: MediaItem, origin: PlayOrigin, start: () -> Unit) {
        if (item.held) {
            reportProblem(PlaybackProblem(UiError.HELD, origin))
            return
        }
        clearProblem()
        start()
    }

    /** The screen of back stack entry [screen] shows: a problem asked from another one goes, never to show there. */
    fun screenShown(screen: String) = problem.update { it?.takeIf { asked -> asked.origin.screen == screen } }
}
