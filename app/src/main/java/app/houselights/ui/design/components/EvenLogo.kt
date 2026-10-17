package app.houselights.ui.design.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import coil.size.Size
import coil.transform.Transformation
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A clear logo set to a constant weight in a room [boxWidth] by [boxHeight] px: its transparent
 * margin trimmed, so that its ink sits on the screen's margin, then sized so that every logo carries
 * the same optical surface instead of filling the room ([planLogo]): a heavy, wide logo no longer
 * shouts, a thin one stacked over a ribbon no longer whispers, and a hero no longer jumps from one
 * film to the next. One pass over the logo as it is decoded; the result is what stays in memory.
 */
internal class EvenLogo(private val boxWidth: Int, private val boxHeight: Int) : Transformation {
    override val cacheKey = "evenLogo$VERSION-${boxWidth}x$boxHeight"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val pixels = IntArray(input.width * input.height)
        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
        val plan = planLogo(pixels, input.width, input.height, boxWidth, boxHeight) ?: return input
        val output = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        val ink = Rect(plan.left, plan.top, plan.right, plan.bottom)
        Canvas(output).drawBitmap(input, ink, Rect(0, 0, plan.width, plan.height), Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }

    private companion object {
        /** Bumped when the plan changes: logos kept on disk are set again, not found under an old key. */
        const val VERSION = 1
    }
}

/** How a logo is set: the part of it that holds ink, [left] to [right] and [top] to [bottom] (excluded), and the size it is drawn at, in px. */
internal class LogoPlan(val left: Int, val top: Int, val right: Int, val bottom: Int, val width: Int, val height: Int)

/**
 * The plan of a logo of [width] by [height] px whose ARGB [pixels] are given, in a room [boxWidth]
 * by [boxHeight]: its ink box, where alpha passes [INK_FLOOR], then the size whose area, weighed by
 * how much of it is ink to the power [INK_WEIGHT], is [SURFACE] of the room's. Between the area of a
 * box (a solid plate weighs as much as thin strokes) and the area of ink (thin strokes grown huge),
 * the eye reads a logo by both. It never leaves the room, and is never enlarged past its own pixels.
 * Null for a logo without ink.
 */
internal fun planLogo(pixels: IntArray, width: Int, height: Int, boxWidth: Int, boxHeight: Int): LogoPlan? {
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    var ink = 0L
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            val alpha = pixels[row + x] ushr ALPHA_SHIFT
            if (alpha > INK_FLOOR) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                bottom = y
            }
            ink += alpha
        }
    }
    if (right < 0) return null
    val inkWidth = right - left + 1
    val inkHeight = bottom - top + 1
    val coverage = max(MIN_COVERAGE, ink / (OPAQUE * inkWidth * inkHeight))
    val area = SURFACE * boxWidth * boxHeight / coverage.pow(INK_WEIGHT)
    val ratio = inkWidth.toDouble() / inkHeight
    val h = sqrt(area / ratio)
    val w = h * ratio
    val fit = minOf(1.0, boxWidth / w, boxHeight / h, inkWidth / w)
    return LogoPlan(
        left, top, right + 1, bottom + 1,
        (w * fit).roundToInt().coerceAtLeast(1),
        (h * fit).roundToInt().coerceAtLeast(1),
    )
}

/** Alpha at or under which a pixel is the logo's margin, not its ink (anti-aliasing haze, a faint glow). */
internal const val INK_FLOOR = 16

/** The share of its room a logo's weighed surface takes: a wide logo of solid letters comes to about two thirds of the room's width. */
internal const val SURFACE = 0.35

/** How much the share of ink weighs against the box's area: 0, the box alone; 1, the ink alone. */
internal const val INK_WEIGHT = 0.5

/** A logo nearly all air still counts this much ink: it never grows past the room for it. */
private const val MIN_COVERAGE = 0.05

private const val OPAQUE = 255.0
private const val ALPHA_SHIFT = 24
