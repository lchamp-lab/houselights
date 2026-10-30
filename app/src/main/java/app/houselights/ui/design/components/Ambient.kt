package app.houselights.ui.design.components

import android.content.Context
import android.graphics.Bitmap
import androidx.collection.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import app.houselights.core.ImageRef
import app.houselights.ui.design.ArtworkSample
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HouselightsColors
import app.houselights.ui.design.greyOf
import coil.decode.DataSource
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Width of the sample a backdrop's light, veils and diffuse field are read from. */
internal const val SAMPLE_PX = 96

/** A backdrop's sample is 16:9, as the backdrop. */
private const val BACKDROP_SAMPLE_HEIGHT_PX = SAMPLE_PX * 9 / 16

/** A picture's light, read once from its sample: its own [deep] (veils, plates) and its [glow]. */
@Immutable
data class ImageLight(val deep: Color, val glow: Color) {
    companion object {
        /** A grey picture: the neutral dark, and a light grey glow. */
        val Grey = ImageLight(HouselightsColors.ambientDeep, Color.hsv(0f, 0f, HlScrim.GLOW_VALUE))
    }
}

/**
 * The tone covering most of [palette]'s picture, weighted by its saturation, among its coloured tones: a red
 * title on teal is teal, and a few sparks do not tint a red picture. Grey when they cover under `TINT_SHARE`.
 */
internal fun lightOf(palette: Palette): ImageLight {
    val swatches = palette.swatches
    val total = swatches.sumOf { it.population }.coerceAtLeast(1)
    val coloured = swatches.filter { hsvOf(it.rgb)[1] >= HlScrim.GLOW_GREY }
    if (coloured.sumOf { it.population } < HlScrim.TINT_SHARE * total) return ImageLight.Grey
    val swatch = coloured.maxBy { it.population * (HlScrim.GLOW_AREA_WEIGHT + it.hsl[1]) }
    val (hue, saturation) = hsvOf(swatch.rgb)
    return ImageLight(deepOf(hue, saturation), glowOf(hue, saturation))
}

/** The picture's dark: its hue at value `DEEP_VALUE`, lowered a step at a time until its luminance is low enough. */
internal fun deepOf(hue: Float, saturation: Float): Color {
    val s = saturation.coerceAtMost(HlScrim.DEEP_SATURATION_MAX)
    var value = HlScrim.DEEP_VALUE
    // Built in 8 bits: the luminance checked is the one drawn.
    var deep = Color.hsv(hue, s, value)
    while (deep.luminance() > HlScrim.DEEP_LUMINANCE_MAX && value > 0f) {
        value = (value - 1f / CHANNEL_MAX).coerceAtLeast(0f)
        deep = Color.hsv(hue, s, value)
    }
    return deep
}

/** The picture's glow: its hue, bright, its saturation held between `GLOW_SATURATION_MIN` and `_MAX`. */
internal fun glowOf(hue: Float, saturation: Float): Color =
    Color.hsv(hue, saturation.coerceIn(HlScrim.GLOW_SATURATION_MIN, HlScrim.GLOW_SATURATION_MAX), HlScrim.GLOW_VALUE)

private const val CHANNEL_MAX = 255f

private const val HSV_COMPONENTS = 3

private fun hsvOf(rgb: Int) = FloatArray(HSV_COMPONENTS).also { android.graphics.Color.colorToHSV(rgb, it) }

/**
 * A backdrop ready to show: [picture] (the screen-size image, or a wall's diffuse field), the [sample] its
 * veils are reckoned from and its [light]; [fromMemory] when it was a memory-cache hit.
 */
internal class PreparedBackdrop(
    val picture: ImageBitmap,
    val sample: ArtworkSample,
    val light: ImageLight = ImageLight.Grey,
    val fromMemory: Boolean = false,
)

/**
 * Loads [image] as a backdrop in [style] on a screen [screenPx] wide, and its sample, off the main thread; null
 * if either fails. A wall's diffuse field comes from the sample's own file, after it.
 */
internal suspend fun prepareBackdrop(context: Context, image: ImageRef, style: BackdropStyle, screenPx: Int): PreparedBackdrop? =
    coroutineScope {
        val loader = context.imageLoader
        val request = style.pictureRequest(context, image, screenPx)
        val picture = if (style.diffuse) null else async { loader.execute(request) as? SuccessResult }
        val small = decodeSample(context, image)
        val read = small?.let { withContext(Dispatchers.Default) { sampleOf(it) to lightOf(paletteOf(it)) } }
        val loaded = when {
            picture != null -> picture.await()
            small != null -> loader.execute(request) as? SuccessResult
            else -> null
        }
        val decoded = loaded?.drawable?.toBitmap()?.asImageBitmap()
        if (decoded == null || read == null) {
            null
        } else {
            PreparedBackdrop(decoded, read.first, read.second, fromMemory = loaded.dataSource == DataSource.MEMORY_CACHE)
        }
    }

private suspend fun decodeSample(context: Context, image: ImageRef): Bitmap? =
    (context.imageLoader.execute(backdropSampleRequest(context, image)) as? SuccessResult)?.drawable?.toBitmap()

/** A small sample of the picture at [url] (a card's glow, a poster field's), its pixels readable. */
internal suspend fun decodeUrl(context: Context, url: String): Bitmap? =
    (context.imageLoader.execute(glowSampleRequest(context, url)) as? SuccessResult)?.drawable?.toBitmap()

/** Request for the small sample of a backdrop that its veils and light are read from. */
internal fun backdropSampleRequest(context: Context, image: ImageRef): ImageRequest =
    sampleRequest(context, image.url(SAMPLE_PX), SAMPLE_PX, BACKDROP_SAMPLE_HEIGHT_PX)

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
private val glowTones = LruCache<String, Color>(GLOW_TONES)

private const val GLOW_TONES = 200

/** A card's glow: its artwork's light at [url] ([lightOf]), sampled once [active]; focus lights it. */
@Composable
fun rememberGlowColor(url: String?, active: Boolean): State<Color> {
    val tone by rememberGlowTone(url, active)
    return animateColorAsState(tone ?: Color.Transparent, tween(HlMotion.FOCUS_IN, easing = HlMotion.focusPull), label = "glow")
}

/** The tone of [rememberGlowColor] as it is read, without its arrival: null until sampled. */
@Composable
internal fun rememberGlowTone(url: String?, active: Boolean): State<Color?> =
    rememberSampleRead(url, active, glowTones) { lightOf(paletteOf(it)).glow }

/** A picture's own dark ([ImageLight.deep]) and its [sample], read once from its small copy. */
@Immutable
internal class PictureRead(val deep: Color, val sample: ArtworkSample)

/**
 * [image] read once ([PictureRead]) from its sample's own small file, never the picture shown, which it would fetch and
 * decode again beside it, and kept, so a saga's panel opening again has it at once: null until read.
 */
@Composable
internal fun rememberPictureRead(image: ImageRef?): State<PictureRead?> =
    rememberSampleRead(image?.url(SAMPLE_PX), active = true, pictureReads) { PictureRead(lightOf(paletteOf(it)).deep, sampleOf(it)) }

/** Pictures read by URL (LRU): a long saga's posters and the backdrops opened among them. */
private val pictureReads = LruCache<String, PictureRead>(PICTURE_READS)

/**
 * What [read] finds in the small sample at [url] ([decodeUrl]), off the main thread, kept in [kept] by its URL: there at
 * once when kept, else read once [active]; null until then.
 */
@Composable
private fun <T : Any> rememberSampleRead(url: String?, active: Boolean, kept: LruCache<String, T>, read: (Bitmap) -> T): State<T?> {
    val context = LocalContext.current
    val found = remember(url) { mutableStateOf(url?.let { kept[it] }) }
    val reading by rememberUpdatedState(read)
    LaunchedEffect(url, active) {
        if (!active || url == null || found.value != null) return@LaunchedEffect
        val bitmap = decodeUrl(context, url) ?: return@LaunchedEffect
        val made = withContext(Dispatchers.Default) { reading(bitmap) }
        kept.put(url, made)
        found.value = made
    }
    return found
}

private const val PICTURE_READS = 40

internal fun paletteOf(bitmap: Bitmap): Palette = Palette.from(bitmap).maximumColorCount(PALETTE_COLOURS).generate()

private const val PALETTE_COLOURS = 16

private fun sampleOf(bitmap: Bitmap): ArtworkSample {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return ArtworkSample(bitmap.width, bitmap.height, FloatArray(pixels.size) { greyOf(Color(pixels[it])) })
}
