package app.houselights

import app.houselights.LaunchGate.Reason
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The launch screen lifts once home has its rows, first images and a full frame drawn, never past the cap. */
class LaunchGateTest {
    /** Milliseconds since the process started, as the test sets them. */
    private var now = 0L
    private val said = mutableListOf<String>()

    private fun newGate() = LaunchGate(sinceStart = { now }, capMs = 1_500, report = { said += it })

    private val gate = newGate()

    /** Whichever of home's rows, first pictures and first full frame comes last, the gate waits for it. */
    @Test
    fun itHoldsUntilHomeHasItsRowsItsPicturesAndAFrameDrawn() {
        val milestones = mapOf<String, LaunchGate.() -> Unit>(
            "rows" to LaunchGate::homeLoaded,
            "pictures" to LaunchGate::picturesLoaded,
            "home drawn" to LaunchGate::homeDrawn,
        )
        milestones.forEach { (missing, record) ->
            val fresh = newGate()
            milestones.filterKeys { it != missing }.values.forEach { it(fresh) }
            assertFalse("Open without $missing", fresh.isOpen)
            fresh.record()
            assertEquals(Reason.READY, fresh.reason)
        }
    }

    /** A slow NAS or a slow first draw never keeps the launch screen up past the cap. */
    @Test
    fun theCapOpensIt() = runTest {
        val timed = LaunchGate(sinceStart = { testScheduler.currentTime + STARTED }, capMs = 1_500, report = { said += it })
        backgroundScope.launch { timed.openAtCap() }
        timed.homeLoaded()
        timed.homeDrawn()
        advanceTimeBy(1_500 - STARTED - 1)
        assertNull(timed.reason)
        advanceTimeBy(2)
        assertEquals(Reason.CAP, timed.reason)
    }

    @Test
    fun itOpensOnceForTheFirstReason() {
        gate.startsElsewhere()
        gate.homeLoaded()
        gate.picturesLoaded()
        gate.homeDrawn()
        assertEquals(Reason.START_SCREEN, gate.reason)
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

    @Test
    fun theLogSaysWhenAndWhy() {
        now = 120
        gate.settingsRead()
        now = 215
        gate.homeLoaded()
        now = 500
        gate.picturesLoaded()
        now = 560
        gate.homeDrawn()
        assertEquals(
            listOf(
                "Launch screen left 560 ms after the process started: ready " +
                    "(settings at 120 ms, rows at 215 ms, pictures at 500 ms, home drawn at 560 ms)",
            ),
            said,
        )
    }

    @Test
    fun theLogSaysWhatTheCapCameBefore() = runTest {
        now = 120
        gate.settingsRead()
        now = 430
        gate.homeLoaded()
        now = 1_517
        gate.openAtCap()
        assertEquals(
            listOf(
                "Launch screen left 1517 ms after the process started: cap " +
                    "(settings at 120 ms, rows at 430 ms, pictures not in, home not drawn)",
            ),
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
        /** Milliseconds from process start to the test clock's zero, since the activity starts later. */
        const val STARTED = 300L
    }
}
