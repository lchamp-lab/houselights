package app.houselights.ui.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library version a screen last showed, as it comes back with its saved state. */
class ComposeTest {
    @Test
    fun aScreenBehindReloadsWhenItComesBack() {
        val shown = shownAt(version = 4, process = 1)
        assertFalse(isBehind(shown, current = 4, process = 1))
        assertTrue(isBehind(shown, current = 6, process = 1))
    }

    /**
     * Android stopped the app while a film played: versions start again from 0, and the screen
     * restored loads anew from its ViewModel. Its saved version says nothing: no second load.
     */
    @Test
    fun aVersionShownInAnotherProcessNeverReloads() {
        val shown = shownAt(version = 7, process = 1)
        assertFalse(isBehind(shown, current = 0, process = 2))
    }
}
