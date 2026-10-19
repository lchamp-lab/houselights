package app.houselights.ui.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.houselights.testing.children
import app.houselights.testing.descendants
import app.houselights.testing.xmlRoot
import app.houselights.ui.testing.argb
import app.houselights.ui.testing.resourceArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import kotlin.math.hypot

/** The icon, banner and launch mark are vectors in Gradation's geometry and light, inside their circle. */
class BrandTest {
    private val drawables = File("src/main/res/drawable")

    /** The mark's light: ember's two ends, at the gradient's two ends. */
    private val light = listOf(0f to argb(HouselightsColors.emberFrom), 1f to argb(HouselightsColors.emberTo))

    @Test
    fun theIconIsTheMarkInsideTheSafeCircle() {
        val bars = bars("ic_launcher_foreground")
        assertBars(Gradation.bars(MIDDLE, MIDDLE, Gradation.ICON), bars)
        assertInside(bars, Gradation.SAFE_CIRCLE / 2)
        val icon = xmlRoot(File("src/main/res/mipmap-anydpi/ic_launcher.xml"))
        assertEquals("adaptive-icon", icon.tagName)
        assertEquals("@color/room", icon.children("background").single().getAttribute("android:drawable"))
        assertEquals("@drawable/ic_launcher_foreground", icon.children("foreground").single().getAttribute("android:drawable"))
    }

    /** Half as tall as the circle Android 9 shows, so its corners stay well inside, and centred like the icon. */
    @Test
    fun theLaunchMarkSitsWellInsideTheCircleAndroidShows() {
        val bars = bars("splash_mark")
        assertBars(Gradation.bars(MIDDLE, MIDDLE, Gradation.LAUNCH), bars)
        assertEquals(Gradation.LAUNCH_CIRCLE / 2, bars.first().height, EXACT)
        assertInside(bars, Gradation.LAUNCH_CIRCLE / 2 * WELL_INSIDE)
    }

    /** A title card: the mark above, 44 of the 180 units tall, the name under it. */
    @Test
    fun theBannerSetsTheMarkAboveTheName() {
        val paths = paths("banner")
        val mark = lit(paths.filter { it.gradient != null })
        assertBars(Gradation.bars(BANNER_WIDTH / 2, BANNER_MARK_TOP + BANNER_MARK / 2, BANNER_MARK), mark)
        assertEquals("the room, the mark, the name's eleven letters", 1 + 3 + 11, paths.size)
    }

    @Test
    fun theWidthsHalveOnOneGap() {
        val bars = bars("splash_mark")
        bars.zipWithNext().forEach { (a, b) -> assertEquals(a.width / 2, b.width, EXACT) }
        val unit = bars.last().width
        assertEquals(Gradation.units * unit, bars.last().right - bars.first().left, EXACT)
        assertEquals(bars.first().height, bars.last().right - bars.first().left, EXACT)
    }

    /** The bars take the mark's light; everything else is flat, the room black and the name white. */
    @Test
    fun theBrandIsDrawnInThePalette() {
        val flat = listOf(HlPalette.N96, HlPalette.N0).map(::argb).toSet()
        listOf("ic_launcher_foreground", "splash_mark", "banner", "wordmark").forEach { name ->
            paths(name).forEach { path ->
                val gradient = path.gradient
                if (gradient != null) {
                    assertEquals("$name: the ember light", light, gradient.stops)
                } else {
                    assertTrue("$name draws in ${path.fill}", path.fill in flat)
                }
            }
        }
        assertEquals(setOf(argb(HlPalette.N96)), paths("wordmark").map { it.fill }.toSet())
    }

    /** A vector's path: its flat fill as #AARRGGBB, or its gradient, and its data. */
    private data class VectorPath(val fill: String?, val gradient: Gradient?, val data: String)

    /** A linear gradient from (startX, startY) to (endX, endY), in the viewport's units; its stops by offset. */
    private data class Gradient(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val stops: List<Pair<Float, String>>,
    )

    private fun bars(name: String): List<Rect> = lit(paths(name))

    /**
     * The bars of [paths], each in the mark's light: one horizontal gradient, the same for all, from the
     * widest bar's left edge to the thinnest bar's right.
     */
    private fun lit(paths: List<VectorPath>): List<Rect> {
        val bars = paths.map { rect(it.data) }
        paths.forEach { path ->
            assertNull("${path.data}: a flat bar", path.fill)
            val gradient = path.gradient ?: error("${path.data}: no gradient")
            assertEquals("${path.data}: the ember light", light, gradient.stops)
            assertEquals("starts on the widest bar's left edge", bars.first().left, gradient.startX, EXACT)
            assertEquals("ends on the thinnest bar's right edge", bars.last().right, gradient.endX, EXACT)
            assertEquals("horizontal", gradient.startY, gradient.endY, EXACT)
        }
        return bars
    }

    /** Each path of a vector drawable, its gradient read from its inline `android:fillColor`. */
    private fun paths(name: String): List<VectorPath> =
        xmlRoot(drawables.resolve("$name.xml")).descendants("path").map { path ->
            val fill = path.getAttribute("android:fillColor").takeIf { it.isNotEmpty() }?.let(::resourceArgb)
            val gradient = path.children("aapt:attr").singleOrNull { it.getAttribute("name") == "android:fillColor" }
                ?.let { gradient(it.children("gradient").single()) }
            VectorPath(fill, gradient, path.getAttribute("android:pathData"))
        }

    private fun gradient(gradient: Element): Gradient {
        assertEquals("linear", gradient.getAttribute("android:type"))
        fun at(name: String) = gradient.getAttribute("android:$name").toFloat()
        val stops = gradient.children("item").map { stop ->
            stop.getAttribute("android:offset").toFloat() to resourceArgb(stop.getAttribute("android:color"))
        }
        return Gradient(at("startX"), at("startY"), at("endX"), at("endY"), stops)
    }

    /** A bar as its path draws it: `M x y h w v h h -w z`. */
    private fun rect(data: String): Rect {
        val found = Regex("""^M([\d.]+) ([\d.]+)h([\d.]+)v([\d.]+)h-[\d.]+z$""").find(data) ?: error("Not a bar: $data")
        val (x, y) = found.groupValues[1].toFloat() to found.groupValues[2].toFloat()
        return Rect(x, y, x + found.groupValues[3].toFloat(), y + found.groupValues[4].toFloat())
    }

    private fun assertBars(expected: List<Rect>, actual: List<Rect>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) ->
            listOf(e.left to a.left, e.top to a.top, e.right to a.right, e.bottom to a.bottom).forEach { (x, y) ->
                assertEquals("$e drawn as $a", x, y, EXACT)
            }
        }
    }

    private fun assertInside(bars: List<Rect>, radius: Float) {
        val corners = bars.flatMap { listOf(it.topLeft, it.topRight, it.bottomLeft, it.bottomRight) }
        corners.forEach { corner: Offset ->
            val distance = hypot(corner.x - MIDDLE, corner.y - MIDDLE)
            assertTrue("$corner is $distance from the middle, past $radius", distance <= radius)
        }
    }

    private companion object {
        const val MIDDLE = Gradation.LAYER / 2
        const val BANNER_WIDTH = 320f
        const val BANNER_MARK = 44f
        const val BANNER_MARK_TOP = 47.5f

        /** Well inside a circle: within three quarters of its radius. */
        const val WELL_INSIDE = 0.75f

        /** The precision the resources keep for a coordinate: three decimals. */
        const val EXACT = 0.001f
    }
}
