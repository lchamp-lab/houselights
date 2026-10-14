package app.houselights.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.luminance
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The veils laid over artwork so that what sits on it reads, and the light artwork spills into the
 * room. Recipes, not loose numbers: veils are `background` at these opacities, lights their own
 * colour; positions are shares of the screen (or of the wall, or the column) as each says.
 */
object HlScrim {
    /**
     * The left veil behind the text column (`HlSpace.safeH` + `HlLayout.readableNarrow`): at each
     * share of the column, the share of its style's strength it keeps. Past the column it fades out
     * over `HlLayout.veilFade`. Under the column it never drops below the floor of the picture
     * ([ArtworkSample.floor]).
     */
    val LEFT = listOf(VeilStop(0f, 1f), VeilStop(0.6f, 0.9f), VeilStop(1f, 0.5f))

    /** The top veil, under the top bar: its opacity at the top edge, gone by this share of the height. */
    const val TOP = 0.45f
    const val TOP_END = 0.14f

    /**
     * The bottom fade: from where the style starts it, it reaches [FADE] over this share of the
     * height (by [FADE_END_MAX] at the latest), then black at the foot.
     */
    const val FADE_LENGTH = 0.3f
    const val FADE = 0.92f
    const val FADE_END_MAX = 0.98f

    /** Down the screen: the top veil, then the bottom fade from [fadeFrom], a share of the height. */
    fun down(fadeFrom: Float) = listOf(
        VeilStop(0f, TOP),
        VeilStop(TOP_END, 0f),
        VeilStop(fadeFrom, 0f),
        VeilStop((fadeFrom + FADE_LENGTH).coerceAtMost(FADE_END_MAX), FADE),
        VeilStop(1f, 1f),
    )

    /**
     * The light a backdrop spills on the room below it: the ambient tone at [SPILL] in its centre,
     * [SPILL_DARK] more once the room is dark, fading out at its radius. Centre and radius are shares
     * of the screen's width ([SPILL_X], [SPILL_RADIUS]) and height ([SPILL_Y]: below the screen).
     */
    const val SPILL = 0.22f
    const val SPILL_DARK = 0.14f
    const val SPILL_X = 0.28f
    const val SPILL_Y = 1.05f
    const val SPILL_RADIUS = 0.62f

    /**
     * How bright the ambient tone is, every film alike: its HSV value. No tone is a lighter grey,
     * so it bounds what the spill adds under text.
     */
    const val AMBIENT_VALUE = 0.62f

    /** The spill's opacity at [x], [y] on a screen of [screen], at rest. */
    fun spillAt(x: Float, y: Float, screen: Size): Float {
        val radius = screen.width * SPILL_RADIUS
        val distance = hypot(x - screen.width * SPILL_X, y - screen.height * SPILL_Y)
        return SPILL * (1f - distance / radius).coerceAtLeast(0f)
    }

    /**
     * The light of a collection when no title is selected: each tone glows over its column from
     * above the screen ([COLLECTION_Y] of the height), at [COLLECTION_CORE] in its centre and
     * [COLLECTION_MID] by [COLLECTION_MID_AT] of its radius ([COLLECTION_RADIUS] of the height),
     * [COLLECTION_SPREAD] columns wide.
     */
    const val COLLECTION_CORE = 0.36f
    const val COLLECTION_MID = 0.11f
    const val COLLECTION_MID_AT = 0.55f
    const val COLLECTION_Y = -0.12f
    const val COLLECTION_RADIUS = 0.95f
    const val COLLECTION_SPREAD = 1.25f

    /** The welcome wall: its posters at this opacity. */
    const val WALL = 0.3f

    /** Across the wall, shares of its width: dark where text sits, the posters seen in the middle. */
    val WALL_ACROSS = listOf(VeilStop(0f, 0.92f), VeilStop(0.42f, 0.55f), VeilStop(0.6f, 0.7f), VeilStop(1f, 0.94f))

    /** Down the wall: black at the top and at the foot. */
    val WALL_DOWN = listOf(VeilStop(0f, 1f), VeilStop(0.2f, 0f), VeilStop(0.8f, 0f), VeilStop(1f, 1f))

    /**
     * The share of the text column's points that sets its floor: a highlight smaller than a
     * twentieth of the column (a lamp, a reflection) does not darken all of it; an overview's worth
     * of light does.
     */
    const val FLOOR_QUANTILE = 0.95f

    /** Floors are rounded up to the next half per cent, so rounding never takes text under 4.5:1. */
    private const val FLOOR_STEP = 0.005f
    private const val TEXT_CONTRAST = 4.5f
    private const val FLARE = 0.05f

    /** The lightest ground, as an sRGB grey, on which [text] keeps 4.5:1. */
    fun ceiling(text: Color): Float {
        val luminance = (text.luminance() + FLARE) / TEXT_CONTRAST - FLARE
        return ColorSpaces.Srgb.oetf(luminance.toDouble()).toFloat()
    }

    /** The quietest text a column holds: genres, the billing block's small words. */
    private val quietest = ceiling(HouselightsColors.contentTertiary)

    /**
     * The opacity the left veil needs over one point of a picture for text to keep 4.5:1 there:
     * text whose [ceiling] is given, the quietest by default. [grey] is how bright the point shows
     * through the other veils, as an sRGB grey (1: white); [spill], the ambient light's opacity
     * there, laid over at its brightest. 0 on a dark point, 1 where even black would not do.
     */
    fun need(grey: Float, spill: Float = 0f, ceiling: Float = quietest): Float {
        val allowed = ceiling - spill * AMBIENT_VALUE
        val shown = grey * (1f - spill)
        return when {
            shown <= allowed.coerceAtLeast(0f) -> 0f
            allowed <= 0f -> 1f
            else -> roundUp(1f - allowed / shown)
        }
    }

    private fun roundUp(alpha: Float) = (ceil(alpha / FLOOR_STEP) * FLOOR_STEP).coerceAtMost(1f)
}

/** A stop of a veil: where it is, as a share of its extent, and its opacity there. */
@Immutable
data class VeilStop(val at: Float, val alpha: Float)

/** The opacity of a veil at [at], between its stops. */
fun List<VeilStop>.alphaAt(at: Float): Float {
    val next = indexOfFirst { it.at >= at }
    if (next == -1) return last().alpha
    if (next == 0) return first().alpha
    val (from, to) = this[next - 1] to this[next]
    return from.alpha + (to.alpha - from.alpha) * (at - from.at) / (to.at - from.at)
}

/** A veil's stops in [color], for a gradient. */
fun List<VeilStop>.colorStops(color: Color): Array<Pair<Float, Color>> = map { it.at to color.copy(alpha = it.alpha) }.toTypedArray()

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
     * The least opacity the left veil keeps under the text column, the first [column] px of a
     * [screen] where the picture is cropped to fill from its top end (as `Backdrop` lays it), at
     * [imageAlpha] and under the veils [down] it: what [HlScrim.FLOOR_QUANTILE] of the column's
     * points need, with the ambient spill over them, where the veil's own [strength] (at the edge,
     * easing as [HlScrim.LEFT] says) falls short. 0 on a dark picture: the veil stays as it is.
     */
    fun floor(screen: Size, column: Float, imageAlpha: Float, strength: Float, down: List<VeilStop>): Float {
        if (grey.isEmpty() || screen.isEmpty()) return 0f
        // Cropped to fill: as wide or as tall as the screen, its right edge on the screen's.
        val scale = max(screen.width / width, screen.height / height)
        val left = screen.width - width * scale
        // The points whose middle shows on the screen, under the column.
        val firstCol = ceil(-left / scale - MIDDLE).toInt().coerceAtLeast(0)
        val cols = firstCol..kotlin.math.floor((column - left) / scale - MIDDLE).toInt().coerceAtMost(width - 1)
        val rows = 0..kotlin.math.floor(screen.height / scale - MIDDLE).toInt().coerceAtMost(height - 1)
        if (cols.isEmpty()) return 0f
        val needs = FloatArray(cols.count() * rows.count())
        var count = 0
        for (row in rows) {
            val y = (row + MIDDLE) * scale
            val through = imageAlpha * (1f - down.alphaAt(y / screen.height))
            for (col in cols) {
                val x = left + (col + MIDDLE) * scale
                val need = HlScrim.need(grey[row * width + col] * through, HlScrim.spillAt(x, y, screen))
                needs[count++] = if (need > strength * HlScrim.LEFT.alphaAt(x / column)) need else 0f
            }
        }
        needs.sort()
        return needs[(needs.lastIndex * HlScrim.FLOOR_QUANTILE).roundToInt()]
    }
}
