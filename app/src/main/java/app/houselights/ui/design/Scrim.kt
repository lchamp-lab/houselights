package app.houselights.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.luminance
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The veils laid over artwork so that what sits on it reads, and the lights artwork gives (a card's
 * glow, a collection's). Recipes, not loose numbers: veils are `background` at these opacities, lights their own
 * colour; positions are shares of the screen (or of the wall, or the column) as each says. A veil
 * runs through its stops as a smooth curve ([alphaAt]), never as straight lines meeting at an angle:
 * the eye sees each angle as a line across the picture.
 */
object HlScrim {
    /**
     * The left veil behind the text column (`HlSpace.safeH` + `HlLayout.readableNarrow`): at each
     * edge of the column's bands, evenly spaced, the share of its style's strength it keeps. Under
     * the column, a band never takes less than what this picture needs there ([ArtworkSample.leftVeil]);
     * past it the veil fades out at the pace `BackdropDefaults.veilFade` sets.
     */
    val LEFT = listOf(VeilStop(0f, 1f), VeilStop(0.25f, 0.94f), VeilStop(0.5f, 0.8f), VeilStop(0.75f, 0.6f), VeilStop(1f, 0.42f))

    /**
     * The contrast the quietest text keeps over the picture, band by band across the column: 4.5:1
     * over its left half, where every line starts and the title, its facts and the billing sit; then
     * less, so the veil eases into the picture before the column ends instead of after it. Only the
     * ends of the longest lines reach the last quarter: there the quietest words keep 3:1 and the
     * overview (`contentSecondary`) 4:1, against 4.7:1 over the third quarter.
     */
    val LEFT_CONTRAST = listOf(4.5f, 4.5f, 3.5f, 3f)

    /** The top veil, under the top bar: its opacity at the top edge, gone by this share of the height. */
    const val TOP = 0.45f
    const val TOP_END = 0.14f

    /**
     * Down the screen: the top veil, then the bottom fade, in one stroke from [fadeFrom] to [foot] at
     * [fadeTo] (black by default), shares of the height past [TOP_END], and [foot] on to the bottom.
     * One long stroke: the picture melts into the black; a fade that reaches near black early, then
     * creeps to it, shows where the black begins.
     */
    fun down(fadeFrom: Float, fadeTo: Float, foot: Float = 1f): List<VeilStop> {
        require(fadeFrom > TOP_END && fadeTo > fadeFrom && fadeTo <= 1f && foot in 0f..1f) {
            "fade from $fadeFrom to $foot at $fadeTo"
        }
        return listOf(VeilStop(0f, TOP), VeilStop(TOP_END, 0f), VeilStop(fadeFrom, 0f), VeilStop(fadeTo, foot))
    }

    /**
     * A card's own light while it has focus: the most vivid tone of its artwork, brighter than the
     * room's ([AMBIENT_VALUE]) so it reads as light around the card. A grey picture glows white.
     */
    const val GLOW_VALUE = 0.9f
    const val GLOW_SATURATION_MIN = 0.45f
    const val GLOW_SATURATION_MAX = 0.85f
    const val GLOW_GREY = 0.12f

    /** Which tones of a picture may give its glow: not the near-black, not the near-white. */
    const val GLOW_LIGHTNESS_MIN = 0.12f
    const val GLOW_LIGHTNESS_MAX = 0.92f

    /** How much a tone counts by its area alone, before its saturation adds to it. */
    const val GLOW_AREA_WEIGHT = 0.35f

    /**
     * The glow is drawn, not cast: a shadow can only darken, and on the black room it does not show.
     * It is [GLOW_STEPS] rounded rings out to `HlFocus.glow`, each at [GLOW_LAYER] of the tone and
     * fading with the distance, so the light is brightest against the card and dies out softly.
     */
    const val GLOW_STEPS = 16
    const val GLOW_LAYER = 0.06f

    /** How bright the ambient tone is, every film alike: its HSV value. */
    const val AMBIENT_VALUE = 0.62f

    /** The room's light is never garish: its saturation within these bounds, at [AMBIENT_VALUE]. */
    const val AMBIENT_SATURATION_MIN = 0.25f
    const val AMBIENT_SATURATION_MAX = 0.7f

    /** The welcome wall: its posters at this opacity. */
    const val WALL = 0.3f

    /** Across the wall, shares of its width: dark where text sits, the posters seen in the middle. */
    val WALL_ACROSS = listOf(VeilStop(0f, 0.92f), VeilStop(0.42f, 0.55f), VeilStop(0.6f, 0.7f), VeilStop(1f, 0.94f))

    /** Down the wall: black at the top and at the foot. */
    val WALL_DOWN = listOf(VeilStop(0f, 1f), VeilStop(0.2f, 0f), VeilStop(0.8f, 0f), VeilStop(1f, 1f))

    /**
     * The share of a band's points that sets its floor: a highlight smaller than a twentieth of the
     * band (a lamp, a reflection) does not darken it; an overview's worth of light does.
     */
    const val FLOOR_QUANTILE = 0.95f

    /** Floors are rounded up to the next half per cent, so rounding never takes text under its contrast. */
    private const val FLOOR_STEP = 0.005f
    private const val TEXT_CONTRAST = 4.5f
    private const val FLARE = 0.05f

    /** The lightest ground, as an sRGB grey, on which [text] keeps [contrast] (4.5:1 by default). */
    fun ceiling(text: Color, contrast: Float = TEXT_CONTRAST): Float {
        val luminance = (text.luminance() + FLARE) / contrast - FLARE
        return ColorSpaces.Srgb.oetf(luminance.toDouble()).toFloat()
    }

    /** The quietest text a column holds: genres, the billing block's small words. */
    private val quietest = ceiling(HouselightsColors.contentTertiary)

    /** The quietest text's ceiling in each band of the column, at its [LEFT_CONTRAST]. */
    private val quietestAcross = LEFT_CONTRAST.map { ceiling(HouselightsColors.contentTertiary, it) }

    /** The ceiling the left veil keeps the quietest text under in [band] of the column. */
    fun leftCeiling(band: Int): Float = quietestAcross[band]

    /**
     * The opacity the left veil needs over one point of a picture for text to keep 4.5:1 there:
     * text whose [ceiling] is given, the quietest by default. [grey] is how bright the point shows
     * through the other veils, as an sRGB grey (1: white). 0 on a dark point.
     */
    fun need(grey: Float, ceiling: Float = quietest): Float =
        if (grey <= ceiling) 0f else roundUp(1f - ceiling / grey)

    private fun roundUp(alpha: Float) = (ceil(alpha / FLOOR_STEP) * FLOOR_STEP).coerceAtMost(1f)
}

/** A stop of a veil: where it is, as a share of its extent, and its opacity there. */
@Immutable
data class VeilStop(val at: Float, val alpha: Float)

/**
 * The opacity of a veil at [at]: a monotone cubic through its stops, flat at the first and the last
 * and wherever the veil turns, so it never overshoots a stop and its slope never jumps.
 */
fun List<VeilStop>.alphaAt(at: Float): Float {
    // By index, with no iterator: a veil is asked for thousands of points.
    var next = 0
    while (next < size && !(this[next].at >= at)) next++
    if (next == size) return last().alpha
    if (next == 0) return first().alpha
    val from = this[next - 1]
    val to = this[next]
    val width = to.at - from.at
    if (width <= 0f) return to.alpha
    // Cubic Hermite: how much of each stop's value, and of each stop's slope, at s along the way.
    val s = (at - from.at) / width
    val rest = 1 - s
    val fromShare = (1 + 2 * s) * rest * rest
    return fromShare * from.alpha + (1 - fromShare) * to.alpha +
        (s * rest * rest * slopeAt(next - 1) - s * s * rest * slopeAt(next)) * width
}

/** The veil's slope at its stop [i] (Fritsch–Carlson): 0 at the ends and where it turns. */
private fun List<VeilStop>.slopeAt(i: Int): Float {
    if (i == 0 || i == lastIndex) return 0f
    val before = this[i].at - this[i - 1].at
    val after = this[i + 1].at - this[i].at
    if (before <= 0f || after <= 0f) return 0f
    val into = (this[i].alpha - this[i - 1].alpha) / before
    val out = (this[i + 1].alpha - this[i].alpha) / after
    if (into * out <= 0f) return 0f
    val w1 = 2 * after + before
    val w2 = after + 2 * before
    return (w1 + w2) / (w1 / into + w2 / out)
}

/** How finely a veil's curve is laid out as a gradient: straight between these steps, too short to see. */
private const val VEIL_STEPS = 32

/**
 * A veil's curve in [color], for a gradient: from its first stop to its last, in [VEIL_STEPS], each
 * at the curve's share of [color]'s alpha. Lights are laid out the same way: a tone, along its fall.
 */
fun List<VeilStop>.colorStops(color: Color): Array<Pair<Float, Color>> {
    val from = first().at
    val to = last().at
    return Array(VEIL_STEPS + 1) {
        val at = from + (to - from) * it / VEIL_STEPS
        at to color.copy(alpha = color.alpha * alphaAt(at))
    }
}

/** How bright [color] is over the black room, as the sRGB grey of the same luminance: 0 black, 1 white. */
fun greyOf(color: Color): Float = ColorSpaces.Srgb.oetf(color.luminance().toDouble()).toFloat() * color.alpha

/**
 * A small copy of a picture, as bright as the room sees it: each point's [greyOf], row by row.
 * Enough to know how strong a veil must be over it.
 */
class ArtworkSample(val width: Int, val height: Int, private val grey: FloatArray) {
    private companion object {
        /** Where a point's middle is, from its edge. */
        const val MIDDLE = 0.5f
    }

    init {
        require(grey.size == width * height) { "$width × $height points, ${grey.size} values" }
    }

    /**
     * The left veil over the text column, the first [column] px of a [screen] where the picture is
     * cropped to fill from its top end (as `Backdrop` lays it), at [imageAlpha] and under the veils
     * [down] it: [HlScrim.LEFT] at [strength], its stops as shares of the column. Where that falls
     * short in a band, the band takes what [HlScrim.FLOOR_QUANTILE] of its points need for the
     * band's contrast ([HlScrim.LEFT_CONTRAST]); and no band is
     * lighter than one to its right, so the veil only ever eases towards the picture, as dark as
     * this picture needs where it needs it. On a dark picture it is [HlScrim.LEFT] as it is.
     */
    fun leftVeil(screen: Size, column: Float, imageAlpha: Float, strength: Float, down: List<VeilStop>): List<VeilStop> {
        val veil = HlScrim.LEFT.map { VeilStop(it.at, it.alpha * strength) }
        if (grey.isEmpty() || screen.isEmpty() || column <= 0f) return veil
        // Cropped to fill: as wide or as tall as the screen, its right edge on the screen's.
        val scale = max(screen.width / width, screen.height / height)
        val left = screen.width - width * scale
        // The points whose middle shows on the screen, under the column.
        val firstCol = ceil(-left / scale - MIDDLE).toInt().coerceAtLeast(0)
        val cols = firstCol..kotlin.math.floor((column - left) / scale - MIDDLE).toInt().coerceAtMost(width - 1)
        val rows = 0..kotlin.math.floor(screen.height / scale - MIDDLE).toInt().coerceAtMost(height - 1)
        if (cols.isEmpty()) return veil
        val bands = veil.size - 1
        check(HlScrim.LEFT_CONTRAST.size == bands) { "a contrast for each of the $bands bands" }
        // How much of each row shows through the veils down the screen: the same all across it.
        val through = throughRows(rows, scale, screen.height, imageAlpha, down)
        val bandOf = IntArray(cols.length) {
            val x = left + (cols.first + it + MIDDLE) * scale
            (x / column * bands).toInt().coerceIn(0, bands - 1)
        }
        // Each band holds the points of its own columns, no more.
        val needs = Array(bands) { band -> FloatArray(bandOf.count { it == band } * rows.length) }
        val counts = IntArray(bands)
        for (col in cols) {
            val x = left + (col + MIDDLE) * scale
            val band = bandOf[col - cols.first]
            val held = veil.alphaAt(x / column)
            val ceiling = HlScrim.leftCeiling(band)
            for (row in rows) {
                val need = HlScrim.need(grey[row * width + col] * through[row], ceiling)
                needs[band][counts[band]++] = if (need > held) need else 0f
            }
        }
        val floors = FloatArray(bands) { band ->
            val points = needs[band].apply { sort() }
            if (points.isEmpty()) 0f else points[(points.lastIndex * HlScrim.FLOOR_QUANTILE).roundToInt()]
        }
        // From the right: a band keeps what any band to its right needs.
        for (band in bands - 2 downTo 0) floors[band] = max(floors[band], floors[band + 1])
        // A band runs between its two stops: both keep its floor (the left one, its left neighbour's,
        // which is no less), and so does the curve between them, which never dips below a stop.
        return veil.mapIndexed { i, stop -> VeilStop(stop.at, max(stop.alpha, floors[(i - 1).coerceAtLeast(0)])) }
    }

    /**
     * The opacity a veil laid over the whole picture needs for the quietest text to read anywhere
     * on it (a page's rows, which run the screen's width): what [HlScrim.FLOOR_QUANTILE] of the
     * points shown need, laid as [leftVeil] lays them. 0 on a dark picture.
     */
    fun floorAcross(screen: Size, imageAlpha: Float, down: List<VeilStop>): Float {
        if (grey.isEmpty() || screen.isEmpty()) return 0f
        val scale = max(screen.width / width, screen.height / height)
        val left = screen.width - width * scale
        val cols = ceil(-left / scale - MIDDLE).toInt().coerceAtLeast(0)..(width - 1)
        val rows = 0..kotlin.math.floor(screen.height / scale - MIDDLE).toInt().coerceAtMost(height - 1)
        val needs = FloatArray(cols.length * rows.length)
        val through = throughRows(rows, scale, screen.height, imageAlpha, down)
        var count = 0
        for (row in rows) {
            for (col in cols) needs[count++] = HlScrim.need(grey[row * width + col] * through[row])
        }
        needs.sort()
        return needs[(needs.lastIndex * HlScrim.FLOOR_QUANTILE).roundToInt()]
    }

    /** How many points a range of them holds, without walking it. */
    private val IntRange.length get() = if (isEmpty()) 0 else last - first + 1

    /** For each of the [rows], how much of the picture at [imageAlpha] shows through the veils [down] it. */
    private fun throughRows(rows: IntRange, scale: Float, screenHeight: Float, imageAlpha: Float, down: List<VeilStop>) =
        FloatArray(rows.last + 1) { row -> imageAlpha * (1f - down.alphaAt((row + MIDDLE) * scale / screenHeight)) }
}
