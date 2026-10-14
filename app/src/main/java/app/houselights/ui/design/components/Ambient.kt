package app.houselights.ui.design.components

import android.content.Context
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import app.houselights.core.ImageRef
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlMotion
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SAMPLE_PX = 96

/**
 * The light a picture spills into the room: a calm, mid-bright tone picked from a tiny
 * copy of the artwork, animated when the artwork changes.
 */
@Composable
fun rememberAmbientColor(image: ImageRef?): Color {
    val context = LocalContext.current
    val fallback = Hl.colors.ambientFallback
    var target by remember { mutableStateOf(fallback) }
    LaunchedEffect(image) {
        if (image == null) {
            target = fallback
            return@LaunchedEffect
        }
        target = ambientColorOf(context, image) ?: fallback
    }
    val color by animateColorAsState(target, tween(HlMotion.SLOW, easing = HlMotion.standard), label = "ambient")
    return color
}

/**
 * The ambient tone of [image], or null when it cannot be loaded. [sourcePx] picks the file to sample:
 * the one a card already shows comes from the cache instead of the network.
 */
private suspend fun ambientColorOf(context: Context, image: ImageRef, sourcePx: Int = SAMPLE_PX): Color? {
    val request = ImageRequest.Builder(context)
        .data(image.url(sourcePx))
        .size(SAMPLE_PX, SAMPLE_PX * 9 / 16)
        .allowHardware(false) // Palette needs to read the pixels.
        .build()
    val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap() ?: return null
    return withContext(Dispatchers.Default) { ambientOf(Palette.from(bitmap).maximumColorCount(16).generate()) }
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
        tones = coroutineScope { posters.map { async { ambientColorOf(context, it, posterPx) } }.awaitAll() }.filterNotNull()
    }
    val light by animateFloatAsState(if (lit) 1f else 0f, tween(HlMotion.SLOW, easing = HlMotion.standard), label = "collection")
    Crossfade(tones, modifier.fillMaxSize(), tween(HlMotion.SLOW, easing = HlMotion.standard), label = "tones") { colors ->
        Box(
            Modifier.fillMaxSize().drawBehind {
                if (light <= 0f || colors.isEmpty()) return@drawBehind
                val column = size.width / colors.size
                colors.forEachIndexed { i, tone ->
                    val center = Offset(column * (i + 0.5f), -size.height * 0.12f)
                    val radius = size.height * 0.95f
                    // Taller than wide: each glow stays over its column and blends with its neighbours.
                    scale(scaleX = (column * 1.25f) / radius, scaleY = 1f, pivot = center) {
                        drawCircle(
                            Brush.radialGradient(
                                0f to tone.copy(alpha = 0.36f * light),
                                0.55f to tone.copy(alpha = 0.11f * light),
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
    hsv[2] = 0.62f
    return Color(android.graphics.Color.HSVToColor(hsv))
}
