package app.houselights.ui.design

import org.junit.Assert.assertEquals
import org.junit.Test

/** A receding card dims and desaturates in a few steps, so its image draws again a few times, not once per frame. */
class RecedeTest {

    @Test
    fun aFrameByFrameRecedeTakesAFewSteps() {
        // FOCUS_IN at 60 frames per second.
        val frames = HlMotion.FOCUS_IN * 60 / 1000
        val steps = (0..frames).map { HlFocus.recedeStep(it / frames.toFloat()) }
        assertEquals((0..HlFocus.RECEDE_STEPS).toList(), steps.distinct())
        assertEquals(steps, steps.sorted())
    }

    @Test
    fun theStepsRunFromFullColourToTheSiblingsShare() {
        assertEquals(1f, HlFocus.saturationAt(0), 0.0001f)
        assertEquals(HlFocus.SIBLING_SATURATION, HlFocus.saturationAt(HlFocus.RECEDE_STEPS), 0.0001f)
        assertEquals(1f, HlFocus.brightnessAt(0), 0.0001f)
        assertEquals(HlFocus.SIBLING_BRIGHTNESS, HlFocus.brightnessAt(HlFocus.RECEDE_STEPS), 0.0001f)
    }

    @Test
    fun anOvershootStaysInRange() {
        assertEquals(0, HlFocus.recedeStep(-0.1f))
        assertEquals(HlFocus.RECEDE_STEPS, HlFocus.recedeStep(1.1f))
    }
}
