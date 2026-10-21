package app.houselights.ui.design

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.luminance
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.deepOf
import app.houselights.ui.testing.contrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The left veil keeps a column's quietest text at its band's contrast over the lightest image, counting only
 * what shows under the column and the tint of the veils over it; plates keep theirs; no veil draws a line.
 */
class ScrimTest {
    private val c = HouselightsColors

    /** 1080p at 2 px per dp: the column is `safeH` + `readableNarrow`, 568 dp. */
    private val screen = Size(1920f, 1080f)
    private val column = 1136f
    private val detail = BackdropStyle.Full.veils
    private val home = BackdropStyle.Hero.veils
    private val base = detail.left

    /** The neutral dark as a grey, and the brightest a picture's dark may be. */
    private val deep = greyOf(c.ambientDeep)
    private val brightest = srgb(HlScrim.DEEP_LUMINANCE_MAX)

    @Test
    fun onWhiteTheFloorIsTheOneDesignMdGives() {
        // A veil in the picture's dark lightens what it covers: 0.915 at its brightest, where a black one needs 0.85.
        assertEquals(0.915f, HlScrim.need(1f, tint = brightest), 0.0001f)
        assertEquals(0.91f, HlScrim.need(1f, tint = deep), 0.0001f)
        assertEquals(0.85f, HlScrim.need(1f), 0.0001f)
        assertEquals(0.83f, HlScrim.need(1f, ceiling = HlScrim.ceiling(c.contentSecondary), tint = brightest), 0.0001f)
    }

    @Test
    fun theFloorKeepsTheQuietestTextReadable() {
        // In floats: a Color rounds its channels to 8 bits, a step the screen dithers.
        listOf(0f, brightest, greyOf(Color(0xFF1C1B1B))).forEach { tint ->
            listOf(0.2f, 0.4f, 0.6f, 0.8f, 1f).forEach { grey ->
                val ground = linear(grey + (tint - grey) * HlScrim.need(grey, tint = tint))
                assertTrue("tint $tint, grey $grey: ${ratio(ground)}:1", ratio(ground) >= 4.5f)
            }
        }
        // A coloured dark, blended channel by channel as the screen does.
        listOf(0f, 60f, 140f, 220f, 300f).map { deepOf(it, 1f) }.forEach { dark ->
            listOf(0.2f, 0.6f, 1f).forEach { grey ->
                val alpha = HlScrim.need(grey, tint = greyOf(dark))
                val ground = luminanceOf(dark.red, dark.green, dark.blue) { grey + (it - grey) * alpha }
                assertTrue("$dark, grey $grey: ${ratio(ground)}:1", ratio(ground) >= 4.5f)
            }
        }
    }

    @Test
    fun aPicturesDarkIsDarkEnough() {
        assertTrue(c.ambientDeep.luminance() <= HlScrim.DEEP_LUMINANCE_MAX)
        assertTrue(brightest <= HlScrim.ceiling(c.contentTertiary))
    }

    @Test
    fun aDarkPictureKeepsItsVeil() {
        assertEquals(base, sample(96, 54) { _, _ -> 0.1f }.leftVeil(screen, column, detail, brightest))
    }

    @Test
    fun aWhitePictureGetsTheFloorOfEachBand() {
        // Over a light base, every band of a white picture is short of its floor and takes it.
        val veil = sample(96, 54) { _, _ -> 1f }.leftVeil(screen, column, home.copy(left = HlScrim.LEFT_QUIET), brightest)
        (0..100).forEach {
            val need = HlScrim.need(1f, ceiling = HlScrim.leftCeiling(band(it / 100f)), tint = brightest)
            assertTrue("at $it %", veil.alphaAt(it / 100f) >= need - 0.0001f)
        }
        // Over the left half, the floor DESIGN.md gives.
        (0..50).forEach { assertTrue("at $it %", veil.alphaAt(it / 100f) >= 0.915f - 0.0001f) }
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
        val dimmed = white.leftVeil(screen, column, detail.copy(imageAlpha = HlScrim.QUIET_PICTURE), deep)
        val full = white.leftVeil(screen, column, detail, deep)
        assertTrue(dimmed.sumOf { it.alpha.toDouble() } < full.sumOf { it.alpha.toDouble() })
    }

    @Test
    fun onlyWhatShowsUnderTheColumnCounts() {
        // Light past the column, dark under it.
        assertEquals(base, sample(96, 54) { x, _ -> if (x >= 60) 1f else 0f }.leftVeil(screen, column, detail, deep))
        // Wider than the screen, the picture shows its right end: its light left end is out of view.
        assertEquals(base, sample(192, 54) { x, _ -> if (x < 96) 1f else 0f }.leftVeil(screen, column, detail, deep))
    }

    @Test
    fun aLightLeftEndDarkensItsBandsOnly() {
        // White under the column's first quarter of home: the floor there, the usual easing after.
        val veil = sample(96, 54) { x, _ -> if (x < 12) 1f else 0f }.leftVeil(screen, column, home, deep)
        assertTrue(veil[1].alpha >= HlScrim.need(1f, ceiling = HlScrim.leftCeiling(0), tint = deep))
        assertEquals(home.left.drop(2), veil.drop(2))
    }

    @Test
    fun aLightRightEndDarkensAllTheColumnBeforeIt() {
        // Light at the column's end holds the whole veil at what the last band needs.
        val veil = sample(96, 54) { x, _ -> if (x in 48..56) 1f else 0f }.leftVeil(screen, column, detail, deep)
        val need = HlScrim.need(1f, ceiling = HlScrim.leftCeiling(HlScrim.LEFT_CONTRAST.lastIndex), tint = deep)
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
        listOf("home" to home, "detail" to detail).forEach { (style, veils) ->
            pictures.forEach { (name, grey) ->
                val veil = sample(96, 54, grey).leftVeil(screen, column, veils, brightest)
                shortByBand(grey, veil, veils, brightest).forEachIndexed { band, short ->
                    val what = "$style, $name, band $band: ${"%.1f".format(short * 100)} % short"
                    assertTrue(what, short <= 1f - HlScrim.FLOOR_QUANTILE + 0.01f)
                }
            }
        }
    }

    @Test
    fun theTopVeilsTintCountsInTheFloor() {
        // Light only under the top veil, over a light left veil: the top's tint asks more of the left veil there.
        val veils = home.copy(left = HlScrim.LEFT_QUIET)
        val light: (Int, Int) -> Float = { _, y -> if (y < 12) 1f else 0f }
        val floors = listOf(0f, deep, greyOf(Color(0xFF1C1B1B))).map { sample(96, 54, light).leftVeil(screen, column, veils, it) }
        floors.zipWithNext().forEach { (darker, lighter) ->
            darker.zip(lighter).forEach { (a, b) -> assertTrue("${a.at}", b.alpha >= a.alpha) }
        }
        assertTrue(floors.last()[1].alpha > floors.first()[1].alpha)
        assertTrue(shortByBand(light, floors[1], veils, deep).all { it <= 1f - HlScrim.FLOOR_QUANTILE + 0.01f })
    }

    @Test
    fun noVeilDrawsALine() {
        val floored = sample(96, 54) { x, _ -> x / 95f }.leftVeil(screen, column, detail, deep)
        val veils = BackdropStyle.entries.flatMap { style ->
            listOf("${style.name} left" to style.veils.left, "${style.name} down" to style.veils.down)
        }
        (veils + listOf("floored" to floored, "wall across" to HlScrim.WALL_ACROSS, "wall down" to HlScrim.WALL_DOWN))
            .filter { it.second.isNotEmpty() }
            .forEach { (name, veil) ->
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

    /** The top veil's dark turns to the bottom's black where neither is drawn, so the change never shows. */
    @Test
    fun theTwoColoursDownTheScreenMeetWhereNeitherVeils() {
        BackdropStyle.entries.forEach { style ->
            val (top, bottom) = style.veils.top to style.veils.bottom
            assertEquals(style.name, 0f, top.last().alpha, 0f)
            assertEquals(style.name, 0f, bottom.first().alpha, 0f)
            assertTrue(style.name, top.last().at < bottom.first().at)
        }
    }

    @Test
    fun aQuietPictureStaysInTheRoomWhereTextStillReads() {
        // Its fade stops short of black, yet over a white picture the quietest text keeps 4.5:1 there.
        val quiet = BackdropStyle.Quiet.veils
        val foot = quiet.bottom.last().alpha
        assertTrue(foot < 0.5f)
        assertEquals(0f, HlScrim.need(quiet.imageAlpha * (1f - foot)), 0f)
        // A picture shown whole still fades into black.
        assertEquals(1f, detail.bottom.last().alpha, 0f)
        assertEquals(1f, home.bottom.last().alpha, 0f)
    }

    @Test
    fun theGradientFollowsTheCurve() {
        val stops = detail.down.colorStops(c.background)
        stops.forEach { (at, color) -> assertEquals(detail.down.alphaAt(at), color.alpha, 0.004f) }
        assertEquals(0f, stops.first().first, 0f)
        assertEquals(1f, stops.last().first, 0f)
    }

    @Test
    fun aWhitePictureStepsBackFarEnoughForTheQuietestText() {
        val white = sample(32, 18) { _, _ -> 1f }
        assertTrue(white.floorAcross(screen, detail, deep) >= HlScrim.need(1f) - 0.0001f)
    }

    @Test
    fun aDarkPictureStepsBackByItsVeilAlone() {
        // `Backdrop` dims by VEIL at least: a dark picture needs no more.
        val dark = sample(32, 18) { _, _ -> 0.1f }
        assertTrue(dark.floorAcross(screen, detail, deep) <= HlAlpha.VEIL)
    }

    @Test
    fun aLightCornerOutsideTheColumnCountsWhenThePageRunsTheWidth() {
        // White on the last quarter only, past the text column: the column ignores it, the page does not.
        val right = sample(32, 18) { x, _ -> if (x >= 24) 1f else 0.1f }
        assertEquals(base, right.leftVeil(screen, column, detail, deep))
        assertTrue(right.floorAcross(screen, detail, deep) >= HlScrim.need(1f) - 0.0001f)
    }

    @Test
    fun aPlateKeepsItsTextOverWhite() {
        val band = 224f
        val white = sample(96, 54) { _, _ -> 1f }
        val bare = Veils(1f, emptyList(), listOf(VeilStop(0f, 0f)), listOf(VeilStop(1f, 0f)))
        val floor = white.plateFloor(screen, column, band, bare, brightest)
        assertEquals(HlScrim.need(1f, tint = brightest), floor, 0.0001f)
        assertTrue(floor <= HlAlpha.PLATE_MAX)
        // Under the top veil, a plate needs less.
        assertTrue(white.plateFloor(screen, column, band, home, brightest) in HlAlpha.PLATE..floor)
        // The brightest dark at the most a plate takes, over white.
        val ground = linear(1f + (brightest - 1f) * HlAlpha.PLATE_MAX)
        assertTrue("${ratio(ground)}:1", ratio(ground) >= 4.5f)
        // Over a dark picture, or light only below the band or under the column, a plate keeps its opacity.
        assertEquals(HlAlpha.PLATE, sample(96, 54) { _, _ -> 0.1f }.plateFloor(screen, column, band, home, brightest), 0f)
        assertEquals(HlAlpha.PLATE, sample(96, 54) { _, y -> if (y > 20) 1f else 0f }.plateFloor(screen, column, band, home, brightest), 0f)
        assertEquals(HlAlpha.PLATE, sample(96, 54) { x, _ -> if (x < 50) 1f else 0f }.plateFloor(screen, column, band, home, brightest), 0f)
    }

    @Test
    fun aPlateOnADiffuseFieldNeedsNoFloor() {
        // The field's brightest point, one level over its cap as dithering may draw it.
        val field = srgb(HlScrim.FIELD_LUMINANCE_MAX).let { (kotlin.math.floor(it * 255f) + 1f) / 255f }
        assertTrue(HlScrim.need(field, tint = brightest) <= HlAlpha.PLATE)
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
            listOf(0f, brightest).forEach { tint ->
                assertEquals(
                    "$w × $h on $shown, $style, tint $tint",
                    pointByPoint(w, h, points, shown, style.veils, tint),
                    ArtworkSample(w, h, points).leftVeil(shown, column, style.veils, tint),
                )
                assertEquals(
                    "across, $w × $h on $shown, $style, tint $tint",
                    floorPointByPoint(w, h, points, shown, style.veils, tint),
                    ArtworkSample(w, h, points).floorAcross(shown, style.veils, tint),
                    0f,
                )
            }
        }
    }

    /** A point of [grey] at [y] (a share of the height) through the veils down the screen, and what a veil of [tint] above shows there. */
    private fun through(grey: Float, y: Float, veils: Veils, tint: Float): Pair<Float, Float> {
        val dark = if (veils.tinted) tint else 0f
        val top = veils.top.alphaAt(y)
        val clear = 1f - veils.bottom.alphaAt(y)
        return grey * (veils.imageAlpha * (1f - top) * clear) + dark * top * clear to dark * clear
    }

    /** Point-by-point reference for [ArtworkSample.floorAcross]: a black veil through the veils down. */
    @Suppress("LongParameterList")
    private fun floorPointByPoint(width: Int, height: Int, grey: FloatArray, screen: Size, veils: Veils, tint: Float): Float {
        val scale = maxOf(screen.width / width, screen.height / height)
        val left = screen.width - width * scale
        val cols = kotlin.math.ceil(-left / scale - 0.5f).toInt().coerceAtLeast(0)..(width - 1)
        val rows = 0..kotlin.math.floor(screen.height / scale - 0.5f).toInt().coerceAtMost(height - 1)
        val needs = FloatArray(cols.count() * rows.count())
        var count = 0
        for (row in rows) {
            val y = (row + 0.5f) * scale / screen.height
            for (col in cols) needs[count++] = HlScrim.need(through(grey[row * width + col], y, veils, tint).first)
        }
        needs.sort()
        return needs[(needs.lastIndex * HlScrim.FLOOR_QUANTILE).roundToInt()]
    }

    /** Point-by-point reference for [ArtworkSample.leftVeil], through the veils down. */
    @Suppress("LongParameterList")
    private fun pointByPoint(width: Int, height: Int, grey: FloatArray, screen: Size, veils: Veils, tint: Float): List<VeilStop> {
        val veil = veils.left
        if (veil.isEmpty()) return veil
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
                val (point, under) = through(grey[row * width + col], (row + 0.5f) * scale / screen.height, veils, tint)
                val need = HlScrim.need(point, HlScrim.leftCeiling(band), under)
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

    /** Per band of the column, the share of a 96 × 54 picture's points left under contrast by [veil], composed as drawn. */
    private fun shortByBand(grey: (x: Int, y: Int) -> Float, veil: List<VeilStop>, veils: Veils, tint: Float): List<Float> {
        val scale = screen.width / 96
        val bands = veil.size - 1
        val short = IntArray(bands)
        val all = IntArray(bands)
        for (col in 0 until 96) {
            val x = (col + 0.5f) * scale
            if (x > column) continue
            val band = band(x / column)
            val alpha = veil.alphaAt(x / column)
            for (row in 0 until 54) {
                val y = (row + 0.5f) * scale / screen.height
                // The picture, the left veil in the picture's dark, then the top veil in it and the bottom one in black.
                val top = veils.top.alphaAt(y)
                val bottom = veils.bottom.alphaAt(y)
                val shown = ((grey(col, row) * (1f - alpha) + tint * alpha) * (1f - top) + tint * top) * (1f - bottom)
                all[band]++
                if (shown > HlScrim.leftCeiling(band) + 0.0001f) short[band]++
            }
        }
        return List(bands) { short[it].toFloat() / all[it] }
    }

    /** The band of the column at [at], a fraction of its width. */
    private fun band(at: Float): Int {
        val bands = HlScrim.LEFT_CONTRAST.size
        return (at * bands).toInt().coerceIn(0, bands - 1)
    }

    private fun sample(width: Int, height: Int, grey: (x: Int, y: Int) -> Float) =
        ArtworkSample(width, height, FloatArray(width * height) { grey(it % width, it / width) })

    private fun srgb(luminance: Float) = ColorSpaces.Srgb.oetf(luminance.toDouble()).toFloat()

    private fun linear(grey: Float) = ColorSpaces.Srgb.eotf(grey.toDouble()).toFloat()

    /** The luminance of red, green and blue each [blended] first, in sRGB. */
    private fun luminanceOf(red: Float, green: Float, blue: Float, blended: (Float) -> Float) =
        0.2126f * linear(blended(red)) + 0.7152f * linear(blended(green)) + 0.0722f * linear(blended(blue))

    /** The quietest text's contrast on a ground of this luminance. */
    private fun ratio(ground: Float) = (c.contentTertiary.luminance() + 0.05f) / (ground + 0.05f)

    private companion object {
        /** Small enough that a curve's slope barely changes over it, while an angle changes it at once. */
        const val STEP = 0.0002f
    }
}
