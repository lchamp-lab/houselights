package app.houselights.ui.design.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import app.houselights.core.ImageRef
import app.houselights.ui.design.HlMotion
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Posters a [PosterField] is drawn from, side by side. */
const val FIELD_POSTERS = 6

/** The strip they are laid in before it becomes a field: 16:9, three points across each of the field's. */
private const val STRIP_W = FIELD_W * 3
private const val STRIP_H = FIELD_H * 3

/** A picture's points, [width] × [height], as ARGB. */
internal class Points(val argb: IntArray, val width: Int, val height: Int)

/**
 * [posters] side by side across [width] × [height], each in an equal slot it fills as a picture cropped to fill,
 * centred, at its nearest point.
 */
internal fun posterStrip(posters: List<Points>, width: Int, height: Int): IntArray {
    require(posters.isNotEmpty()) { "No poster to lay" }
    val strip = IntArray(width * height)
    posters.forEachIndexed { i, poster ->
        val from = i * width / posters.size
        val slot = (i + 1) * width / posters.size - from
        val scale = max(slot / poster.width.toFloat(), height / poster.height.toFloat())
        val left = (poster.width * scale - slot) / 2
        val top = (poster.height * scale - height) / 2
        for (y in 0 until height) {
            val py = ((y + HALF + top) / scale).toInt().coerceIn(0, poster.height - 1)
            for (x in 0 until slot) {
                val px = ((x + HALF + left) / scale).toInt().coerceIn(0, poster.width - 1)
                strip[y * width + from + x] = poster.argb[py * poster.width + px]
            }
        }
    }
    return strip
}

private const val HALF = 0.5f

/** The light of [posters] as a wall's field ([diffuseField]): laid side by side ([posterStrip]), then made one. */
internal fun posterField(posters: List<Points>): IntArray = diffuseField(posterStrip(posters, STRIP_W, STRIP_H), STRIP_W, STRIP_H)

/** Reads the samples at [urls] and makes their field, off the main thread; null when none can be read. */
private suspend fun loadPosterField(context: Context, urls: List<String>): Bitmap? = coroutineScope {
    val samples = urls.map { async { decodeUrl(context, it) } }.awaitAll().filterNotNull()
    if (samples.isEmpty()) return@coroutineScope null
    withContext(Dispatchers.Default) {
        val points = samples.map { sample ->
            val argb = IntArray(sample.width * sample.height)
            sample.getPixels(argb, 0, sample.width, 0, 0, sample.width, sample.height)
            Points(argb, sample.width, sample.height)
        }
        Bitmap.createBitmap(posterField(points), FIELD_W, FIELD_H, Bitmap.Config.ARGB_8888)
    }
}

/** The last field made, by its posters: the settings and their forms come back to it at once. */
private object PosterFields {
    private var urls: List<String>? = null
    private var field: Bitmap? = null

    @Synchronized
    fun of(key: List<String>): Bitmap? = field.takeIf { key == urls }

    @Synchronized
    fun keep(key: List<String>, made: Bitmap) {
        urls = key
        field = made
    }
}

/**
 * The room of the settings and their forms (DESIGN.md, Secondary screens): the light of a library's [posters], up
 * to [FIELD_POSTERS] side by side, as a wall's diffuse field, capped as a wall's, under its veils; one fill, no
 * layer, made once. It comes in over `TINT` the first time; before, and without posters, the room stays black.
 */
@Composable
fun PosterField(posters: List<ImageRef>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val urls = remember(posters) { posters.take(FIELD_POSTERS).map { it.url(SAMPLE_PX) } }
    val known = remember(urls) { PosterFields.of(urls) }
    var field by remember(urls) { mutableStateOf(known) }
    val shown = remember(urls) { Animatable(if (known != null) 1f else 0f) }
    LaunchedEffect(urls) {
        if (field != null || urls.isEmpty()) return@LaunchedEffect
        val made = loadPosterField(context, urls) ?: return@LaunchedEffect
        PosterFields.keep(urls, made)
        field = made
        shown.animateTo(1f, tween(HlMotion.TINT, easing = LinearEasing))
    }
    val drawn = field ?: return
    Box(
        modifier.fillMaxSize().drawWithCache {
            val paint = DiffusePaint(drawn, size, BackdropStyle.Diffuse.veils.down).at(1f)
            onDrawBehind {
                // Its arrival read while drawing: the one fill fades in.
                paint.alpha = (shown.value * OPAQUE).roundToInt()
                drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
            }
        },
    )
}

private const val OPAQUE = 255
