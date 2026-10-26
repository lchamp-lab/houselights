package app.houselights.ui.util

import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Why a play did not start belongs to the screen it was asked from: another screen shown clears it. */
class PlaybackOutcomeTest {
    private val outcome = PlaybackOutcome(MutableSharedFlow())
    private val home = PlaybackProblem(UiError.NO_PLAYER, PlayOrigin("home"))

    @Test
    fun aProblemStaysWhileItsScreenShows() {
        outcome.reportProblem(home)
        outcome.screenShown("home")
        assertEquals(home, outcome.playbackProblem.value)
    }

    /** Home's problem never shows on the next title's page, nor again on coming back. */
    @Test
    fun anotherScreenClearsIt() {
        outcome.reportProblem(home)
        outcome.screenShown("title")
        assertNull(outcome.playbackProblem.value)
        outcome.screenShown("home")
        assertNull(outcome.playbackProblem.value)
    }

    /** A title held while its file is missing is not tried: where it was asked says it is gone, nothing played. */
    @Test
    fun aTitleHeldIsNotTried() {
        val page = PlayOrigin("title")
        val heat = MediaItem("m1", MediaKind.MOVIE, "Heat")
        assertEquals(false, outcome.refusesHeld(heat, page))
        assertNull(outcome.playbackProblem.value)
        assertEquals(true, outcome.refusesHeld(heat.copy(missingSince = 1), page))
        assertEquals(PlaybackProblem(UiError.HELD, page), outcome.playbackProblem.value)
    }
}
