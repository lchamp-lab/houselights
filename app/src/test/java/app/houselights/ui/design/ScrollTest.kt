package app.houselights.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import org.junit.Assert.assertEquals
import org.junit.Test

/** A list of the screen's height (540 dp at density 1) keeping focus 28 dp from both ends. */
@OptIn(ExperimentalFoundationApi::class)
class ScrollTest {
    private val edge = HlFocus.edge.value
    private val screen = 540f
    private val list = EdgeScroll(edge, edge)

    /** Where the element starts once the list has scrolled for it. */
    private fun landing(offset: Float, size: Float) = offset - list.calculateScrollDistance(offset, size, screen)

    @Test
    fun anElementBelowStopsAnEdgeFromTheBottom() {
        val size = 168f
        assertEquals(screen - edge, landing(offset = 480f, size = size) + size)
        // Partly hidden at the foot of the screen, against its edge: it moves up too.
        assertEquals(screen - edge, landing(offset = screen - size, size = size) + size)
    }

    @Test
    fun anElementAboveStopsAnEdgeFromTheTop() {
        assertEquals(edge, landing(offset = -100f, size = 168f))
    }

    @Test
    fun anElementInsideDoesNotMove() {
        assertEquals(0f, list.calculateScrollDistance(edge, 168f, screen))
        assertEquals(0f, list.calculateScrollDistance(200f, 168f, screen))
        assertEquals(0f, list.calculateScrollDistance(screen - edge - 168f, 168f, screen))
    }

    @Test
    fun anElementLongerThanTheWindowAlignsWithItsTop() {
        val size = screen - edge
        assertEquals(edge, landing(offset = 300f, size = size))
        assertEquals(edge, landing(offset = -200f, size = size))
        assertEquals(edge, landing(offset = 0f, size = size))
    }

    @Test
    fun aListUnderAHeaderBringsItsRowsBackToItsTop() {
        // The rows of home: the list starts under the hero, a row is taller than the window.
        val home = EdgeScroll(0f, edge)
        assertEquals(0f, home.calculateScrollDistance(0f, 222f, 244f))
        assertEquals(230f, home.calculateScrollDistance(230f, 222f, 244f))
    }
}
