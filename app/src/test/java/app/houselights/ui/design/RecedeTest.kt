package app.houselights.ui.design

import org.junit.Assert.assertEquals
import org.junit.Test

/** A receding card desaturates in a few steps, so its image recomposes a few times, not once per frame. */
class RecedeTest {

    @Test
    fun aFrameByFrameRecedeTakesAFewSteps() {
        // 250 ms at 60 frames per second.
        val steps = (0..15).map { HlFocus.recedeStep(it / 15f) }
        assertEquals((0..HlFocus.RECEDE_STEPS).toList(), steps.distinct())
        assertEquals(steps, steps.sorted())
    }

    @Test
    fun theStepsRunFromFullColourToTheSiblingsShare() {
        assertEquals(0, HlFocus.recedeStep(0f))
        assertEquals(HlFocus.RECEDE_STEPS, HlFocus.recedeStep(1f))
        assertEquals(1f, HlFocus.saturationAt(0), 0.0001f)
        assertEquals(HlFocus.SIBLING_SATURATION, HlFocus.saturationAt(HlFocus.RECEDE_STEPS), 0.0001f)
    }

    @Test
    fun anOvershootStaysInRange() {
        assertEquals(0, HlFocus.recedeStep(-0.1f))
        assertEquals(HlFocus.RECEDE_STEPS, HlFocus.recedeStep(1.1f))
    }
}
