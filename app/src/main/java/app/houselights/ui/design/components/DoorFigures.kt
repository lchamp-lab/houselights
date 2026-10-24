package app.houselights.ui.design.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HomeGridDefaults
import app.houselights.ui.design.figureAt
import app.houselights.ui.design.textRoom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

// A library door's count cut in its posters, at the one size every library door shares (DESIGN.md, Layout).

/**
 * A library's door picture: [count], each digit cut in one of [posters] (the next poster for each, its upper half),
 * edged by a fine line of ink, at the [size] every library's door shares ([figureSize]); a digit without its poster
 * is ink. Under it, the posters' tint ([CutPosters.tint]), dark at rest and lit whole once [lit].
 */
@Composable
fun DoorFigures(count: String, posters: CutPosters, size: TextUnit, lit: Boolean, modifier: Modifier = Modifier, readAs: String? = null) {
    val c = Hl.colors
    // Laid out and cut once for a count, its posters and its size: focus only lights the tint.
    val figure = rememberCutFigure(count, posters, size)
    val tints = remember { ScaledTint() }
    val tint = animateFloatAsState(
        if (lit) 1f else HlAlpha.DOOR_TINT,
        if (lit) tween(HlMotion.FOCUS_IN, easing = HlMotion.focusPull) else tween(HlMotion.FOCUS_OUT, easing = HlMotion.fallBack),
        label = "doorTint",
    )
    Box(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics { (readAs ?: count).let { contentDescription = it } }
            .drawWithCache {
                val start = HomeGridDefaults.figureStart.toPx()
                val origin = figure?.let { Offset(start, (this.size.height - it.layout.size.height) / 2) } ?: Offset.Zero
                val edge = Stroke(HlSize.hairline.toPx())
                val ink = SolidColor(c.content)
                val line = SolidColor(c.content.copy(alpha = HlAlpha.FIGURE_EDGE))
                val tintPaint = posters.tint?.let { tintPaint(tints.at(it, this.size)) }
                onDrawBehind {
                    tintPaint?.let { paint ->
                        // Its light read while drawing: focus redraws this door only.
                        paint.alpha = (tint.value * OPAQUE).roundToInt()
                        drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, this.size.width, this.size.height, paint) }
                    }
                    val cut = figure ?: return@onDrawBehind
                    // The layout's paint keeps what it was last given: each pass says its brush, its opacity and its style.
                    cut.digits.forEach { digit ->
                        clipRect(origin.x + digit.left, 0f, origin.x + digit.right, this.size.height) {
                            drawText(cut.layout, digit.brush ?: ink, origin, alpha = 1f, drawStyle = Fill)
                        }
                    }
                    drawText(cut.layout, line, origin, alpha = 1f, drawStyle = edge)
                }
            },
    )
}

/**
 * [count] laid out at [size] and cut in [posters]: at once when they are scaled to its line already; else they are, off
 * the main thread, the last cut drawn meanwhile. Null until there is a size.
 */
@Composable
private fun rememberCutFigure(count: String, posters: CutPosters, size: TextUnit): CutFigure? {
    val measurer = rememberTextMeasurer(cacheSize = 1)
    val style = Hl.type.figureSignature
    val layout = remember(count, size, style, measurer) {
        if (size.isSpecified) measurer.measure(count, style.figureAt(size), softWrap = false, maxLines = 1) else null
    }
    val ready = remember(layout, posters) { layout?.let { cutFigure(it, posters.posters, scale = false) } }
    var last by remember { mutableStateOf<CutFigure?>(null) }
    LaunchedEffect(layout, posters) {
        last = ready ?: layout?.let { withContext(Dispatchers.Default) { cutFigure(it, posters.posters, scale = true) } }
    }
    return if (layout == null) null else ready ?: last
}

/** A count laid out, and its digits cut. */
private class CutFigure(val layout: TextLayoutResult, val digits: List<Cut>)

/** One digit of a cut count: from [left] to [right] across its line, and its poster's brush (none: ink). */
private class Cut(val left: Float, val right: Float, val brush: Brush?)

/**
 * [layout]'s digits, each with the next of [posters] as its brush, in the layout's own coordinates: the poster at the
 * size that makes it 2.1 times the line's height, centred across the digit and from a tenth down, so its upper half
 * shows. Each digit reaches half way to its neighbours, so no stroke of it is lost. The posters are scaled to that
 * size once when [scale]s (off the main thread); without, null until they are.
 */
private fun cutFigure(layout: TextLayoutResult, posters: List<CutPoster?>, scale: Boolean): CutFigure? {
    val text = layout.layoutInput.text.text
    val boxes = text.indices.filter { text[it].isDigit() }.map { layout.getBoundingBox(it) }
    val line = layout.size.height.toFloat()
    val height = (line * POSTER_OVER_LINE).roundToInt().coerceAtLeast(1)
    val digits = boxes.mapIndexed { k, box ->
        val left = if (k == 0) -BIG else (boxes[k - 1].right + box.left) / 2
        val right = if (k == boxes.lastIndex) BIG else (box.right + boxes[k + 1].left) / 2
        val brush = posters.getOrNull(k)?.let { poster ->
            val drawn = if (scale) poster.scaledTo(height) else poster.scaledOrNull(height) ?: return null
            posterBrush(drawn, Rect(box.left, 0f, box.right, line))
        }
        Cut(left, right, brush)
    }
    return CutFigure(layout, digits)
}

/** A poster at its drawn size ([CutPoster.scaledTo]) on its [digit]: the text paint, which does not filter, draws it point for point. */
private fun posterBrush(drawn: Bitmap, digit: Rect): Brush {
    val shader = BitmapShader(drawn, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        val x = digit.left + (digit.width - drawn.width) * POSTER_ACROSS
        val y = digit.top + (digit.height - drawn.height) * POSTER_DOWN
        setLocalMatrix(Matrix().apply { setTranslate(x.roundToInt().toFloat(), y.roundToInt().toFloat()) })
    }
    return ShaderBrush(shader)
}

/** A door's tint enlarged once to the size it is drawn at, with bilinear filtering: drawn point for point, it never shows its points. */
private class ScaledTint {
    private var made: Triple<Bitmap, Int, Int>? = null
    private var scaled: Bitmap? = null

    fun at(tint: Bitmap, size: Size): Bitmap {
        val w = size.width.roundToInt().coerceAtLeast(1)
        val h = size.height.roundToInt().coerceAtLeast(1)
        val key = Triple(tint, w, h)
        scaled?.takeIf { made == key }?.let { return it }
        return Bitmap.createScaledBitmap(tint, w, h, true).also {
            made = key
            scaled = it
        }
    }
}

/** The paint of a [tint] drawn at its size: lowered and coloured as a door's dark. */
private fun tintPaint(tint: Bitmap): Paint {
    val shader = BitmapShader(tint, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    val light = HlScrim.DOOR_TINT_LIGHT
    val matrix = android.graphics.ColorMatrix().apply { setSaturation(HlScrim.DOOR_TINT_SATURATION) }
    matrix.postConcat(android.graphics.ColorMatrix().apply { setScale(light, light, light, 1f) })
    return Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        this.shader = shader
        colorFilter = ColorMatrixColorFilter(matrix)
    }
}

private const val POSTER_OVER_LINE = 2.1f
private const val POSTER_ACROSS = 0.5f
private const val POSTER_DOWN = 0.1f
private const val BIG = 1e6f
private const val OPAQUE = 255

/** How tall a figure stands in its line, as a share of its size: Archivo's lining figures. */
private const val FIGURE_HEIGHT = 0.7f

/**
 * The one size every library's door gives its count (DESIGN.md, Layout): the largest that leaves [airX] across and
 * [airY] down for the longest. [widths] are the counts' widths at [reference] px; [boxes], their doors' pictures (px).
 */
internal fun figureSize(widths: List<Float>, boxes: List<Size>, reference: Float, airX: Float, airY: Float): Float =
    widths.zip(boxes).minOfOrNull { (w, box) ->
        min(if (w > 0f) (box.width - 2 * airX) / w * reference else Float.MAX_VALUE, (box.height - 2 * airY) / FIGURE_HEIGHT)
    }?.coerceAtLeast(0f) ?: 0f

/**
 * The size the library doors' [counts] share, in a row of [spans] (parts of four) [rowHeight] tall: measured once at
 * the signature's size, then scaled to their pictures ([figureSize]); unspecified until there is a count.
 */
@Composable
fun rememberFigureSize(counts: List<String>, spans: List<Int>, rowHeight: Dp): TextUnit {
    val measurer = rememberTextMeasurer(cacheSize = FIGURE_MEASURES)
    val style = Hl.type.figureSignature
    val density = LocalDensity.current
    val band = textRoom(HomeGridDefaults.band)
    return remember(counts, spans, rowHeight, band, style, density) {
        if (counts.isEmpty() || counts.size != spans.size) return@remember TextUnit.Unspecified
        with(density) {
            val reference = style.fontSize.toPx()
            val widths = counts.map { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width.toFloat() }
            val doors = placeDoors(listOf(spans), listOf((rowHeight - band).toPx()), HlSpace.s.toPx(), DEFAULT_WIDTH.dp.toPx()).first()
            val px = figureSize(
                widths,
                doors.map { Size(it.width, it.height) },
                reference,
                HomeGridDefaults.figureAirX.toPx(),
                HomeGridDefaults.figureAirY.toPx(),
            )
            px.toSp()
        }
    }
}

private const val FIGURE_MEASURES = 8
