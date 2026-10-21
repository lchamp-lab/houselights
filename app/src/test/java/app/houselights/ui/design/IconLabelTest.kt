package app.houselights.ui.design

import app.houselights.ui.design.components.labelX
import org.junit.Assert.assertEquals
import org.junit.Test

/** A tile's label sits under it: centred, else aligned with its edge, never past the label's edge. */
class IconLabelTest {
    private val screen = 1920
    private val safe = 96
    private val edge = 48
    private val button = 96

    @Test
    fun centredInTheMiddleOfTheScreen() = assertEquals(-20, labelX(button, 136, left = 800, screen = screen, edge = edge))

    @Test
    fun centredUnderAButtonAtTheEdgeOfTheSafeArea() =
        assertEquals(-20, labelX(button, 136, left = screen - safe - button, screen = screen, edge = edge))

    @Test
    fun alignedWithTheButtonsEndWhenCentredItWouldReachTheSide() =
        assertEquals(button - 136, labelX(button, 136, left = screen - edge - button, screen = screen, edge = edge))

    @Test
    fun alignedWithTheButtonsStartWhenCentredItWouldReachTheSide() =
        assertEquals(0, labelX(button, 136, left = edge, screen = screen, edge = edge))

    @Test
    fun aButtonPastTheEdgeHoldsItsLabelInside() {
        val left = screen - edge - button + 28
        assertEquals(screen - edge - left - 136, labelX(button, 136, left = left, screen = screen, edge = edge))
    }
}
