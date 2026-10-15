package app.houselights.ui.design

import app.houselights.ui.design.components.labelX
import org.junit.Assert.assertEquals
import org.junit.Test

/** A round button's label sits under it: centred, else aligned with its edge, never past the safe area. */
class IconLabelTest {
    private val screen = 1920
    private val safe = 96
    private val button = 96

    @Test
    fun centredInTheMiddleOfTheScreen() = assertEquals(-20, labelX(button, 136, left = 800, screen = screen, safe = safe))

    @Test
    fun alignedWithTheButtonsEndNearTheRightEdge() =
        assertEquals(button - 136, labelX(button, 136, left = screen - safe - button, screen = screen, safe = safe))

    @Test
    fun alignedWithTheButtonsStartNearTheLeftEdge() = assertEquals(0, labelX(button, 136, left = safe, screen = screen, safe = safe))

    @Test
    fun aLabelWiderThanTheRoomStaysInsideTheSafeArea() {
        val left = screen - safe - button
        assertEquals(screen - safe - left - 300, labelX(button, 300, left = left, screen = screen, safe = safe))
    }
}
