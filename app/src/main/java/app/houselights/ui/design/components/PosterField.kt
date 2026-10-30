package app.houselights.ui.design.components

import android.content.Context
import android.graphics.Bitmap
import androidx.collection.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.util.lerp
import app.houselights.core.ImageRef
import app.houselights.ui.design.AmbientLight
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HouselightsColors
import app.houselights.ui.design.LocalAmbient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

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

/** A field made from the posters at [urls]: its [picture] and its [light], their dark and glow. */
internal class MadeField(val urls: List<String>, val picture: Bitmap, val light: ImageLight)

/** Reads the samples at [urls] and makes their field and light, off the main thread; null when none can be read. */
private suspend fun loadPosterField(context: Context, urls: List<String>): MadeField? = coroutineScope {
    val samples = urls.map { async { decodeUrl(context, it) } }.awaitAll().filterNotNull()
    if (samples.isEmpty()) return@coroutineScope null
    withContext(Dispatchers.Default) {
        val points = samples.map { sample ->
            val argb = IntArray(sample.width * sample.height)
            sample.getPixels(argb, 0, sample.width, 0, 0, sample.width, sample.height)
            Points(argb, sample.width, sample.height)
        }
        val strip = posterStrip(points, STRIP_W, STRIP_H)
        val light = lightOf(paletteOf(Bitmap.createBitmap(strip, STRIP_W, STRIP_H, Bitmap.Config.ARGB_8888)))
        MadeField(urls, Bitmap.createBitmap(diffuseField(strip, STRIP_W, STRIP_H), FIELD_W, FIELD_H, Bitmap.Config.ARGB_8888), light)
    }
}

/** The last fields made, by their posters: the settings and their forms come back to them at once. */
internal object PosterFields {
    private val made = LruCache<List<String>, MadeField>(KEPT)

    fun of(urls: List<String>): MadeField? = made[urls]

    fun keep(field: MadeField) {
        made.put(field.urls, field)
    }

    /** The one showing and the one coming in. */
    private const val KEPT = 2
}

/**
 * The room a [PosterField] draws, read while drawing: the field [under], whole, and the one coming in [over] it,
 * one fade at a time; its light is the screen's ([AmbientLight]): its dark, plates at `PLATE`, its glow.
 */
@Stable
internal class RoomField : AmbientLight {
    var under by mutableStateOf<MadeField?>(null)
        private set
    var over by mutableStateOf<MadeField?>(null)
        private set
    val coming = Animatable(0f)

    /** How much of a field shows: 0 on the bare room, 1 once one is whole. */
    fun shown(): Float = if (under != null) 1f else if (over != null) coming.value else 0f

    override fun deep(): Color = blend(under?.light?.deep ?: HouselightsColors.ambientDeep, over?.light?.deep)

    override fun plateAlpha(): Float = HlAlpha.PLATE

    override fun glow(): Color = blend(under?.light?.glow ?: Color.Transparent, over?.light?.glow)

    /** What a scrolling edge fades into over the field, read while drawing: the room's black, short of opaque on a field. */
    fun edge(room: Color): Color = room.copy(alpha = lerp(1f, HlAlpha.VEIL, shown()))

    private fun blend(from: Color, to: Color?): Color = if (to == null) from else lerp(from, to, coming.value)

    /** Shows [field] at once: the first frame of a visit, from the fields kept. */
    fun showAtOnce(field: MadeField) {
        under = field
    }

    /** Brings [field] in over the one showing, which stays whole under it until it is in: the room never dips. */
    suspend fun bringIn(field: MadeField) {
        coming.snapTo(0f)
        over = field
        coming.animateTo(1f, tween(HlMotion.TINT, easing = LinearEasing))
        under = field
        over = null
        coming.snapTo(0f)
    }
}

/** The room a [PosterField] provides, for what fades into it ([HlTwoPane]) and the plates that lie on it. */
internal val LocalRoomField = staticCompositionLocalOf<RoomField?> { null }

/**
 * The room of the settings and their forms (DESIGN.md, Secondary screens), under [content]: the light of a library's
 * [posters], up to [FIELD_POSTERS] side by side, as a wall's diffuse field, capped as a wall's, under its veils; one
 * fill, no layer. The last fields made come back at once; a new one comes in over `TINT`, the one showing kept whole
 * under it. Its light is [content]'s ([LocalAmbient]): plates on it take its dark.
 */
@Composable
fun PosterField(posters: List<ImageRef>, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val room = rememberRoomField(posters)
    Box(
        modifier.fillMaxSize().drawWithCache {
            val down = BackdropStyle.Diffuse.veils.down
            // Read here, so the paints are made again only when the fields change.
            val under = room.under?.let { DiffusePaint(it.picture, size, down).at(1f) }
            val over = room.over?.let { DiffusePaint(it.picture, size, down).at(1f) }
            onDrawBehind {
                drawIntoCanvas { canvas ->
                    under?.let {
                        it.alpha = OPAQUE
                        canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, it)
                    }
                    over?.let {
                        // Its arrival read while drawing: one fill fades in over the other.
                        it.alpha = (room.coming.value * OPAQUE).roundToInt()
                        canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, it)
                    }
                }
            }
        },
    ) {
        CompositionLocalProvider(LocalAmbient provides room, LocalRoomField provides room, content = content)
    }
}

/**
 * The room of [posters]: the field kept for them at once, else each new list's field made by [make] and brought in,
 * one fade at a time, the last list asked for after the one running.
 */
@Composable
internal fun rememberRoomField(
    posters: List<ImageRef>,
    make: (suspend (Context, List<String>) -> MadeField?)? = null,
): RoomField {
    val context = LocalContext.current
    val urls = remember(posters) { posters.take(FIELD_POSTERS).map { it.url(SAMPLE_PX) } }
    val room = remember { RoomField().apply { PosterFields.of(urls)?.let(::showAtOnce) } }
    val wanted by rememberUpdatedState(urls)
    LaunchedEffect(room) {
        snapshotFlow { wanted }.conflate().collect { next ->
            if (next.isEmpty() || next == room.under?.urls) return@collect
            val field = PosterFields.of(next) ?: (make ?: ::loadPosterField)(context, next)?.also(PosterFields::keep)
            field?.let { room.bringIn(it) }
        }
    }
    return room
}

private const val OPAQUE = 255
