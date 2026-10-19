package app.houselights.ui.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import app.houselights.testing.children
import app.houselights.testing.descendants
import app.houselights.testing.xmlRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/** The icon, banner and launch mark are vectors in Gradation's geometry and palette, inside their circle. */
class BrandTest {
    private val drawables = File("src/main/res/drawable")

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
        val mark = paths.filter { it.first == argb(HlPalette.Ember.toArgb()) }.map { rect(it.second) }
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

    @Test
    fun theBrandIsDrawnInThePalette() {
        val palette = listOf(HlPalette.Ember, HlPalette.N96, HlPalette.N0).map { argb(it.toArgb()) }.toSet()
        listOf("ic_launcher_foreground", "splash_mark", "banner", "wordmark").forEach { name ->
            paths(name).forEach { (fill, _) -> assertTrue("$name draws in $fill", fill in palette) }
        }
        assertEquals(setOf(argb(HlPalette.N96.toArgb())), paths("wordmark").map { it.first }.toSet())
    }

    private fun bars(name: String): List<Rect> = paths(name).map { (fill, data) ->
        assertEquals("$name: a bar of ember", argb(HlPalette.Ember.toArgb()), fill)
        rect(data)
    }

    /** Each path of a vector drawable: its fill as #AARRGGBB, and its data. */
    private fun paths(name: String): List<Pair<String, String>> =
        xmlRoot(drawables.resolve("$name.xml")).descendants("path").map { path ->
            val fill = path.getAttribute("android:fillColor").removePrefix("#").uppercase().padStart(ARGB_DIGITS, 'F')
            "#$fill" to path.getAttribute("android:pathData")
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

    private fun argb(color: Int) = "#%08X".format(color)

    private companion object {
        const val MIDDLE = Gradation.LAYER / 2
        const val BANNER_WIDTH = 320f
        const val BANNER_MARK = 44f
        const val BANNER_MARK_TOP = 47.5f

        /** Well inside a circle: within three quarters of its radius. */
        const val WELL_INSIDE = 0.75f

        /** The precision the resources keep for a coordinate: three decimals. */
        const val EXACT = 0.001f

        const val ARGB_DIGITS = 8
    }
}
