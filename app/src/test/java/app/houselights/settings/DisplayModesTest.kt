package app.houselights.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayModesTest {
    /** What the Zidoo Z1000 Pro lists while its HDMI signal is 2160p60: stand-in 1080p modes. */
    private val zidoo = listOf(60f, 50f, 30f, 25f, 24f).mapIndexed { i, hz -> OutputMode(1920, 1080, hz, i + 1) }

    /** A box that lists the TV's real modes. */
    private val real = listOf(
        OutputMode(3840, 2160, 60f, 1),
        OutputMode(3840, 2160, 59.94f, 2),
        OutputMode(3840, 2160, 50f, 3),
        OutputMode(3840, 2160, 23.976f, 4),
        OutputMode(1920, 1080, 60f, 5),
    )

    @Test
    fun standInModesLeaveTheOutputToThePlayer() {
        assertEquals(OutputControl.Player, DisplayModes.control(zidoo))
        assertNull(DisplayModes.ui4k(zidoo))
        // A system that lists nothing says nothing true either.
        assertEquals(OutputControl.Player, DisplayModes.control(emptyList()))
    }

    @Test
    fun realModesOfferUltraHdAtSixtyHertz() {
        assertEquals(OutputControl.Ui4k, DisplayModes.control(real))
        assertEquals(1, DisplayModes.ui4k(real)?.id)
        // 59.94 Hz counts as 60 when it is all there is; DCI 4K is 4K.
        assertEquals(2, DisplayModes.ui4k(real.filter { it.id != 1 })?.id)
        assertEquals(OutputControl.Ui4k, DisplayModes.control(listOf(OutputMode(4096, 2160, 60f))))
    }

    @Test
    fun ultraHdBelowSixtyHertzLeavesTheSystemMode() {
        val slow = listOf(OutputMode(3840, 2160, 30f), OutputMode(3840, 2160, 24f), OutputMode(1920, 1080, 60f))
        assertEquals(OutputControl.System, DisplayModes.control(slow))
        assertNull(DisplayModes.ui4k(slow))
    }

    @Test
    fun aWideButShortModeIsNotUltraHd() {
        assertEquals(OutputControl.Player, DisplayModes.control(listOf(OutputMode(3840, 1080, 60f))))
    }

    /** A box that lists DCI 4K first: a 16:9 TV gets 3840 all the same. */
    @Test
    fun atTheSameRateUltraHdIs3840Wide() {
        val dciFirst = listOf(OutputMode(4096, 2160, 60f, 1), OutputMode(3840, 2160, 60f, 2), OutputMode(3840, 2160, 50f, 3))
        assertEquals(2, DisplayModes.ui4k(dciFirst)?.id)
        // Nearer to 60 Hz comes first.
        assertEquals(1, DisplayModes.ui4k(listOf(OutputMode(4096, 2160, 60f, 1), OutputMode(3840, 2160, 59.5f, 2)))?.id)
    }
}
