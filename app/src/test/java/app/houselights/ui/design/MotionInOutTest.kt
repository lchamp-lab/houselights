package app.houselights.ui.design

import androidx.compose.animation.core.tween
import org.junit.Assert.assertEquals
import org.junit.Test

/** What comes with focus comes in the focus pull, what goes falls back, each over its own time ([HlMotion.inOut]). */
class MotionInOutTest {
    @Test
    fun focusComesInThePullAndGoesInTheFallBack() {
        assertEquals(tween<Float>(HlMotion.FOCUS_IN, easing = HlMotion.focusPull), HlMotion.inOut<Float>(true))
        assertEquals(tween<Float>(HlMotion.FOCUS_OUT, easing = HlMotion.fallBack), HlMotion.inOut<Float>(false))
    }

    @Test
    fun aPlateTakesItsOwnTimes() {
        assertEquals(
            tween<Float>(HlMotion.PLATE_IN, easing = HlMotion.focusPull),
            HlMotion.inOut<Float>(true, HlMotion.PLATE_IN, HlMotion.PLATE_OUT),
        )
        assertEquals(
            tween<Float>(HlMotion.PLATE_OUT, easing = HlMotion.fallBack),
            HlMotion.inOut<Float>(false, HlMotion.PLATE_IN, HlMotion.PLATE_OUT),
        )
    }
}
