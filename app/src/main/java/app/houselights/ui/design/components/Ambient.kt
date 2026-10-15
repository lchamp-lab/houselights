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
import androidx.compose.ui.graphics.graphicsLayer
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
import app.houselights.ui.design.colorStops
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

/**
 * The light of a collection, for when no title is selected: the colours [posters] are made of (the
 * wall as it is shown), cast across the room in pools ([HlScrim.COLLECTION_POOLS]), so the room
 * takes the colours of what is on the wall without showing any one film. Drawn once; it fades in
 * and out with [lit], and from one wall's colours to the next.
 */
@Composable
fun CollectionLight(posters: List<ImageRef>, lit: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val posterPx = posterPx()
    var tones by remember { mutableStateOf<List<Color>>(emptyList()) }
    LaunchedEffect(posters, lit) {
        // Only while the light shows. The wall's posters are in the cache: they are read from there, in parallel.
        if (!lit) return@LaunchedEffect
        val walls = coroutineScope {
            posters.map { poster ->
                async {
                    decodeSample(context, poster, posterPx)?.let { bitmap ->
                        withContext(Dispatchers.Default) {
                            val palette = Palette.from(bitmap).maximumColorCount(PALETTE_COLOURS).generate()
                            palette.swatches.map { WallSwatch(it.rgb, it.population) }
                        }
                    }
                }
            }.awaitAll()
        }.filterNotNull()
        tones = wallTones(walls, HlScrim.COLLECTION_POOLS.size)
    }
    val light by animateFloatAsState(if (lit) 1f else 0f, tween(HlMotion.SLOW, easing = HlMotion.standard), label = "collection")
    // The light comes and goes by the alpha of its layer: its pools are drawn once.
    val fade = tween<Float>(HlMotion.SLOW, easing = HlMotion.standard)
    Crossfade(tones, modifier.fillMaxSize().graphicsLayer { alpha = light }, fade, label = "tones") { colors ->
        Box(
            Modifier.fillMaxSize().drawBehind {
                if (colors.isEmpty()) return@drawBehind
                HlScrim.COLLECTION_POOLS.forEachIndexed { i, pool ->
                    val center = Offset(size.width * pool.x, size.height * pool.y)
                    val radius = size.height * pool.radius
                    scale(scaleX = pool.stretch, scaleY = 1f, pivot = center) {
                        drawCircle(
                            Brush.radialGradient(
                                *HlScrim.FALLOFF.colorStops(colors[i % colors.size].copy(alpha = pool.core)),
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

/** How many colours a poster is read as. */
private const val PALETTE_COLOURS = 16

/** A colour of a poster ([rgb]) and how many of its points have it. */
internal class WallSwatch(val rgb: Int, val population: Int)

/** Hues pool together by tens of degrees. */
private const val HUE_BINS = 36
private const val FULL_TURN = 360f

/**
 * The colours a wall is made of, at most [count]: every poster counts alike, each of its colours by
 * the area it covers, a little more when saturated, as a card's glow picks its own; near-black,
 * near-white and greys give none. Close hues pool together, and each colour kept is a hue apart from
 * the heavier ones ([HlScrim.COLLECTION_HUE_APART]). The heaviest comes first; the second answers
 * it, the one that weighs most once its distance across the hue wheel counts too (a red wall answered
 * in teal rather than in a neighbouring ochre): a chord, not a muddle. They come out as the room's
 * light: tamed, at [HlScrim.AMBIENT_VALUE].
 */
internal fun wallTones(posters: List<List<WallSwatch>>, count: Int): List<Color> {
    val weights = FloatArray(HUE_BINS)
    val hues = FloatArray(HUE_BINS)
    val saturations = FloatArray(HUE_BINS)
    for (swatches in posters) {
        val total = swatches.sumOf { it.population }.toFloat()
        if (total <= 0f) continue
        for (swatch in swatches) {
            val tone = HueOf(swatch.rgb)
            if (tone.lightness !in HlScrim.GLOW_LIGHTNESS_MIN..HlScrim.GLOW_LIGHTNESS_MAX || tone.saturation < HlScrim.GLOW_GREY) continue
            val weight = swatch.population / total * (HlScrim.GLOW_AREA_WEIGHT + tone.hslSaturation)
            val bin = (tone.hue / FULL_TURN * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
            weights[bin] += weight
            hues[bin] += tone.hue * weight
            saturations[bin] += tone.saturation * weight
        }
    }
    val kept = mutableListOf<WallTone>()
    for (bin in weights.indices.sortedByDescending { weights[it] }) {
        if (weights[bin] <= 0f) break
        val tone = WallTone(hues[bin] / weights[bin], saturations[bin] / weights[bin], weights[bin])
        if (kept.all { hueDistance(it.hue, tone.hue) >= HlScrim.COLLECTION_HUE_APART }) kept += tone
    }
    val first = kept.firstOrNull() ?: return emptyList()
    val answer = kept.drop(1).maxByOrNull { it.weight * (1f + hueDistance(it.hue, first.hue) / (FULL_TURN / 2)) }
    val ordered = listOfNotNull(first, answer) + kept.filter { it !== first && it !== answer }
    return ordered.take(count).map {
        Color.hsv(it.hue, it.saturation.coerceIn(HlScrim.AMBIENT_SATURATION_MIN, HlScrim.AMBIENT_SATURATION_MAX), HlScrim.AMBIENT_VALUE)
    }
}

/** A colour of a wall: its hue (degrees), its saturation, and how much of the wall it is. */
private class WallTone(val hue: Float, val saturation: Float, val weight: Float)

/** How far apart two hues are on the wheel, in degrees: 180 at most. */
private fun hueDistance(a: Float, b: Float): Float {
    val distance = kotlin.math.abs(a - b)
    return minOf(distance, FULL_TURN - distance)
}

/** The hue wheel in six sectors, red, yellow, green, cyan, blue, magenta: green's and blue's start. */
private const val HUE_SECTORS = 6f
private const val GREEN_SECTOR = 2f
private const val BLUE_SECTOR = 4f

/** An RGB colour's hue (degrees), its HSV saturation, its HSL lightness and saturation. */
private class HueOf(rgb: Int) {
    val hue: Float
    val saturation: Float
    val lightness: Float
    val hslSaturation: Float

    init {
        val color = Color(rgb)
        val (r, g, b) = Triple(color.red, color.green, color.blue)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val chroma = max - min
        saturation = if (max > 0f) chroma / max else 0f
        lightness = (max + min) / 2
        hslSaturation = if (chroma == 0f) 0f else chroma / (1f - kotlin.math.abs(2 * lightness - 1))
        val sector = when {
            chroma == 0f -> 0f
            max == r -> ((g - b) / chroma).mod(HUE_SECTORS)
            max == g -> (b - r) / chroma + GREEN_SECTOR
            else -> (r - g) / chroma + BLUE_SECTOR
        }
        hue = sector * FULL_TURN / HUE_SECTORS
    }
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
