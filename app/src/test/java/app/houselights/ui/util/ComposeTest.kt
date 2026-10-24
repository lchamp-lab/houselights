package app.houselights.ui.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library version a screen last showed, as it comes back with its saved state. */
class ComposeTest {
    @Test
    fun aScreenBehindReloadsWhenItComesBack() {
        val shown = shownAt(version = 4, settled = 2, process = 1)
        assertFalse(isBehind(shown, current = 4, process = 1))
        assertTrue(isBehind(shown, current = 6, process = 1))
    }

    /** Only a scan's progress since: the settled version is still the one shown. */
    @Test
    fun aScansProgressIsToldApart() {
        val shown = shownAt(version = 4, settled = 2, process = 1)
        assertTrue(scanOnly(shown, settled = 2))
        assertFalse(scanOnly(shown, settled = 3))
    }

    /** After process death, versions restart at 0 and the restored screen loads anew: no second load. */
    @Test
    fun aVersionShownInAnotherProcessNeverReloads() {
        val shown = shownAt(version = 7, settled = 7, process = 1)
        assertFalse(isBehind(shown, current = 0, process = 2))
    }
}
