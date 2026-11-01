package app.houselights.ui.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round

/** The icon, banner and launch mark are vectors in Gradation's geometry and light, inside their circle; the lockup sets it by the name. */
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

    /**
     * The lockup as its reference draws it, capitals 100 units tall on the baseline y = 0: the mark 1.4 times
     * their height and centred on them, in one light across it, the name a wide bar's width after it.
     */
    @Test
    fun theLockupSetsTheMarkBesideTheName() {
        val bars = Gradation.lockupBars(CAPITALS, -CAPITALS / 2)
        assertBars(LOCKUP_BARS, bars)
        assertEquals(LOCKUP_NAME, Gradation.lockupIndent * CAPITALS, EXACT)
        assertEquals("the gap is the wide bar", bars.first().width, Gradation.lockupGap * CAPITALS, EXACT)
        val c = HouselightsColors
        assertEquals(Brush.horizontalGradient(listOf(c.emberFrom, c.emberTo), startX = 0f, endX = LOCKUP_MARK), Gradation.light(bars, c))

        // The name is wordmark.xml: as wide as the reference's, its box's middle that of its capitals.
        val name = xmlRoot(drawables.resolve("wordmark.xml"))
        assertEquals(LOCKUP_WIDTH - LOCKUP_NAME, name.getAttribute("android:viewportWidth").toFloat(), EXACT)
        val baseline = name.children("group").single().getAttribute("android:translateY").toFloat()
        assertEquals(baseline - CAPITALS / 2, name.getAttribute("android:viewportHeight").toFloat() / 2, ROUND_OVERSHOOT)
    }

    /** On screen, home's lockup (11.5 dp capitals, 23 px at 1080p) has every edge on a whole pixel, within one of its line. */
    @Test
    fun onScreenTheLockupsBarsFallOnWholePixels() {
        val capitals = HOME_CAPITALS_PX
        val drawn = Gradation.lockupBars(capitals, capitals / 2)
        val whole = Gradation.lockupBars(capitals, capitals / 2, whole = true)
        whole.flatMap { listOf(it.left, it.top, it.right, it.bottom) }.forEach { assertEquals(it, round(it), 0f) }
        whole.zip(drawn).forEach { (bar, line) ->
            listOf(bar.left - line.left, bar.right - line.right, bar.top - line.top).forEach { assertTrue(abs(it) <= HALF_PIXEL) }
        }
        whole.zipWithNext().forEach { (a, b) -> assertTrue("${a.width} then ${b.width}", a.width > b.width && b.left > a.right) }
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

        /** The reference lockup (`lockup-ember.svg`, viewBox 0 −120 1399.069 140): its capitals, mark, name and width. */
        const val CAPITALS = 100f
        const val LOCKUP_MARK = 140f
        const val LOCKUP_NAME = 202.222f
        const val LOCKUP_WIDTH = 1399.069f
        val LOCKUP_BARS = listOf(Rect(0f, -120f, 62.222f, 20f), Rect(78.4f, -120f, 109.511f, 20f), Rect(124.444f, -120f, 140f, 20f))

        /** How far the name's box sits from its capitals' middle: its round letters overshoot alike above and below. */
        const val ROUND_OVERSHOOT = 0.2f

        /** Home's lockup capitals at 1080p (`HomeGridDefaults.lockup` 11.5 dp), and how far a rounded edge may move. */
        const val HOME_CAPITALS_PX = 23f
        const val HALF_PIXEL = 0.5f
    }
}
