package app.houselights.ui.onboarding

import app.houselights.ui.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The welcome wall's posters are asked of TMDB once, and stay while the welcome screen is in the back stack. */
@OptIn(ExperimentalCoroutinesApi::class)
class WelcomeViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun thePostersAreAskedForOnceAndKept() = runTest {
        var asked = 0
        val vm = WelcomeViewModel {
            asked++
            listOf("https://image.tmdb.org/t/p/w342/a.jpg", "https://image.tmdb.org/t/p/w342/b.jpg")
        }
        assertTrue("none while TMDB answers", vm.posters.value.isEmpty())
        advanceUntilIdle()
        assertEquals(2, vm.posters.value.size)
        assertEquals(1, asked)
    }

    @Test
    fun noAnswerIsAnEmptyWall() = runTest {
        val vm = WelcomeViewModel { emptyList() }
        advanceUntilIdle()
        assertTrue(vm.posters.value.isEmpty())
    }
}
