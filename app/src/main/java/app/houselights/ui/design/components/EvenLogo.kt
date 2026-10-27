package app.houselights.ui.design.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import coil.size.Size
import coil.transform.Transformation
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Sets a clear logo to a constant visual weight in a [boxWidth] by [boxHeight] px box ([planLogo]): its
 * transparent margin trimmed, sized to the same optical surface, so heavy and thin logos read alike; enlarged at
 * most [enlarge] times.
 */
internal class EvenLogo(private val boxWidth: Int, private val boxHeight: Int, private val enlarge: Float = 1f) : Transformation {
    override val cacheKey = "evenLogo$VERSION-${boxWidth}x$boxHeight-$enlarge"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val pixels = IntArray(input.width * input.height)
        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
        val plan = planLogo(pixels, input.width, input.height, boxWidth, boxHeight, enlarge) ?: return input
        val output = createBitmap(plan.width, plan.height)
        val ink = Rect(plan.left, plan.top, plan.right, plan.bottom)
        Canvas(output).drawBitmap(input, ink, Rect(0, 0, plan.width, plan.height), Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }

    private companion object {
        /** Bump when [planLogo] changes, so cached logos are transformed again, not found under the old key. */
        const val VERSION = 1
    }
}

/** A logo's ink box ([left] to [right], [top] to [bottom], exclusive) and its drawn size, in px. */
internal class LogoPlan(val left: Int, val top: Int, val right: Int, val bottom: Int, val width: Int, val height: Int)

/**
 * The ink box (alpha above [INK_FLOOR]) of a logo and the size whose area, weighted by ink coverage to the
 * power [INK_WEIGHT], is [SURFACE] of the box, fitted and at most [enlarge] times its own pixels; null without ink.
 */
internal fun planLogo(pixels: IntArray, width: Int, height: Int, boxWidth: Int, boxHeight: Int, enlarge: Float = 1f): LogoPlan? {
    val found = inkOf(pixels, width, height) ?: return null
    val left = found.left
    val top = found.top
    val inkWidth = found.right - left + 1
    val inkHeight = found.bottom - top + 1
    val coverage = max(MIN_COVERAGE, found.alpha / (OPAQUE * inkWidth * inkHeight))
    val area = SURFACE * boxWidth * boxHeight / coverage.pow(INK_WEIGHT)
    val ratio = inkWidth.toDouble() / inkHeight
    val h = sqrt(area / ratio)
    val w = h * ratio
    val fit = minOf(1.0, boxWidth / w, boxHeight / h, inkWidth * enlarge / w)
    return LogoPlan(
        left,
        top,
        found.right + 1,
        found.bottom + 1,
        (w * fit).roundToInt().coerceAtLeast(1),
        (h * fit).roundToInt().coerceAtLeast(1),
    )
}

/** The bounds of a logo's ink (inclusive) and the alpha summed over all its pixels. */
private class Ink(val left: Int, val top: Int, val right: Int, val bottom: Int, val alpha: Long)

/** The ink of a logo [width] by [height] px of ARGB [pixels]: null when there is none. */
private fun inkOf(pixels: IntArray, width: Int, height: Int): Ink? {
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    var sum = 0L
    for (i in pixels.indices) {
        val alpha = pixels[i] ushr ALPHA_SHIFT
        sum += alpha
        if (alpha <= INK_FLOOR) continue
        val x = i % width
        val y = i / width
        if (x < left) left = x
        if (x > right) right = x
        if (y < top) top = y
        bottom = y
    }
    return if (right < 0) null else Ink(left, top, right, bottom, sum)
}

/** Alpha at or below which a pixel counts as margin, not ink (anti-aliasing haze, a faint glow). */
internal const val INK_FLOOR = 16

/** Fraction of the box a logo's weighted surface takes: a wide solid logo spans about two thirds of its width. */
internal const val SURFACE = 0.35

/** Weight of ink coverage against box area: 0 uses the box alone, 1 the ink alone. */
internal const val INK_WEIGHT = 0.5

/** Minimum ink coverage counted, so a mostly transparent logo is not sized past the box. */
private const val MIN_COVERAGE = 0.05

private const val OPAQUE = 255.0
private const val ALPHA_SHIFT = 24
