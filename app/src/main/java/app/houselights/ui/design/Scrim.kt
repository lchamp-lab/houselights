package app.houselights.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.luminance
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Veils and lights over artwork (DESIGN.md, Veils); positions are shares of the column or the screen's height.
 * Veils follow smooth curves ([alphaAt]): straight segments show as lines. The left and top veils take the
 * picture's own dark, the bottom one is the room.
 */
object HlScrim {
    /** A hero's left veil (« À regarder », a person's page) at the column's band edges, raised where needed ([ArtworkSample.leftVeil]). */
    val LEFT_HOME = listOf(
        VeilStop(0f, 0.95f), VeilStop(0.25f, 0.94f), VeilStop(0.5f, 0.905f), VeilStop(0.75f, 0.785f), VeilStop(1f, 0.48f),
    )

    /** A detail page's: darker to the column's end, where its text runs longer. */
    val LEFT_DETAIL = listOf(
        VeilStop(0f, 0.96f), VeilStop(0.25f, 0.955f), VeilStop(0.5f, 0.94f), VeilStop(0.75f, 0.885f), VeilStop(1f, 0.67f),
    )

    /** Over a dimmed picture ([QUIET_PICTURE]). */
    val LEFT_QUIET = listOf(
        VeilStop(0f, 0.6f), VeilStop(0.25f, 0.564f), VeilStop(0.5f, 0.48f), VeilStop(0.75f, 0.36f), VeilStop(1f, 0.252f),
    )

    /**
     * Quietest text's contrast per band: 4.5:1 over the left half, where lines start; then less, so the
     * veil eases out before the column ends (the last quarter holds only line ends: overview 4:1, quietest 3:1).
     */
    val LEFT_CONTRAST = listOf(4.5f, 4.5f, 3.5f, 3f)

    /** The top veil, under the top bar: 0.74 at the edge, 0.44 at 72 dp, gone at 164 dp. */
    val TOP = listOf(
        VeilStop(0f, 0.74f), VeilStop(dp(34f), 0.64f), VeilStop(dp(72f), 0.44f),
        VeilStop(dp(110f), 0.22f), VeilStop(dp(140f), 0.07f), VeilStop(dp(164f), 0f),
    )

    /** A hero's bottom veil: its rows sit on the room from 77 % of the height. */
    val BOTTOM_HOME = listOf(
        VeilStop(0.4f, 0f), VeilStop(0.47f, 0.2f), VeilStop(0.54f, 0.48f), VeilStop(0.6f, 0.74f),
        VeilStop(0.66f, 0.9f), VeilStop(0.72f, 0.98f), VeilStop(0.77f, 1f), VeilStop(1f, 1f),
    )

    /** A detail page's: the picture runs behind its text down to 45 %. */
    val BOTTOM_DETAIL = listOf(
        VeilStop(0.45f, 0f), VeilStop(0.56f, 0.24f), VeilStop(0.68f, 0.55f), VeilStop(0.8f, 0.82f),
        VeilStop(0.9f, 0.96f), VeilStop(0.96f, 1f), VeilStop(1f, 1f),
    )

    /** A showcase's posters melting into its glass from a third of their height, deep under its name, whole at their foot. */
    val SHOWCASE_FOOT = listOf(
        VeilStop(0.36f, 0f), VeilStop(0.53f, 0.22f), VeilStop(0.72f, 0.6f), VeilStop(0.89f, 0.88f), VeilStop(1f, 1f),
    )

    /** Home's case: its picture whole to a third of its height, then melting into its dark under its words. */
    val CASE_FOOT = listOf(
        VeilStop(0.36f, 0f), VeilStop(0.47f, 0.42f), VeilStop(0.57f, 0.8f), VeilStop(0.65f, 0.95f), VeilStop(0.73f, 1f),
        VeilStop(1f, 1f),
    )

    /** A door's still: black rising under its logo from the middle, to 0.55 at its foot. */
    val STILL_FOOT = listOf(VeilStop(0.45f, 0f), VeilStop(1f, 0.55f))

    /** The search door's wall: its glass rising from a third of its height, to 0.65 at its foot. */
    val WALL_FOOT = listOf(VeilStop(0.3f, 0f), VeilStop(1f, 0.65f))

    /** A door's posters as its tint: their light lowered to [DOOR_TINT_LIGHT], their colour lifted [DOOR_TINT_SATURATION] times. */
    const val DOOR_TINT_LIGHT = 0.36f
    const val DOOR_TINT_SATURATION = 1.2f

    /** The search door's wall of posters, out: their light and colour lowered. */
    const val WALL_THUMB_LIGHT = 0.44f
    const val WALL_THUMB_SATURATION = 0.4f

    /** A saga stacked behind another, in its shade. */
    const val SAGA_BEHIND = 0.6f

    /** Over a wall's diffuse field, black: 0.66 at the top, gone at 200 dp; 0.5 at the foot. */
    val DIFFUSE_TOP = listOf(VeilStop(0f, 0.66f), VeilStop(dp(200f), 0f))
    val DIFFUSE_BOTTOM = listOf(VeilStop(0.7f, 0f), VeilStop(1f, 0.5f))

    /** The brightest a diffuse field gets: `contentSecondary` keeps 4.6:1 on it without a veil. */
    const val FIELD_LUMINANCE_MAX = 0.039f

    /** A picture's own dark: its hue at this value, lowered until its luminance is at most [DEEP_LUMINANCE_MAX]. */
    const val DEEP_VALUE = 0.11f
    const val DEEP_SATURATION_MAX = 0.75f
    const val DEEP_LUMINANCE_MAX = 0.006f

    /** A picture whose coloured tones cover less than this share is grey: a neutral dark, a light grey glow. */
    const val TINT_SHARE = 0.1f

    /** A focused card's glow: its artwork's main tone, bright; a grey picture glows light grey, at this value. */
    const val GLOW_VALUE = 0.9f
    const val GLOW_SATURATION_MIN = 0.45f
    const val GLOW_SATURATION_MAX = 0.85f

    /** Under this saturation a tone is grey: it neither tints a picture's dark nor its glow. */
    const val GLOW_GREY = 0.12f

    /** How much a tone counts by its area alone, before its saturation adds to it. */
    const val GLOW_AREA_WEIGHT = 0.35f

    /** The glow is rings out to `HlFocus.glow`, each at [GLOW_LAYER]: a shadow cannot light the black room. */
    const val GLOW_STEPS = 16
    const val GLOW_LAYER = 0.06f

    /** Opacity of the welcome wall's posters. */
    const val WALL = 0.3f

    /** Across the wall, shares of its width: dark where text sits, the posters seen in the middle. */
    val WALL_ACROSS = listOf(VeilStop(0f, 0.92f), VeilStop(0.42f, 0.55f), VeilStop(0.6f, 0.7f), VeilStop(1f, 0.94f))

    /** Down the wall: black at the top and at the foot. */
    val WALL_DOWN = listOf(VeilStop(0f, 1f), VeilStop(0.2f, 0f), VeilStop(0.8f, 0f), VeilStop(1f, 1f))

    /** Share of a band's points that sets its floor: a highlight under a twentieth (a lamp) does not darken it. */
    const val FLOOR_QUANTILE = 0.95f

    /** Floors are rounded up to the next half per cent, so rounding never takes text under its contrast. */
    private const val FLOOR_STEP = 0.005f
    private const val TEXT_CONTRAST = 4.5f
    private const val FLARE = 0.05f

    /** The screen's height in dp on a TV, which the top veils are drawn against. */
    private const val SCREEN_HEIGHT = 540f

    private fun dp(at: Float) = at / SCREEN_HEIGHT

    /** The lightest ground, as an sRGB grey, on which [text] keeps [contrast] (4.5:1 by default). */
    fun ceiling(text: Color, contrast: Float = TEXT_CONTRAST): Float {
        val luminance = (text.luminance() + FLARE) / contrast - FLARE
        return ColorSpaces.Srgb.oetf(luminance.toDouble()).toFloat()
    }

    /** The quietest text a column holds: genres, the billing block's small words. */
    val quietest = ceiling(HouselightsColors.contentTertiary)

    private val quietestAcross = LEFT_CONTRAST.map { ceiling(HouselightsColors.contentTertiary, it) }

    /** The quietest text's ceiling in [band] of the column. */
    fun leftCeiling(band: Int): Float = quietestAcross[band]

    /** A dimmed picture's opacity, its top veil, and its foot: what its quietest text needs, short of black. */
    // After [quietest], which [need] reads as this object initialises.
    const val QUIET_PICTURE = 0.28f
    val TOP_QUIET = listOf(VeilStop(0f, 0.45f), VeilStop(0.14f, 0f))
    val BOTTOM_QUIET = listOf(VeilStop(0.2f, 0f), VeilStop(0.75f, need(QUIET_PICTURE)))

    /**
     * Opacity a veil (or plate) of grey [tint] needs over one point for text with this [ceiling] to keep its
     * contrast; [grey] is the point through what lies above it. 0 on a dark point. A tinted veil lightens
     * what it covers: it needs `(grey - ceiling) / (grey - tint)`.
     */
    fun need(grey: Float, ceiling: Float = quietest, tint: Float = 0f): Float =
        if (grey <= ceiling) 0f else roundUp((grey - ceiling) / (grey - tint))

    private fun roundUp(alpha: Float) = (ceil(alpha / FLOOR_STEP) * FLOOR_STEP).coerceAtMost(1f)
}

/**
 * A backdrop's veils over its picture at [imageAlpha]: [left] at the text column's band edges (none on a
 * wall), [top] and [bottom] down the screen. Left and top take the picture's dark when [tinted].
 */
@Immutable
data class Veils(
    val imageAlpha: Float,
    val left: List<VeilStop>,
    val top: List<VeilStop>,
    val bottom: List<VeilStop>,
    val tinted: Boolean = true,
) {
    /** Top then bottom, drawn as one gradient: they never overlap. */
    val down: List<VeilStop> = top + bottom
}

/** A stop of a veil: where it is, as a share of its extent, and its opacity there. */
@Immutable
data class VeilStop(val at: Float, val alpha: Float)

/** Opacity at [at]: a monotone cubic through the stops, never overshooting, its slope never jumping. */
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
    // Cubic Hermite blend of the two stops' values and slopes at s.
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

/** The curve in [color] as [VEIL_STEPS] gradient stops, each at the curve's share of its alpha. */
fun List<VeilStop>.colorStops(color: Color): Array<Pair<Float, Color>> = colorStops { color }

/** The curve as [VEIL_STEPS] gradient stops, each in [color] where it is, at the curve's share of its alpha. */
fun List<VeilStop>.colorStops(color: (at: Float) -> Color): Array<Pair<Float, Color>> {
    val from = first().at
    val to = last().at
    return Array(VEIL_STEPS + 1) {
        val at = from + (to - from) * it / VEIL_STEPS
        val tone = color(at)
        at to tone.copy(alpha = tone.alpha * alphaAt(at))
    }
}

/** Gradient steps per veil curve: straight between them, too short to see. */
private const val VEIL_STEPS = 32

/** How bright [color] is over the black room, as the sRGB grey of the same luminance: 0 black, 1 white. */
fun greyOf(color: Color): Float = ColorSpaces.Srgb.oetf(color.luminance().toDouble()).toFloat() * color.alpha

/**
 * A small copy of a picture as each point's [greyOf], row by row: enough to size a veil or a plate over it.
 * Veils are reckoned in sRGB greys, blended as the screen blends them; [deep] is the picture's dark as a grey.
 */
class ArtworkSample(val width: Int, val height: Int, private val grey: FloatArray) {
    init {
        require(grey.size == width * height) { "$width × $height points, ${grey.size} values" }
    }

    /**
     * The left veil over the first [column] px of a [screen], the picture cropped to fill from its top (as
     * `Backdrop` lays it) under [veils]: [Veils.left], each band raised to what [HlScrim.FLOOR_QUANTILE] of its
     * points need, and never lighter than a band to its right.
     */
    fun leftVeil(screen: Size, column: Float, veils: Veils, deep: Float): List<VeilStop> {
        val veil = veils.left
        if (veil.isEmpty() || column <= 0f || !shows(screen)) return veil
        val shown = Shown(screen)
        // The points whose middle shows on the screen, under the column.
        val cols = shown.firstCol..floor((column - shown.left) / shown.scale - MIDDLE).toInt().coerceAtMost(width - 1)
        if (cols.isEmpty()) return veil
        val bands = veil.size - 1
        check(HlScrim.LEFT_CONTRAST.size == bands) { "a contrast for each of the $bands bands" }
        val down = Down(shown, veils, if (veils.tinted) deep else 0f)
        val bandOf = IntArray(cols.length) {
            val x = shown.left + (cols.first + it + MIDDLE) * shown.scale
            (x / column * bands).toInt().coerceIn(0, bands - 1)
        }
        val needs = Array(bands) { band -> FloatArray(bandOf.count { it == band } * shown.rows.length) }
        val counts = IntArray(bands)
        for (col in cols) {
            val x = shown.left + (col + MIDDLE) * shown.scale
            val band = bandOf[col - cols.first]
            val held = veil.alphaAt(x / column)
            val ceiling = HlScrim.leftCeiling(band)
            for (row in shown.rows) {
                val need = HlScrim.need(down.point(grey[row * width + col], row), ceiling, down.under[row])
                needs[band][counts[band]++] = if (need > held) need else 0f
            }
        }
        val floors = FloatArray(bands) { band ->
            val points = needs[band].apply { sort() }
            if (points.isEmpty()) 0f else points[(points.lastIndex * HlScrim.FLOOR_QUANTILE).roundToInt()]
        }
        // From the right: a band keeps what any band to its right needs.
        for (band in bands - 2 downTo 0) floors[band] = max(floors[band], floors[band + 1])
        // Both stops of a band keep its floor, and the curve between them never dips below a stop.
        return veil.mapIndexed { i, stop -> VeilStop(stop.at, max(stop.alpha, floors[(i - 1).coerceAtLeast(0)])) }
    }

    /**
     * Opacity a black veil over the whole picture needs for the quietest text anywhere on it (rows spanning the
     * screen): what [HlScrim.FLOOR_QUANTILE] of the shown points need. 0 on a dark picture.
     */
    fun floorAcross(screen: Size, veils: Veils, deep: Float): Float {
        if (!shows(screen)) return 0f
        val shown = Shown(screen)
        return quantileOfNeeds(shown.firstCol..(width - 1), shown.rows, Down(shown, veils, if (veils.tinted) deep else 0f), 0f)
    }

    /**
     * Opacity a plate in the picture's dark needs for the quietest text over the band [bottom] px tall right of
     * [left] px, the picture seen through its top and bottom veils: what [HlScrim.FLOOR_QUANTILE] of the band's
     * points need, from `HlAlpha.PLATE` to `HlAlpha.PLATE_MAX`.
     */
    fun plateFloor(screen: Size, left: Float, bottom: Float, veils: Veils, deep: Float): Float {
        if (!shows(screen)) return HlAlpha.PLATE
        val shown = Shown(screen)
        val cols = max(shown.firstCol, ceil((left - shown.left) / shown.scale - MIDDLE).toInt())..(width - 1)
        val rows = 0..min(shown.rows.last, floor(bottom / shown.scale - MIDDLE).toInt())
        if (cols.isEmpty() || rows.isEmpty()) return HlAlpha.PLATE
        val need = quantileOfNeeds(cols, rows, Down(shown, veils, if (veils.tinted) deep else 0f), deep)
        return need.coerceIn(HlAlpha.PLATE, HlAlpha.PLATE_MAX)
    }

    /** Whether any of the picture shows on [screen]. */
    private fun shows(screen: Size) = grey.isNotEmpty() && !screen.isEmpty()

    /** What [HlScrim.FLOOR_QUANTILE] of the points in [cols] and [rows] need of a layer of grey [tint] over them. */
    private fun quantileOfNeeds(cols: IntRange, rows: IntRange, down: Down, tint: Float): Float {
        val needs = FloatArray(cols.length * rows.length)
        var count = 0
        for (row in rows) {
            for (col in cols) needs[count++] = HlScrim.need(down.point(grey[row * width + col], row), tint = tint)
        }
        needs.sort()
        return needs[(needs.lastIndex * HlScrim.FLOOR_QUANTILE).roundToInt()]
    }

    /** The picture cropped to fill a [screen], its right edge on the screen's: its scale, left edge and shown points. */
    private inner class Shown(screen: Size) {
        val scale = max(screen.width / width, screen.height / height)
        val left = screen.width - width * scale
        val screenHeight = screen.height
        val firstCol = ceil(-left / scale - MIDDLE).toInt().coerceAtLeast(0)
        val rows = 0..floor(screen.height / scale - MIDDLE).toInt().coerceAtMost(height - 1)
    }

    /**
     * Row by row, the veils down the screen over a point: how much of the picture shows ([through]), the top
     * veil's [tint] lit over it ([lit]), and what a veil of that tint above them shows there ([under]).
     */
    private class Down(shown: Shown, veils: Veils, tint: Float) {
        val through = FloatArray(shown.rows.last + 1)
        val lit = FloatArray(through.size)
        val under = FloatArray(through.size)

        init {
            for (row in shown.rows) {
                val at = (row + MIDDLE) * shown.scale / shown.screenHeight
                val top = veils.top.alphaAt(at)
                val clear = 1f - veils.bottom.alphaAt(at)
                through[row] = veils.imageAlpha * (1f - top) * clear
                lit[row] = tint * top * clear
                under[row] = tint * clear
            }
        }

        /** A point of [grey] in [row], as the veils down the screen leave it. */
        fun point(grey: Float, row: Int) = grey * through[row] + lit[row]
    }

    private val IntRange.length get() = if (isEmpty()) 0 else last - first + 1

    private companion object {
        const val MIDDLE = 0.5f
    }
}
