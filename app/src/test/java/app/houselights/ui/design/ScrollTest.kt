package app.houselights.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/** A list of the screen's height (540 dp at density 1) keeping focus 28 dp from both ends. */
@OptIn(ExperimentalFoundationApi::class)
class ScrollTest {
    private val edge = HlFocus.edge.value
    private val screen = 540f
    private val list = EdgeScroll(edge, edge)

    /** A detail page, which brings each block to the top. */
    private val page = EdgeScroll(edge, edge, toTop = true)

    /** Where the element starts once [scroll] has moved for it. */
    private fun landing(offset: Float, size: Float, scroll: EdgeScroll = list) =
        offset - scroll.calculateScrollDistance(offset, size, screen)

    @Test
    fun anElementBelowStopsAnEdgeFromTheBottom() {
        val size = 168f
        assertEquals(screen - edge, landing(offset = 480f, size = size) + size)
        // Partly hidden at the bottom of the screen, against its edge: it moves up too.
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

    /** A detail page: the block focus goes to comes to the keyline, wherever it starts. */
    @Test
    fun aPageOfBlocksBringsTheNextOneToTheTop() {
        listOf(
            Triple("a row below the hero, not just above the bottom", 480f, 222f),
            Triple("a row partly hidden at the bottom", 400f, 222f),
            Triple("a block half way up, asked again on the next frame", 200f, 222f),
            Triple("a hero taller than the window", -300f, 600f),
        ).forEach { (case, offset, size) -> assertEquals(case, edge, landing(offset, size, page)) }
    }

    @Test
    fun aBlockAtTheKeylineStays() {
        assertEquals(0f, page.calculateScrollDistance(edge, 222f, screen))
        assertEquals(0f, page.calculateScrollDistance(edge + 0.2f, 222f, screen))
    }

    private fun alpha(left: Float, top: Float, right: Float, bottom: Float) =
        safeAreaAlpha(Offset(left, top), Size(right - left, bottom - top), Size(960f, screen), HlSpace.safeH.value, HlSpace.safeV.value)

    @Test
    fun textInsideTheSafeAreaIsWhole() {
        assertEquals(1f, alpha(48f, 28f, 912f, 512f))
        assertEquals(1f, alpha(200f, 300f, 400f, 326f))
    }

    @Test
    fun textAtTheEdgeOfTheScreenIsGone() {
        assertEquals(0f, alpha(-20f, 300f, 60f, 320f))
        assertEquals(0f, alpha(900f, 300f, 980f, 320f))
        assertEquals(0f, alpha(100f, -4f, 300f, 22f))
        assertEquals(0f, alpha(100f, 526f, 300f, 552f))
    }

    @Test
    fun textInTheMarginFadesByTheSideThatComesClosest() {
        // Half way into the bottom margin.
        assertEquals(0.5f, alpha(100f, 500f, 300f, 526f), 0.001f)
        // Nearer the bottom edge than the left one: the bottom decides.
        assertEquals(0.5f, alpha(36f, 500f, 300f, 526f), 0.001f)
        assertEquals(0.25f, alpha(12f, 300f, 300f, 320f), 0.001f)
    }
}
