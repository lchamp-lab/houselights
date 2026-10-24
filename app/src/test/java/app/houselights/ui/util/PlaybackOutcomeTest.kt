package app.houselights.ui.util

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
}
