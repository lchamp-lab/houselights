package app.houselights.ui.settings

import app.houselights.ui.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** About asks the device once whether the Zidoo player is there, and its control API each time it shows. */
@OptIn(ExperimentalCoroutinesApi::class)
class AboutViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun theApiIsAskedEachTimeAboutShows() = runTest {
        var installedAsked = 0
        val answers = ArrayDeque(listOf(true, false))
        val gate = CompletableDeferred<Unit>()
        val vm = AboutViewModel({ installedAsked++ == 0 }, { gate.await(); answers.removeFirst() })
        assertTrue(vm.player)

        vm.check()
        advanceUntilIdle()
        assertNull("being asked", vm.api)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(true, vm.api)

        vm.forget()
        assertNull("shown again, it is asked again", vm.api)
        vm.check()
        advanceUntilIdle()
        assertEquals(false, vm.api)
        assertEquals("the player is asked for once", 1, installedAsked)
    }

    @Test
    fun anAnswerAfterAboutHasGoneIsNotKept() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = AboutViewModel({ false }, { gate.await(); true })
        assertFalse(vm.player)
        vm.check()
        vm.forget()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.api)
    }
}
