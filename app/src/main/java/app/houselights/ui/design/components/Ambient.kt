package app.houselights.ui.design.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.scale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import app.houselights.core.ImageRef
import app.houselights.ui.design.ArtworkSample
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.greyOf
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val SAMPLE_PX = 96

/**
 * The light a picture spills into the room: a calm, mid-bright tone picked from a tiny
 * copy of the artwork, animated when the artwork changes. A state, read while drawing: screens
 * provide it as `LocalAmbient provides { ambient.value }`. No picture, or one that fails to load,
 * gives no light: transparent, and the room stays black.
 */
@Composable
fun rememberAmbientColor(image: ImageRef?): State<Color> {
    val context = LocalContext.current
    var target by remember { mutableStateOf(Color.Transparent) }
    LaunchedEffect(image) {
        if (image == null) {
            target = Color.Transparent
            return@LaunchedEffect
        }
        target = backdropSample(context, image)?.let { toneOf(it) } ?: Color.Transparent
    }
    return animateColorAsState(target, tween(HlMotion.SLOW, easing = HlMotion.standard), label = "ambient")
}

/**
 * How bright each part of a backdrop is, for the veil laid over it: read from the same tiny copy
 * as its light. Null without a picture; the last one stays while the next loads.
 */
@Composable
internal fun rememberArtworkSample(image: ImageRef?): ArtworkSample? {
    val context = LocalContext.current
    var sample by remember { mutableStateOf<ArtworkSample?>(null) }
    LaunchedEffect(image) {
        sample = image?.let { backdropSample(context, it) }?.let { withContext(Dispatchers.Default) { sampleOf(it) } }
    }
    return sample
}

/**
 * A tiny copy of [image], or null when it cannot be loaded. [sourcePx] picks the file to sample:
 * the one a card already shows comes from the cache instead of the network.
 */
private suspend fun decodeSample(context: Context, image: ImageRef, sourcePx: Int = SAMPLE_PX): Bitmap? {
    val request = ImageRequest.Builder(context)
        .data(image.url(sourcePx))
        .size(SAMPLE_PX, SAMPLE_PX * 9 / 16)
        .allowHardware(false) // Palette and the veil need to read the pixels.
        .build()
    return (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
}

/** A tiny copy of the picture at [url], or null when it cannot be loaded. */
private suspend fun decodeUrl(context: Context, url: String): Bitmap? {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(SAMPLE_PX, SAMPLE_PX)
        .allowHardware(false) // Palette reads the pixels.
        .build()
    return (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
}

/** Tones already read from artwork: focus coming back to a card finds its light at once. */
private val glowTones = object : LinkedHashMap<String, Color>(GLOW_TONES, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>) = size > GLOW_TONES
}

private const val GLOW_TONES = 200

/**
 * The light a card gives off while it has focus: the most vivid tone of its own artwork at [url],
 * the same each time. It comes in with focus and goes with it; no picture, no light. Read from the
 * picture the card already shows, so it is ready before the room's light has followed.
 */
@Composable
fun rememberGlowColor(url: String?, active: Boolean): State<Color> {
    val context = LocalContext.current
    var tone by remember(url) { mutableStateOf(url?.let { synchronized(glowTones) { glowTones[it] } }) }
    LaunchedEffect(url, active) {
        if (!active || url == null || tone != null) return@LaunchedEffect
        val bitmap = decodeUrl(context, url) ?: return@LaunchedEffect
        val found = withContext(Dispatchers.Default) { glowOf(Palette.from(bitmap).maximumColorCount(16).generate()) }
        if (found != null) {
            synchronized(glowTones) { glowTones[url] = found }
            tone = found
        }
    }
    val target = if (active) tone ?: Color.Transparent else Color.Transparent
    return animateColorAsState(target, tween(HlMotion.STANDARD, easing = HlMotion.standard), label = "glow")
}

private fun glowOf(palette: Palette): Color? {
    val swatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.darkVibrantSwatch
        ?: palette.mutedSwatch ?: palette.dominantSwatch ?: return null
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(swatch.rgb, hsv)
    // A colour glows vivid; a grey picture glows white rather than in a hue it does not have.
    if (hsv[1] >= HlScrim.GLOW_GREY) hsv[1] = hsv[1].coerceIn(HlScrim.GLOW_SATURATION_MIN, HlScrim.GLOW_SATURATION_MAX)
    hsv[2] = HlScrim.GLOW_VALUE
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** The light and the veil of a backdrop take turns: the second finds the copy decoded, in memory. */
private val backdropSampling = Mutex()

private suspend fun backdropSample(context: Context, image: ImageRef): Bitmap? =
    backdropSampling.withLock { decodeSample(context, image) }

private suspend fun toneOf(bitmap: Bitmap): Color? =
    withContext(Dispatchers.Default) { ambientOf(Palette.from(bitmap).maximumColorCount(16).generate()) }

private fun sampleOf(bitmap: Bitmap): ArtworkSample {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return ArtworkSample(bitmap.width, bitmap.height, FloatArray(pixels.size) { greyOf(Color(pixels[it])) })
}

/**
 * The light of a collection, for when no title is selected: each of [posters] (the top row
 * of a wall, left to right) lends its tone to a soft, wide glow above its own column, so the
 * room takes the colours of what is on the wall without showing any one film. It fades in
 * and out with [lit].
 */
@Composable
fun CollectionLight(posters: List<ImageRef>, lit: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val posterPx = posterPx()
    var tones by remember { mutableStateOf<List<Color>>(emptyList()) }
    LaunchedEffect(posters, lit) {
        // Only while the light shows. The wall's posters are in the cache: tones come from there, in parallel.
        if (!lit) return@LaunchedEffect
        tones = coroutineScope {
            posters.map { async { decodeSample(context, it, posterPx)?.let { bitmap -> toneOf(bitmap) } } }.awaitAll()
        }.filterNotNull()
    }
    val light by animateFloatAsState(if (lit) 1f else 0f, tween(HlMotion.SLOW, easing = HlMotion.standard), label = "collection")
    Crossfade(tones, modifier.fillMaxSize(), tween(HlMotion.SLOW, easing = HlMotion.standard), label = "tones") { colors ->
        Box(
            Modifier.fillMaxSize().drawBehind {
                if (light <= 0f || colors.isEmpty()) return@drawBehind
                val column = size.width / colors.size
                colors.forEachIndexed { i, tone ->
                    val center = Offset(column * (i + 0.5f), size.height * HlScrim.COLLECTION_Y)
                    val radius = size.height * HlScrim.COLLECTION_RADIUS
                    // Taller than wide: each glow stays over its column and blends with its neighbours.
                    scale(scaleX = (column * HlScrim.COLLECTION_SPREAD) / radius, scaleY = 1f, pivot = center) {
                        drawCircle(
                            Brush.radialGradient(
                                0f to tone.copy(alpha = HlScrim.COLLECTION_CORE * light),
                                HlScrim.COLLECTION_MID_AT to tone.copy(alpha = HlScrim.COLLECTION_MID * light),
                                1f to Color.Transparent,
                                center = center,
                                radius = radius,
                            ),
                            radius = radius,
                            center = center,
                        )
                    }
                }
            }
        )
    }
}

private fun ambientOf(palette: Palette): Color? {
    val swatch = palette.vibrantSwatch ?: palette.darkVibrantSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch ?: return null
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(swatch.rgb, hsv)
    // Never garish: tame saturation, fixed brightness so every film glows the same amount.
    hsv[1] = hsv[1].coerceIn(0.25f, 0.7f)
    hsv[2] = HlScrim.AMBIENT_VALUE
    return Color(android.graphics.Color.HSVToColor(hsv))
}
