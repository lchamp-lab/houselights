package app.houselights.ui.design.components

import androidx.compose.animation.animateColorAsState
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
        val request = ImageRequest.Builder(context)
            .data(image.url(SAMPLE_PX))
            .size(SAMPLE_PX, SAMPLE_PX * 9 / 16)
            .allowHardware(false) // Palette needs to read the pixels.
            .build()
        val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap() ?: return@LaunchedEffect
        target = withContext(Dispatchers.Default) { ambientOf(Palette.from(bitmap).maximumColorCount(16).generate()) } ?: fallback
    }
    val color by animateColorAsState(target, tween(HlMotion.SLOW, easing = HlMotion.standard), label = "ambient")
    return color
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
