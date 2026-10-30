package app.houselights.ui.design.components

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import app.houselights.core.MediaItem
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HomeGridDefaults
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.roundToInt

// The posters a library door's count is cut in, loaded off the main thread and kept for the app, and their tint.

/** A poster a count is cut in, read for its pixels, with its tone for the door's glow. */
@Immutable
class CutPoster(val bitmap: Bitmap, val tone: Color) {
    @Volatile
    private var scaled: Bitmap? = null

    /** This poster [height] px tall, as its digit draws it, if made already ([scaledTo]). */
    internal fun scaledOrNull(height: Int): Bitmap? = if (height == bitmap.height) bitmap else scaled?.takeIf { it.height == height }

    /** This poster [height] px tall, made once and kept: never on the main thread, as the door draws on. */
    internal fun scaledTo(height: Int): Bitmap = scaledOrNull(height) ?: synchronized(this) {
        scaledOrNull(height) ?: run {
            val width = (bitmap.width * height / bitmap.height.toFloat()).roundToInt().coerceAtLeast(1)
            bitmap.scale(width, height).also { scaled = it }
        }
    }
}

/**
 * The posters a door's count is cut in, their [tones] and their tint ([doorTint]), loaded; empty until they are. Let go
 * ([CutPosterCache.clear]), only the tones and the tint stay, the digits ink until the posters are cut again.
 */
@Immutable
class CutPosters(
    val posters: List<CutPoster?> = emptyList(),
    val tint: Bitmap? = null,
    val tones: List<Color> = posters.mapNotNull { it?.tone },
) {
    internal val whole: Boolean get() = posters.any { it != null }

    internal fun faded() = CutPosters(tint = tint, tones = tones)
}

/** The request of a poster a count is cut in, its pixels readable and kept apart from its card's in memory. */
internal fun cutPosterRequest(context: Context, item: MediaItem, px: (Dp) -> Int): ImageRequest? {
    val width = px(HlSize.poster)
    val url = item.poster?.url(width) ?: return null
    return artworkRequest(context, url, width, posterDecode(width)).newBuilder()
        .allowHardware(false)
        .setParameter(CUT, true)
        .build()
}

/** Added to a cut poster's memory cache key: its software copy never stands for its card's. */
private const val CUT = "houselights.cut"

/**
 * The posters of [items] a door's count is cut in, with their tint: those made last at once, else loaded off the main
 * thread, the posters shown until then staying, so a count reloaded never falls back to ink; let go, their colours at
 * once. Kept in the app's [LocalCutPosters], else in the door's own; never let go while drawn.
 */
@Composable
internal fun rememberCutPosters(items: List<MediaItem>): CutPosters {
    val context = LocalContext.current
    val density = LocalDensity.current
    val cuts = LocalCutPosters.current ?: remember { CutPosterCache() }
    val keys = remember(items) { cutKeys(items) }
    var shown by remember { mutableStateOf(cuts.shown(keys) ?: CutPosters()) }
    DisposableEffect(cuts, keys) {
        cuts.hold(keys)
        onDispose { cuts.release(keys) }
    }
    LaunchedEffect(keys) {
        shown = cuts.of(keys)
            ?: if (items.isEmpty()) CutPosters() else cuts.make(context, items) { with(density) { it.roundToPx() } }
    }
    return shown
}

/** The titles and their posters a cut is made of: a poster changed is another cut. */
internal fun cutKeys(items: List<MediaItem>): List<Any?> = items.flatMap { listOf(it.id, it.poster) }

/** The cut posters the app keeps ([CutPosterCache]); null outside it, each door keeping its own. */
internal val LocalCutPosters = staticCompositionLocalOf<CutPosterCache?> { null }

/**
 * The cut posters made last, by their titles and posters: a door coming back, or opened under the launch screen, draws
 * them at once. [kept]: home's library doors, and one more for a door whose posters changed, so it never takes another's.
 * The app holds one ([app.houselights.AppContainer.cutPosters]) and lets its posters go when the player needs the memory.
 */
class CutPosterCache(private val kept: Int = HomeGridDefaults.SHELF_DOORS + 1) {
    private val made = object : LinkedHashMap<List<Any?>, CutPosters>(kept, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any?>, CutPosters>) = size > kept
    }
    private val underWay = HashMap<List<Any?>, CompletableDeferred<CutPosters>>()

    /** The cuts doors on screen draw, and how many doors each. */
    private val drawn = HashMap<List<Any?>, Int>()

    /** The cut of [keys], its posters loaded; null when not made, or let go. */
    @Synchronized
    internal fun of(keys: List<Any?>): CutPosters? = made[keys]?.takeIf { it.whole }

    /** What a door draws of [keys] at once: its cut, else its tones and tint once let go ([clear]). */
    @Synchronized
    internal fun shown(keys: List<Any?>): CutPosters? = made[keys]

    @Synchronized
    internal fun keep(keys: List<Any?>, posters: CutPosters) {
        made[keys] = posters
    }

    /** A door draws [keys] until it [release]s them: their posters stay, as the door holds them anyway ([clear]). */
    @Synchronized
    internal fun hold(keys: List<Any?>) {
        drawn[keys] = (drawn[keys] ?: 0) + 1
    }

    @Synchronized
    internal fun release(keys: List<Any?>) {
        val doors = (drawn[keys] ?: return) - 1
        if (doors > 0) drawn[keys] = doors else drawn.remove(keys)
    }

    /**
     * Lets go the posters no door on screen draws, software copies the memory clear of Coil does not reach; tones and
     * tints stay. A door drawn keeps its own: letting them go would free nothing, and cut them again once it is back.
     */
    @Synchronized
    fun clear() {
        made.replaceAll { keys, cut -> if (keys in drawn) cut else cut.faded() }
    }

    /** As Coil trims its own memory: in the background, short of memory, the posters go ([clear]). */
    fun trim(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) clear()
    }

    /**
     * Loads the posters of [items] a door's count is cut in and keeps them, so the door shows them at once; for preloads
     * too. A making already under way for the same posters is awaited, not made again.
     */
    suspend fun make(context: Context, items: List<MediaItem>, px: (Dp) -> Int): CutPosters {
        val keys = cutKeys(items)
        of(keys)?.let { return it }
        val (making, mine) = making(keys)
        if (!mine) {
            return try {
                making.await()
            } catch (gone: CancellationException) {
                // Its maker went away: this one makes them, unless it is going away itself.
                if (!currentCoroutineContext().isActive) throw gone
                make(context, items, px)
            }
        }
        return try {
            loadCutPosters(context, items, px).also { made ->
                if (made.whole) keep(keys, made)
                making.complete(made)
            }
        } finally {
            // Cancelled or failed: whoever waits makes them again.
            if (!making.isCompleted) making.cancel()
            done(keys, making)
        }
    }

    /**
     * The making of [keys] under way, and whether the caller is to make it. One cancelled is never handed out: a waiter
     * its cancelling resumes before its maker is [done] makes them itself rather than await it again.
     */
    @Synchronized
    private fun making(keys: List<Any?>): Pair<CompletableDeferred<CutPosters>, Boolean> =
        underWay[keys]?.takeIf { !it.isCancelled }?.let { it to false }
            ?: CompletableDeferred<CutPosters>().also { underWay[keys] = it }.let { it to true }

    @Synchronized
    private fun done(keys: List<Any?>, making: CompletableDeferred<CutPosters>) {
        if (underWay[keys] === making) underWay.remove(keys)
    }

    private companion object {
        const val LOAD_FACTOR = 0.75f
    }
}

/** Each poster of [items] loaded once, though a count may be cut in one twice. */
private suspend fun loadCutPosters(context: Context, items: List<MediaItem>, px: (Dp) -> Int): CutPosters = coroutineScope {
    val distinct = items.distinctBy { it.id }
    val bitmaps = distinct.map { item ->
        async {
            cutPosterRequest(context, item, px)?.let { request ->
                (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
            }
        }
    }.awaitAll()
    withContext(Dispatchers.Default) {
        val loaded = distinct.zip(bitmaps).associate { (item, bitmap) ->
            item.id to bitmap?.let { CutPoster(it, lightOf(paletteOf(it)).glow) }
        }
        val posters = items.map { loaded[it.id] }
        CutPosters(posters, doorTint(posters.mapNotNull { it?.bitmap }))
    }
}

/**
 * A door's tint: its [posters] side by side, each cropped to fill its slot about its upper part, averaged down to
 * [TINT_W] × [TINT_H] points; enlarged with bilinear filtering, it reads as their colours blurred. Null without any.
 */
internal fun doorTint(posters: List<Bitmap>): Bitmap? {
    if (posters.isEmpty()) return null
    return Bitmap.createBitmap(averagedStrip(posters.map(::tintPoints), TINT_W, TINT_H), TINT_W, TINT_H, Bitmap.Config.ARGB_8888)
}

/**
 * [poster]'s points as its tint reads them: from a copy [TINT_SHRINK] times smaller, filtered, as the tint keeps a few
 * dozen points of it; a whole poster's pixels are never copied.
 */
internal fun tintPoints(poster: Bitmap): Points {
    val small = poster.scale((poster.width / TINT_SHRINK).coerceAtLeast(1), (poster.height / TINT_SHRINK).coerceAtLeast(1))
    val argb = IntArray(small.width * small.height)
    small.getPixels(argb, 0, small.width, 0, 0, small.width, small.height)
    // The copy alone: the poster itself is the loader's, and the door's.
    if (small !== poster) small.recycle()
    return Points(argb, small.width, small.height)
}

/** [posters] side by side across [width] × [height], each point the mean of what it covers, a poster cropped about its top fifth. */
internal fun averagedStrip(posters: List<Points>, width: Int, height: Int): IntArray {
    val strip = IntArray(width * height)
    posters.forEachIndexed { i, poster ->
        val from = i * width / posters.size
        val slot = (i + 1) * width / posters.size - from
        if (slot <= 0) return@forEachIndexed
        val scale = max(slot / poster.width.toFloat(), height / poster.height.toFloat())
        val cropW = slot / scale
        val cropH = height / scale
        val left = (poster.width - cropW) / 2
        val top = (poster.height - cropH) * TINT_TOP
        for (y in 0 until height) {
            for (x in 0 until slot) {
                val x0 = (left + x * cropW / slot).toInt().coerceIn(0, poster.width - 1)
                val x1 = (left + (x + 1) * cropW / slot).toInt().coerceIn(x0 + 1, poster.width)
                val y0 = (top + y * cropH / height).toInt().coerceIn(0, poster.height - 1)
                val y1 = (top + (y + 1) * cropH / height).toInt().coerceIn(y0 + 1, poster.height)
                strip[y * width + from + x] = meanOf(poster, x0, x1, y0, y1)
            }
        }
    }
    return strip
}

private fun meanOf(poster: Points, x0: Int, x1: Int, y0: Int, y1: Int): Int {
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0L
    for (y in y0 until y1) {
        for (x in x0 until x1) {
            val p = poster.argb[y * poster.width + x]
            r += (p shr RED) and LEVEL
            g += (p shr GREEN) and LEVEL
            b += p and LEVEL
            n++
        }
    }
    if (n == 0L) return OPAQUE_BLACK
    return OPAQUE_BLACK or ((r / n).toInt() shl RED) or ((g / n).toInt() shl GREEN) or (b / n).toInt()
}

private const val TINT_W = 24
private const val TINT_H = 9

/** How much smaller than a poster the copy its tint is read from: 60 × 90 points of a 480 × 720 poster. */
private const val TINT_SHRINK = 8
private const val TINT_TOP = 0.15f
private const val RED = 16
private const val GREEN = 8
private const val LEVEL = 0xFF
private const val OPAQUE_BLACK = -0x1000000
