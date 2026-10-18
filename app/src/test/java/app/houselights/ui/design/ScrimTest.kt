package app.houselights.ui.design

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.luminance
import app.houselights.ui.design.components.BackdropStyle
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The left veil keeps a column's quietest text at its band's contrast over the lightest image, counting only
 * what shows under the column, and never draws a line across the picture.
 */
class ScrimTest {
    private val c = HouselightsColors

    /** 1080p at 2 px per dp: the column is `safeH` + `readableNarrow`, 568 dp. */
    private val screen = Size(1920f, 1080f)
    private val column = 1136f
    private val detail = BackdropStyle.Full.down
    private val strength = BackdropStyle.Full.leftScrim
    private val base = HlScrim.LEFT.map { VeilStop(it.at, it.alpha * strength) }

    @Test
    fun onWhiteTheFloorIsTheOneDesignMdGives() {
        assertEquals(0.85f, HlScrim.need(1f), 0.0001f)
        assertEquals(0.775f, HlScrim.need(1f, ceiling = HlScrim.ceiling(c.contentSecondary)), 0.0001f)
    }

    @Test
    fun theFloorKeepsTheQuietestTextReadable() {
        // In floats: a Color rounds its channels to 8 bits, a step the screen dithers.
        listOf(0.2f, 0.4f, 0.6f, 0.8f, 1f).forEach { grey ->
            val ground = ColorSpaces.Srgb.eotf((grey * (1f - HlScrim.need(grey))).toDouble()).toFloat()
            val ratio = (c.contentTertiary.luminance() + 0.05f) / (ground + 0.05f)
            assertTrue("grey $grey: ${"%.3f".format(ratio)}:1", ratio >= 4.5f)
        }
    }

    @Test
    fun aDarkPictureKeepsItsVeil() {
        assertEquals(base, sample(96, 54) { _, _ -> 0.1f }.leftVeil(screen, column, 1f, strength, detail))
    }

    @Test
    fun aWhitePictureGetsTheFloorOfEachBand() {
        val veil = sample(96, 54) { _, _ -> 1f }.leftVeil(screen, column, 1f, strength, detail)
        (0..100).forEach {
            val need = HlScrim.need(1f, ceiling = HlScrim.leftCeiling(band(it / 100f)))
            assertTrue("at $it %", veil.alphaAt(it / 100f) >= need - 0.0001f)
        }
        // Over the left half, the floor DESIGN.md gives.
        (0..50).forEach { assertTrue("at $it %", veil.alphaAt(it / 100f) >= HlScrim.need(1f) - 0.0001f) }
    }

    @Test
    fun theColumnBendsTheRuleOnlyThisFar() {
        assertEquals(listOf(4.5f, 4.5f), HlScrim.LEFT_CONTRAST.take(2))
        HlScrim.LEFT_CONTRAST.indices.forEach { band ->
            // The lightest ground the band allows, as the room sees it.
            val ground = Color(HlScrim.leftCeiling(band), HlScrim.leftCeiling(band), HlScrim.leftCeiling(band))
            assertTrue("band $band: the quietest text", contrast(c.contentTertiary, ground) >= 3f - 0.01f)
            assertTrue("band $band: the overview", contrast(c.contentSecondary, ground) >= 4f - 0.01f)
        }
        HlScrim.LEFT_CONTRAST.zipWithNext().forEach { (a, b) -> assertTrue(a >= b) }
    }

    @Test
    fun aDimmedPictureNeedsLess() {
        val white = sample(96, 54) { _, _ -> 1f }
        val dimmed = white.leftVeil(screen, column, 0.28f, strength, detail)
        val full = white.leftVeil(screen, column, 1f, strength, detail)
        assertTrue(dimmed.sumOf { it.alpha.toDouble() } < full.sumOf { it.alpha.toDouble() })
    }

    @Test
    fun onlyWhatShowsUnderTheColumnCounts() {
        // Light past the column, dark under it.
        assertEquals(base, sample(96, 54) { x, _ -> if (x >= 60) 1f else 0f }.leftVeil(screen, column, 1f, strength, detail))
        // Wider than the screen, the picture shows its right end: its light left end is out of view.
        assertEquals(base, sample(192, 54) { x, _ -> if (x < 96) 1f else 0f }.leftVeil(screen, column, 1f, strength, detail))
    }

    @Test
    fun aLightLeftEndDarkensItsBandsOnly() {
        // White under the column's first quarter: the floor there, the usual easing after.
        val veil = sample(96, 54) { x, _ -> if (x < 12) 1f else 0f }.leftVeil(screen, column, 1f, strength, detail)
        assertTrue(veil[1].alpha >= HlScrim.need(1f, ceiling = HlScrim.leftCeiling(0)))
        assertEquals(base.drop(2), veil.drop(2))
    }

    @Test
    fun aLightRightEndDarkensAllTheColumnBeforeIt() {
        // Light at the column's end holds the whole veil at what the last band needs.
        val veil = sample(96, 54) { x, _ -> if (x in 48..56) 1f else 0f }.leftVeil(screen, column, 1f, strength, detail)
        val need = HlScrim.need(1f, ceiling = HlScrim.leftCeiling(HlScrim.LEFT_CONTRAST.lastIndex))
        veil.forEach { assertTrue("${it.at}: ${it.alpha}", it.alpha >= need - 0.0001f) }
        veil.zipWithNext().forEach { (a, b) -> assertTrue(a.alpha >= b.alpha) }
    }

    @Test
    fun everyBandOfTheColumnReadsUnderTheCurveDrawn() {
        val pictures = mapOf<String, (Int, Int) -> Float>(
            "white" to { _, _ -> 1f },
            "light to the right" to { x, _ -> x / 95f },
            "light to the left" to { x, _ -> 1f - x / 95f },
            "light at the top" to { _, y -> 1f - y / 53f },
            "stripes" to { x, _ -> if (x / 4 % 2 == 0) 0.9f else 0.2f },
            "a light disc" to { x, y -> if (hypot(x - 30f, y - 24f) < 12f) 1f else 0.15f },
            "noise" to { x, y -> (sin(x * 12.9898f + y * 78.233f) * 43758.547f).let { it - kotlin.math.floor(it) } },
        )
        pictures.forEach { (name, grey) ->
            val veil = sample(96, 54, grey).leftVeil(screen, column, 1f, strength, detail)
            shortByBand(grey, veil).forEachIndexed { band, short ->
                assertTrue("$name, band $band: ${"%.1f".format(short * 100)} % short", short <= 1f - HlScrim.FLOOR_QUANTILE + 0.01f)
            }
        }
    }

    @Test
    fun noVeilDrawsALine() {
        val floored = sample(96, 54) { x, _ -> x / 95f }.leftVeil(screen, column, 1f, strength, detail)
        listOf(
            "left" to base,
            "floored" to floored,
            "detail" to detail,
            "home" to BackdropStyle.Hero.down,
            "wall across" to HlScrim.WALL_ACROSS,
            "wall down" to HlScrim.WALL_DOWN,
            "library" to BackdropStyle.Quiet.down,
        ).forEach { (name, veil) ->
            veil.forEach { stop ->
                // The slope just before and just after a stop is the same: no angle where curves meet.
                val before = (veil.alphaAt(stop.at) - veil.alphaAt(stop.at - STEP)) / STEP
                val after = (veil.alphaAt(stop.at + STEP) - veil.alphaAt(stop.at)) / STEP
                if (stop.at - STEP >= veil.first().at && stop.at + STEP <= veil.last().at) {
                    assertEquals("$name at ${stop.at}", before, after, 0.05f)
                }
            }
            // Between two stops it stays between them: no bulge past a stop.
            veil.zipWithNext().forEach { (a, b) ->
                (1..9).forEach {
                    val alpha = veil.alphaAt(a.at + (b.at - a.at) * it / 10f)
                    val within = minOf(a.alpha, b.alpha) - 0.0001f..maxOf(a.alpha, b.alpha) + 0.0001f
                    assertTrue("$name between ${a.at} and ${b.at}", alpha in within)
                }
            }
        }
    }

    @Test
    fun aQuietPictureStaysInTheRoomWhereTextStillReads() {
        // Its fade stops short of black, yet over a white picture the quietest text keeps 4.5:1 there.
        val quiet = BackdropStyle.Quiet
        assertTrue(quiet.foot < 0.5f)
        assertEquals(0f, HlScrim.need(quiet.imageAlpha * (1f - quiet.foot)), 0f)
        // A picture shown whole still fades into black.
        assertEquals(1f, BackdropStyle.Full.foot, 0f)
        assertEquals(1f, BackdropStyle.Hero.foot, 0f)
    }

    @Test
    fun theGradientFollowsTheCurve() {
        val stops = detail.colorStops(c.background)
        stops.forEach { (at, color) -> assertEquals(detail.alphaAt(at), color.alpha, 0.004f) }
        assertEquals(0f, stops.first().first, 0f)
        assertEquals(1f, stops.last().first, 0f)
    }

    @Test
    fun aWhitePictureStepsBackFarEnoughForTheQuietestText() {
        val white = sample(32, 18) { _, _ -> 1f }
        assertTrue(white.floorAcross(screen, 1f, detail) >= HlScrim.need(1f) - 0.0001f)
    }

    @Test
    fun aDarkPictureStepsBackByItsVeilAlone() {
        // `Backdrop` dims by VEIL at least: a dark picture needs no more.
        val dark = sample(32, 18) { _, _ -> 0.1f }
        assertTrue(dark.floorAcross(screen, 1f, detail) <= HlAlpha.VEIL)
    }

    @Test
    fun aLightCornerOutsideTheColumnCountsWhenThePageRunsTheWidth() {
        // White on the last quarter only, past the text column: the column ignores it, the page does not.
        val right = sample(32, 18) { x, _ -> if (x >= 24) 1f else 0.1f }
        assertEquals(base, right.leftVeil(screen, column, 1f, strength, detail))
        assertTrue(right.floorAcross(screen, 1f, detail) >= HlScrim.need(1f) - 0.0001f)
    }

    /** Computed per row and band, the stops match the point-by-point rule ([pointByPoint]) to the last bit. */
    @Test
    fun theVeilIsTheSameReckonedRowByRow() {
        val pictures = listOf<(Int, Int) -> Float>(
            { _, _ -> 1f },
            { x, _ -> x / 95f },
            { _, y -> 1f - y / 53f },
            { x, y -> (sin(x * 12.9898f + y * 78.233f) * 43758.547f).let { it - kotlin.math.floor(it) } },
        )
        val samples = pictures.flatMap { grey ->
            listOf(96 to 54, 192 to 54, 96 to 40).map { (w, h) -> Triple(w, h, FloatArray(w * h) { grey(it % w, it / w) }) }
        }
        val screens = listOf(screen, Size(1280f, 720f), Size(1920f, 800f))
        val cases = samples.flatMap { sample -> screens.flatMap { shown -> BackdropStyle.entries.map { Triple(sample, shown, it) } } }
        cases.forEach { (sample, shown, style) ->
            val (w, h, points) = sample
            assertEquals(
                "$w × $h on $shown, $style",
                pointByPoint(w, h, points, shown, style.imageAlpha, style.leftScrim, style.down),
                ArtworkSample(w, h, points).leftVeil(shown, column, style.imageAlpha, style.leftScrim, style.down),
            )
            assertEquals(
                "across, $w × $h on $shown, $style",
                floorPointByPoint(w, h, points, shown, style.imageAlpha, style.down),
                ArtworkSample(w, h, points).floorAcross(shown, style.imageAlpha, style.down),
                0f,
            )
        }
    }

    /** Point-by-point reference for [ArtworkSample.floorAcross], through the veils down. */
    @Suppress("LongParameterList")
    private fun floorPointByPoint(width: Int, height: Int, grey: FloatArray, screen: Size, imageAlpha: Float, down: List<VeilStop>): Float {
        val scale = maxOf(screen.width / width, screen.height / height)
        val left = screen.width - width * scale
        val cols = kotlin.math.ceil(-left / scale - 0.5f).toInt().coerceAtLeast(0)..(width - 1)
        val rows = 0..kotlin.math.floor(screen.height / scale - 0.5f).toInt().coerceAtMost(height - 1)
        val needs = FloatArray(cols.count() * rows.count())
        var count = 0
        for (row in rows) {
            val y = (row + 0.5f) * scale
            val through = imageAlpha * (1f - down.alphaAt(y / screen.height))
            for (col in cols) needs[count++] = HlScrim.need(grey[row * width + col] * through)
        }
        needs.sort()
        return needs[(needs.lastIndex * HlScrim.FLOOR_QUANTILE).roundToInt()]
    }

    /** Point-by-point reference for [ArtworkSample.leftVeil], through the veils down. */
    @Suppress("LongParameterList")
    private fun pointByPoint(
        width: Int,
        height: Int,
        grey: FloatArray,
        screen: Size,
        imageAlpha: Float,
        strength: Float,
        down: List<VeilStop>,
    ): List<VeilStop> {
        val veil = HlScrim.LEFT.map { VeilStop(it.at, it.alpha * strength) }
        val scale = maxOf(screen.width / width, screen.height / height)
        val left = screen.width - width * scale
        val firstCol = kotlin.math.ceil(-left / scale - 0.5f).toInt().coerceAtLeast(0)
        val cols = firstCol..kotlin.math.floor((column - left) / scale - 0.5f).toInt().coerceAtMost(width - 1)
        val rows = 0..kotlin.math.floor(screen.height / scale - 0.5f).toInt().coerceAtMost(height - 1)
        if (cols.isEmpty()) return veil
        val bands = veil.size - 1
        val needs = Array(bands) { FloatArray(cols.count() * rows.count()) }
        val counts = IntArray(bands)
        for (col in cols) {
            val x = left + (col + 0.5f) * scale
            val band = (x / column * bands).toInt().coerceIn(0, bands - 1)
            val held = veil.alphaAt(x / column)
            for (row in rows) {
                val y = (row + 0.5f) * scale
                val through = imageAlpha * (1f - down.alphaAt(y / screen.height))
                val need = HlScrim.need(grey[row * width + col] * through, HlScrim.leftCeiling(band))
                needs[band][counts[band]++] = if (need > held) need else 0f
            }
        }
        val floors = FloatArray(bands) { band ->
            val points = needs[band].copyOf(counts[band]).apply { sort() }
            if (points.isEmpty()) 0f else points[(points.lastIndex * HlScrim.FLOOR_QUANTILE).roundToInt()]
        }
        for (band in bands - 2 downTo 0) floors[band] = maxOf(floors[band], floors[band + 1])
        return veil.mapIndexed { i, stop -> VeilStop(stop.at, maxOf(stop.alpha, floors[(i - 1).coerceAtLeast(0)])) }
    }

    /** Per band of the column, the share of a 96 × 54 picture's points left under contrast by [veil]. */
    private fun shortByBand(grey: (x: Int, y: Int) -> Float, veil: List<VeilStop>): List<Float> {
        val scale = screen.width / 96
        val bands = veil.size - 1
        val short = IntArray(bands)
        val all = IntArray(bands)
        for (col in 0 until 96) {
            val x = (col + 0.5f) * scale
            if (x > column) continue
            val band = band(x / column)
            for (row in 0 until 54) {
                val y = (row + 0.5f) * scale
                val shown = grey(col, row) * (1f - detail.alphaAt(y / screen.height))
                val need = HlScrim.need(shown, HlScrim.leftCeiling(band))
                all[band]++
                if (need > veil.alphaAt(x / column) + 0.0001f) short[band]++
            }
        }
        return List(bands) { short[it].toFloat() / all[it] }
    }

    /** The band of the column at [at], a fraction of its width. */
    private fun band(at: Float): Int {
        val bands = HlScrim.LEFT.size - 1
        return (at * bands).toInt().coerceIn(0, bands - 1)
    }

    private fun sample(width: Int, height: Int, grey: (x: Int, y: Int) -> Float) =
        ArtworkSample(width, height, FloatArray(width * height) { grey(it % width, it / width) })

    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    private companion object {
        /** Small enough that a curve's slope barely changes over it, while an angle changes it at once. */
        const val STEP = 0.0002f
    }
}
