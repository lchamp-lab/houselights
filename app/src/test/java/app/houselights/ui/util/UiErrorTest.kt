package app.houselights.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What each error offers to do: Retry only where trying again can help (core/FailuresTest says which error is which). */
class UiErrorTest {
    @Test
    fun eachErrorOffersWhatFixesIt() {
        assertEquals(ErrorAction.RETRY, UiError.OFFLINE.action)
        assertEquals(ErrorAction.RETRY, UiError.TMDB_OFFLINE.action)
        assertEquals(ErrorAction.RETRY, UiError.FAILED.action)
        assertEquals(ErrorAction.RECONNECT, UiError.DENIED.action)
        assertEquals(ErrorAction.ADD_TMDB_KEY, UiError.NO_TMDB_KEY.action)
        assertEquals(ErrorAction.EDIT_TMDB_KEY, UiError.TMDB_KEY_REFUSED.action)
        // Gone stays gone: Back is the way out.
        assertNull(UiError.GONE.action)
        // TMDB will not know the id the next time either: another result, or another search.
        assertNull(UiError.TMDB_NOT_FOUND.action)
    }
}
