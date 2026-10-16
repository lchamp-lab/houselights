package app.houselights.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a part that failed offers: trying again, or the way to where the error is fixed. */
class PartFailedTest {
    @Test
    fun anActionDoesWhatItNames() {
        val done = mutableListOf<String>()
        ErrorAction.RETRY.run({ done += "retry" }) { done += "fix $it" }
        ErrorAction.RECONNECT.run({ done += "retry" }) { done += "fix $it" }
        assertEquals(listOf("retry", "fix RECONNECT"), done)
    }
}
