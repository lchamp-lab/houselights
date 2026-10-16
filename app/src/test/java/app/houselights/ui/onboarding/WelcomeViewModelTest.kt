package app.houselights.ui.onboarding

import app.houselights.ui.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The welcome wall's posters are asked of TMDB once, and stay while the welcome screen is in the
 * back stack; none came, they are asked for again.
 */
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
        vm.show()
        assertTrue("none while TMDB answers", vm.posters.value.isEmpty())
        advanceUntilIdle()
        assertEquals(2, vm.posters.value.size)
        vm.show()
        advanceUntilIdle()
        assertEquals("shown again, the wall is there", 1, asked)
    }

    @Test
    fun anEmptyWallIsAskedForAgainWhenItShowsAgain() = runTest {
        val answers = ArrayDeque(listOf(emptyList(), listOf("https://image.tmdb.org/t/p/w342/a.jpg")))
        var asked = 0
        val vm = WelcomeViewModel {
            asked++
            answers.removeFirst()
        }
        vm.show()
        advanceUntilIdle()
        assertTrue(vm.posters.value.isEmpty())
        assertEquals("asked once while the first answer comes", 1, asked)
        vm.show()
        advanceUntilIdle()
        assertEquals(1, vm.posters.value.size)
        assertEquals(2, asked)
    }

    @Test
    fun noAnswerIsAnEmptyWall() = runTest {
        val vm = WelcomeViewModel { emptyList() }
        advanceUntilIdle()
        assertTrue(vm.posters.value.isEmpty())
    }
}
