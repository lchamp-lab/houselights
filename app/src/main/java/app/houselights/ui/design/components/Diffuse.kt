package app.houselights.ui.design.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import app.houselights.core.ImageRef
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.VeilStop
import app.houselights.ui.design.colorStops
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Size as DrawSize

/** A wall's diffuse field: 64 × 36 points, 9 KB a title, enlarged to the screen as it is drawn. */
internal const val FIELD_W = 64
internal const val FIELD_H = 36

/** Box blurs over the field, about a Gaussian of 2.4 points. */
private const val BLUR_PASSES = 3
private const val BLUR_RADIUS = 2

/** The field's colours, a little richer once blurred. */
private const val SATURATION = 1.2f

/** The field's light keeps its shape: this share of its points ends at or under the cap. */
private const val CAP_QUANTILE = 0.95f

private const val CHANNELS = 3
private const val LEVELS = 256
private const val LEVEL_MAX = 255

/** Rec. 709 weights of linear red, green and blue in luminance. */
private val LUMA = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

/** sRGB levels as linear light. */
private val LINEAR = FloatArray(LEVELS) { ColorSpaces.Srgb.eotf(it / LEVEL_MAX.toDouble()).toFloat() }

/**
 * The walls' light from a picture's [argb] points ([width] × [height]): cropped as a backdrop lays it, averaged
 * down to [FIELD_W] × [FIELD_H], blurred, its colour lifted, its 95th percentile brought to
 * `HlScrim.FIELD_LUMINANCE_MAX` and every point capped there once encoded.
 */
internal fun diffuseField(argb: IntArray, width: Int, height: Int): IntArray {
    require(argb.size == width * height && argb.isNotEmpty()) { "$width × $height points, ${argb.size} values" }
    // Cropped to the field's shape, its top and right edges kept, as the backdrop shows it.
    val cropW = minOf(width.toFloat(), height * FIELD_W / FIELD_H.toFloat())
    val cropH = minOf(height.toFloat(), width * FIELD_H / FIELD_W.toFloat())
    val field = Array(CHANNELS) { channel ->
        val shift = (CHANNELS - 1 - channel) * Byte.SIZE_BITS
        val plane = FloatArray(argb.size) { LINEAR[(argb[it] ushr shift) and LEVEL_MAX] }
        val across = averaged(plane, width, height, horizontal = true, from = width - cropW, span = cropW, out = FIELD_W)
        averaged(across, FIELD_W, height, horizontal = false, from = 0f, span = cropH, out = FIELD_H)
    }
    repeat(BLUR_PASSES) { field.forEach(::blurred) }
    val luma = FloatArray(FIELD_W * FIELD_H)
    for (i in luma.indices) {
        val y = lumaAt(field, i)
        for (plane in field) plane[i] = max(0f, y + SATURATION * (plane[i] - y))
        luma[i] = lumaAt(field, i)
    }
    val cap = HlScrim.FIELD_LUMINANCE_MAX
    val quantile = luma.sortedArray()[((luma.size - 1) * CAP_QUANTILE).roundToInt()]
    val scale = if (quantile > cap) cap / quantile else 1f
    return IntArray(luma.size) { i ->
        val y = luma[i] * scale
        val k = if (y > cap) scale * cap / y else scale
        // Rounded down, so no point is brighter than the cap once in 8 bits.
        var argbOut = LEVEL_MAX shl (CHANNELS * Byte.SIZE_BITS)
        field.forEachIndexed { channel, plane ->
            argbOut = argbOut or (encoded(plane[i] * k) shl ((CHANNELS - 1 - channel) * Byte.SIZE_BITS))
        }
        argbOut
    }
}

private fun lumaAt(field: Array<FloatArray>, i: Int): Float = LUMA[0] * field[0][i] + LUMA[1] * field[1][i] + LUMA[2] * field[2][i]

/** Linear light as an sRGB level, rounded down. */
private fun encoded(linear: Float): Int =
    floor(ColorSpaces.Srgb.oetf(linear.coerceIn(0f, 1f).toDouble()) * LEVEL_MAX).toInt().coerceIn(0, LEVEL_MAX)

/**
 * [plane] ([width] × [height]) averaged along one axis over [span] points from [from] into [out] points, each
 * point weighted by how much of it falls in each output's share.
 */
@Suppress("LongParameterList") // A plane, its size, the axis, the span and the points out: one pass of the average.
private fun averaged(plane: FloatArray, width: Int, height: Int, horizontal: Boolean, from: Float, span: Float, out: Int): FloatArray {
    val outW = if (horizontal) out else width
    val outH = if (horizontal) height else out
    val result = FloatArray(outW * outH)
    val step = span / out
    val points = if (horizontal) width else height
    // A line across the other axis: its length, and the strides between its points out and in.
    val line = if (horizontal) height else width
    val outStride = if (horizontal) outW else 1
    val inStride = if (horizontal) width else 1
    for (o in 0 until out) {
        val a = from + o * step
        val b = a + step
        val into = if (horizontal) o else o * outW
        var i = floor(a).toInt()
        while (i < b && i < points) {
            val weight = (minOf(b, i + 1f) - maxOf(a, i.toFloat())) / step
            val at = if (horizontal) i else i * width
            for (k in 0 until line) result[into + k * outStride] += plane[at + k * inStride] * weight
            i++
        }
    }
    return result
}

/** One box blur of a field plane, across then down, its edges held. */
private fun blurred(plane: FloatArray) {
    val line = FloatArray(max(FIELD_W, FIELD_H))
    val size = BLUR_RADIUS * 2 + 1
    for (y in 0 until FIELD_H) {
        for (x in 0 until FIELD_W) {
            var sum = 0f
            for (d in -BLUR_RADIUS..BLUR_RADIUS) sum += plane[y * FIELD_W + (x + d).coerceIn(0, FIELD_W - 1)]
            line[x] = sum / size
        }
        line.copyInto(plane, y * FIELD_W, 0, FIELD_W)
    }
    for (x in 0 until FIELD_W) {
        for (y in 0 until FIELD_H) {
            var sum = 0f
            for (d in -BLUR_RADIUS..BLUR_RADIUS) sum += plane[(y + d).coerceIn(0, FIELD_H - 1) * FIELD_W + x]
            line[y] = sum / size
        }
        for (y in 0 until FIELD_H) plane[y * FIELD_W + x] = line[y]
    }
}

/** Coil's step from a backdrop's sample to its diffuse field, run on Coil's decoding threads. */
internal object DiffuseField : Transformation {
    override val cacheKey = "houselights.diffuse"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val pixels = IntArray(input.width * input.height)
        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
        return Bitmap.createBitmap(diffuseField(pixels, input.width, input.height), FIELD_W, FIELD_H, Bitmap.Config.ARGB_8888)
    }
}

/** The diffuse field of [image]: its sample's file, memory-cached under its own key beside the sample. */
internal fun diffuseRequest(context: Context, image: ImageRef): ImageRequest =
    backdropSampleRequest(context, image).newBuilder().transformations(DiffuseField).build()

/**
 * A wall's [field] over [size], enlarged with bilinear filtering under its black veils [down] (shares of the
 * height), and dithered: one fill per picture, rebuilt only when the house lights lift the veils.
 */
internal class DiffusePaint(private val field: Bitmap, private val size: DrawSize, private val down: List<VeilStop>) {
    private var builtFor = Float.NaN
    private var paint = Paint()

    /** The paint with the veils at [lift]. */
    fun at(lift: Float): Paint {
        if (lift != builtFor) {
            paint = diffusePaint(field, size.width, size.height, down, lift)
            builtFor = lift
        }
        return paint
    }
}

/**
 * Paint filling [width] × [height] px with [field] cropped as a backdrop (top and right edges kept), enlarged
 * with bilinear filtering, times what its veils [down] at [lift] leave, plus an ordered 8 × 8 pattern under
 * one level, all in one shader: the screen rounds once, dithered, where a field this dark shows rings otherwise.
 * Its rounded-down levels make up for the pattern's half level on average.
 */
internal fun diffusePaint(field: Bitmap, width: Float, height: Float, down: List<VeilStop>, lift: Float): Paint {
    val scale = max(width / field.width, height / field.height)
    val picture = BitmapShader(field, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        setLocalMatrix(
            Matrix().apply {
                setScale(scale, scale)
                postTranslate(width - field.width * scale, 0f)
            },
        )
        // From Android 13 a shader inside another ignores the paint's filter, so it sets its own.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) filterMode = BitmapShader.FILTER_MODE_LINEAR
    }
    val stops = down.colorStops(Color.Black)
    val kept = LinearGradient(
        0f,
        0f,
        0f,
        height,
        IntArray(stops.size) { grey(1f - lift * stops[it].second.alpha) },
        FloatArray(stops.size) { stops[it].first },
        Shader.TileMode.CLAMP,
    )
    val tile = BitmapShader(Bayer.tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    val pattern = ComposeShader(tile, level(1), PorterDuff.Mode.MULTIPLY)
    return Paint(Paint.FILTER_BITMAP_FLAG).apply {
        shader = ComposeShader(ComposeShader(picture, kept, PorterDuff.Mode.MULTIPLY), pattern, PorterDuff.Mode.ADD)
    }
}

/** The ordered dither matrix, as a tile of greys centred in each of 64 steps. */
private object Bayer {
    private val ORDER = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21,
    )
    private const val SIDE = 8
    private const val STEP = 4

    val tile: Bitmap = Bitmap.createBitmap(IntArray(ORDER.size) { grey(ORDER[it] * STEP + STEP / 2) }, SIDE, SIDE, Bitmap.Config.ARGB_8888)
}

/** A constant shader of [levels] of 8 bits. */
private fun level(levels: Int): Shader = LinearGradient(0f, 0f, 1f, 0f, grey(levels), grey(levels), Shader.TileMode.CLAMP)

private fun grey(level: Int): Int = android.graphics.Color.rgb(level, level, level)

private fun grey(share: Float): Int = grey((share * LEVEL_MAX).roundToInt().coerceIn(0, LEVEL_MAX))
