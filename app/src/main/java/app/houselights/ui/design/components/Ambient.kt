package app.houselights.ui.design.components

import android.content.Context
import android.graphics.Bitmap
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
 * The light of a picture, which lights the card that has focus: a calm, mid-bright tone picked from
 * a tiny copy of the artwork, animated when the artwork changes. A state, read while drawing: screens
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
 * A backdrop ready to be shown: its [picture] decoded at the size of the screen and how bright each
 * part of it is ([sample], for the veil laid over it). A picture that cannot be loaded leaves nothing.
 */
internal class PreparedBackdrop(val picture: ImageBitmap, val sample: ArtworkSample)

/**
 * Prepares the backdrop [image] at [url], [widthPx] by [heightPx], before it shows: the picture and
 * its tiny copy are read in parallel, off the main thread. Null when the picture cannot be loaded.
 */
internal suspend fun prepareBackdrop(context: Context, image: ImageRef, url: String, widthPx: Int, heightPx: Int): PreparedBackdrop? =
    coroutineScope {
        val picture = async {
            val request = ImageRequest.Builder(context).data(url).size(widthPx, heightPx).scale(Scale.FILL).build()
            (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()?.asImageBitmap()
        }
        val small = backdropSample(context, image)
        val sample = small?.let { withContext(Dispatchers.Default) { sampleOf(it) } }
        val decoded = picture.await()
        if (decoded == null || sample == null) null else PreparedBackdrop(decoded, sample)
    }

/** A tiny copy of [image], or null when it cannot be loaded. */
private suspend fun decodeSample(context: Context, image: ImageRef): Bitmap? {
    val request = ImageRequest.Builder(context)
        .data(image.url(SAMPLE_PX))
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

/**
 * The colour a picture is mostly made of, not its most vivid detail: a red title on a teal poster
 * glows teal. Each tone counts by the area it covers, a little more when it is saturated, so a
 * large grey sky does not always win; near-black and near-white do not count.
 */
private fun glowOf(palette: Palette): Color? {
    val swatch = palette.swatches
        .filter { it.hsl[2] in HlScrim.GLOW_LIGHTNESS_MIN..HlScrim.GLOW_LIGHTNESS_MAX }
        .maxByOrNull { it.population * (HlScrim.GLOW_AREA_WEIGHT + it.hsl[1]) }
        ?: palette.dominantSwatch ?: return null
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

private fun ambientOf(palette: Palette): Color? {
    val swatch = palette.vibrantSwatch ?: palette.darkVibrantSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch ?: return null
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(swatch.rgb, hsv)
    // Never garish: tame saturation, fixed brightness so every film glows the same amount.
    hsv[1] = hsv[1].coerceIn(HlScrim.AMBIENT_SATURATION_MIN, HlScrim.AMBIENT_SATURATION_MAX)
    hsv[2] = HlScrim.AMBIENT_VALUE
    return Color(android.graphics.Color.HSVToColor(hsv))
}
