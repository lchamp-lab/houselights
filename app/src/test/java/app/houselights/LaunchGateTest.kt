package app.houselights

import app.houselights.LaunchGate.Reason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch screen holds while the gate is shut, as the splash screen asks it at each frame
 * ([LaunchGate.keepOnScreen]): until home has its rows and its first pictures, at once when the app
 * starts elsewhere, never past the cap; and it opens once, said once in the log.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LaunchGateTest {
    /** Milliseconds since the process started, as the test sets them. */
    private var now = 0L
    private val said = mutableListOf<String>()
    private val gate = LaunchGate(sinceStart = { now }, capMs = 1_500, report = { said += it })

    @Test
    fun itHoldsUntilHomeHasItsRowsAndItsPictures() {
        assertTrue(gate.keepOnScreen())
        now = 430
        gate.homeLoaded()
        assertTrue("rows alone: the pictures would fill in after", gate.keepOnScreen())
        now = 790
        gate.picturesLoaded()
        assertFalse(gate.keepOnScreen())
        assertEquals(Reason.READY, gate.reason)
    }

    @Test
    fun picturesBeforeRowsAreNotEnough() {
        gate.picturesLoaded()
        assertTrue(gate.keepOnScreen())
        gate.homeLoaded()
        assertEquals(Reason.READY, gate.reason)
    }

    @Test
    fun itOpensAtOnceWhenTheAppStartsElsewhere() {
        now = 180
        gate.startsElsewhere()
        assertFalse(gate.keepOnScreen())
        assertEquals(Reason.START_SCREEN, gate.reason)
    }

    @Test
    fun aFrameAtTheCapOpensIt() {
        gate.homeLoaded()
        now = 1_499
        assertTrue(gate.keepOnScreen())
        now = 1_500
        assertFalse("a NAS slow to send the pictures never holds the app", gate.keepOnScreen())
        assertEquals(Reason.CAP, gate.reason)
    }

    @Test
    fun theCapOpensItWithoutAFrame() = runTest {
        val timed = LaunchGate(sinceStart = { testScheduler.currentTime + STARTED }, capMs = 1_500, report = { said += it })
        backgroundScope.launch { timed.openAtCap() }
        advanceTimeBy(1_500 - STARTED - 1)
        assertNull(timed.reason)
        advanceTimeBy(2)
        assertEquals(Reason.CAP, timed.reason)
    }

    @Test
    fun itOpensOnceForTheFirstReason() {
        now = 1_600
        gate.keepOnScreen()
        gate.homeLoaded()
        gate.picturesLoaded()
        gate.startsElsewhere()
        assertEquals(Reason.CAP, gate.reason)
        assertEquals("said once", 1, said.size)
    }

    @Test
    fun waitingForItEndsWhenItOpens() = runTest {
        val opened = async { gate.awaitOpen() }
        runCurrent()
        assertFalse(opened.isCompleted)
        gate.startsElsewhere()
        assertEquals(Reason.START_SCREEN, opened.await())
        assertEquals("open already: at once", Reason.START_SCREEN, gate.awaitOpen())
    }

    /** One line for the evening on the player: how long after the process started, why, and when each part came. */
    @Test
    fun theLogSaysWhenAndWhy() {
        now = 120
        gate.settingsRead()
        now = 430
        gate.homeLoaded()
        now = 790
        gate.picturesLoaded()
        assertEquals(
            listOf("Launch screen left 790 ms after the process started: ready (settings at 120 ms, rows at 430 ms, pictures at 790 ms)"),
            said,
        )
    }

    @Test
    fun theLogSaysWhatTheCapCameBefore() {
        now = 120
        gate.settingsRead()
        now = 430
        gate.homeLoaded()
        now = 1_517
        gate.keepOnScreen()
        assertEquals(
            listOf("Launch screen left 1517 ms after the process started: cap (settings at 120 ms, rows at 430 ms, pictures not in)"),
            said,
        )
    }

    @Test
    fun theLogOfAnotherStartScreenSaysTheSettingsAlone() {
        now = 190
        gate.settingsRead()
        now = 212
        gate.startsElsewhere()
        assertEquals(listOf("Launch screen left 212 ms after the process started: start screen (settings at 190 ms)"), said)
    }

    private companion object {
        /** The process started this long before the test's clock, as the activity comes after it. */
        const val STARTED = 300L
    }
}
