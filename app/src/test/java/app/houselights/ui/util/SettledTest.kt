package app.houselights.ui.util

import androidx.compose.runtime.mutableStateOf
import app.houselights.ui.design.HlMotion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What follows focus (a backdrop, a hero, a preview) shows once focus settles; the first at once. */
class SettledTest {
    private val focus = MutableStateFlow<String?>(null)
    private val shown = mutableStateOf<String?>(null)

    private fun TestScope.follow() {
        backgroundScope.launch { focus.settleInto(shown) }
        runCurrent()
    }

    @Test
    fun theFirstComesAtOnce() = runTest {
        follow()
        assertNull(shown.value)
        focus.value = "playtime"
        runCurrent()
        assertEquals("playtime", shown.value)
    }

    @Test
    fun theNextWaitsForFocusToSettle() = runTest {
        focus.value = "playtime"
        follow()
        focus.value = "heat"
        advanceTimeBy(HlMotion.SETTLE - 1)
        assertEquals("playtime", shown.value)
        advanceTimeBy(2)
        assertEquals("heat", shown.value)
    }

    @Test
    fun aStepBeforeItSettlesWaitsAgain() = runTest {
        focus.value = "playtime"
        follow()
        focus.value = "heat"
        advanceTimeBy(HlMotion.SETTLE / 2)
        focus.value = "alien"
        advanceTimeBy(HlMotion.SETTLE - 1)
        assertEquals("nothing loaded for a title passed by", "playtime", shown.value)
        advanceTimeBy(2)
        assertEquals("alien", shown.value)
    }

    @Test
    fun comingBackToWhatIsShownLeavesIt() = runTest {
        focus.value = "playtime"
        follow()
        focus.value = "heat"
        advanceTimeBy(HlMotion.SETTLE / 2)
        focus.value = "playtime"
        advanceTimeBy(HlMotion.SETTLE * 2)
        assertEquals("playtime", shown.value)
    }

    @Test
    fun nothingLeftToShowWaitsToo() = runTest {
        focus.value = "playtime"
        follow()
        focus.value = null
        advanceTimeBy(HlMotion.SETTLE - 1)
        assertEquals("playtime", shown.value)
        advanceTimeBy(2)
        assertNull(shown.value)
        focus.value = "heat"
        runCurrent()
        assertEquals("then the next comes at once", "heat", shown.value)
    }
}
