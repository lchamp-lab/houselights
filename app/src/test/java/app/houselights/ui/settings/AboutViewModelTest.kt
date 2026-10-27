package app.houselights.ui.settings

import app.houselights.player.PlayerStatus
import app.houselights.ui.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** About asks once what playback decides of the Zidoo player, and its control API each time it shows. */
class AboutViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun theApiIsAskedEachTimeAboutShows() = runTest {
        var playerAsked = 0
        val answers = ArrayDeque(listOf(true, false))
        val gate = CompletableDeferred<Unit>()
        val player = PlayerStatus.Detected("com.zidoo.player")
        val vm = AboutViewModel({ player.also { playerAsked++ } }, { gate.await(); answers.removeFirst() })
        assertEquals(player, vm.player)

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
        assertEquals("the player is asked for once", 1, playerAsked)
    }

    @Test
    fun anAnswerAfterAboutHasGoneIsNotKept() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = AboutViewModel({ PlayerStatus.NotDetected }, { gate.await(); true })
        assertEquals(PlayerStatus.NotDetected, vm.player)
        vm.check()
        vm.forget()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.api)
    }
}
