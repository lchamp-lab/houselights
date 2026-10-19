package app.houselights.ui.design.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import coil.size.Precision
import coil.size.Scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import app.houselights.core.ImageRef
import app.houselights.ui.design.ArtworkSample
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.LocalLaunchScreen
import app.houselights.ui.design.greyOf
import coil.decode.DataSource
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val SAMPLE_PX = 96

/**
 * The ambient light of [image]: a calm tone from its sample, animated on change and read while drawing;
 * transparent without a picture. The first tone, under the launch screen, snaps in.
 */
@Composable
fun rememberAmbientColor(image: ImageRef?): State<Color> {
    val context = LocalContext.current
    val launch = LocalLaunchScreen.current
    val light = remember { Animatable(Color.Transparent) }
    LaunchedEffect(image) {
        val target = image?.let { backdropSample(context, it)?.let { sample -> toneOf(sample) } } ?: Color.Transparent
        if (light.value == Color.Transparent && launch.covers) {
            light.snapTo(target)
        } else {
            light.animateTo(target, tween(HlMotion.SLOW, easing = HlMotion.fallBack))
        }
    }
    return light.asState()
}

/**
 * A backdrop ready to show: [picture] at screen size and the [sample] its veil is computed from;
 * [fromMemory] when it was a memory-cache hit, [atOnce] to skip the fade.
 */
internal class PreparedBackdrop(
    val picture: ImageBitmap,
    val sample: ArtworkSample,
    val fromMemory: Boolean = false,
    val atOnce: Boolean = false,
) {
    fun shownAtOnce() = PreparedBackdrop(picture, sample, fromMemory, atOnce = true)
}

/** Loads a backdrop picture and its sample in parallel, off the main thread; null if either fails. */
internal suspend fun prepareBackdrop(context: Context, image: ImageRef, url: String, widthPx: Int, heightPx: Int): PreparedBackdrop? =
    coroutineScope {
        val picture = async { context.imageLoader.execute(backdropRequest(context, url, widthPx, heightPx)) as? SuccessResult }
        val small = backdropSample(context, image)
        val sample = small?.let { withContext(Dispatchers.Default) { sampleOf(it) } }
        val loaded = picture.await()
        val decoded = loaded?.drawable?.toBitmap()?.asImageBitmap()
        val fromMemory = loaded?.dataSource == DataSource.MEMORY_CACHE
        if (decoded == null || sample == null) null else PreparedBackdrop(decoded, sample, fromMemory)
    }

/**
 * A backdrop request, never decoded larger than its file: a smaller one (1280 px on a 1920 px screen) is
 * upscaled once into the cached layer instead of taking twice the memory.
 */
internal fun backdropRequest(context: Context, url: String, widthPx: Int, heightPx: Int): ImageRequest =
    ImageRequest.Builder(context).data(url).size(widthPx, heightPx).scale(Scale.FILL).precision(Precision.INEXACT).build()

private suspend fun decodeSample(context: Context, image: ImageRef): Bitmap? =
    (context.imageLoader.execute(backdropSampleRequest(context, image)) as? SuccessResult)?.drawable?.toBitmap()

private suspend fun decodeUrl(context: Context, url: String): Bitmap? =
    (context.imageLoader.execute(glowSampleRequest(context, url)) as? SuccessResult)?.drawable?.toBitmap()

/** Request for the small sample of a backdrop that its veil and the ambient light are read from. */
internal fun backdropSampleRequest(context: Context, image: ImageRef): ImageRequest =
    sampleRequest(context, image.url(SAMPLE_PX), SAMPLE_PX, SAMPLE_PX * 9 / 16)

/** Request for a card's glow sample; kept out of the memory cache, since [glowTones] caches the tone. */
internal fun glowSampleRequest(context: Context, url: String): ImageRequest =
    sampleRequest(context, url, SAMPLE_PX, SAMPLE_PX).newBuilder().memoryCachePolicy(CachePolicy.READ_ONLY).build()

/** A sample memory-cached under its own key: under the URL alone it would evict the full-size picture. */
private fun sampleRequest(context: Context, url: String, widthPx: Int, heightPx: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .size(widthPx, heightPx)
        .allowHardware(false) // Palette and the veil read the pixels.
        .setParameter(SAMPLE, "${widthPx}x$heightPx")
        .build()

/** Added to a sample's memory cache key; fetchers and decoders ignore it. */
private const val SAMPLE = "houselights.sample"

/** Glow tones by URL (LRU), so focus returning to a card lights it at once. */
private val glowTones = object : LinkedHashMap<String, Color>(GLOW_TONES, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>) = size > GLOW_TONES
}

private const val GLOW_TONES = 200

/** A card's glow: the main tone of its artwork at [url] ([glowOf]), sampled once [active]; focus lights it. */
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
    return animateColorAsState(tone ?: Color.Transparent, tween(HlMotion.FOCUS_IN, easing = HlMotion.focusPull), label = "glow")
}

/** The tone covering most of the picture, weighted by area and saturation: a red title on teal glows teal. */
private fun glowOf(palette: Palette): Color? {
    val swatch = palette.swatches
        .filter { it.hsl[2] in HlScrim.GLOW_LIGHTNESS_MIN..HlScrim.GLOW_LIGHTNESS_MAX }
        .maxByOrNull { it.population * (HlScrim.GLOW_AREA_WEIGHT + it.hsl[1]) }
        ?: palette.dominantSwatch ?: return null
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(swatch.rgb, hsv)
    // Clamp the saturation of a coloured swatch; a grey one keeps its own and glows white.
    if (hsv[1] >= HlScrim.GLOW_GREY) hsv[1] = hsv[1].coerceIn(HlScrim.GLOW_SATURATION_MIN, HlScrim.GLOW_SATURATION_MAX)
    hsv[2] = HlScrim.GLOW_VALUE
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Serialises the sample decodes of the ambient light and the veil, so the second hits the memory cache. */
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

private fun ambientOf(palette: Palette): Color? {
    val swatch = palette.vibrantSwatch ?: palette.darkVibrantSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch ?: return null
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(swatch.rgb, hsv)
    // Clamped saturation and a fixed value, so every film lights the room equally.
    hsv[1] = hsv[1].coerceIn(HlScrim.AMBIENT_SATURATION_MIN, HlScrim.AMBIENT_SATURATION_MAX)
    hsv[2] = HlScrim.AMBIENT_VALUE
    return Color(android.graphics.Color.HSVToColor(hsv))
}
