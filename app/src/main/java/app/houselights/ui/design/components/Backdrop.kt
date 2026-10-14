package app.houselights.ui.design.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import app.houselights.core.ImageRef
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HouseLightCue
import app.houselights.ui.design.LocalHouseLights
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.util.screenWidthPx
import coil.compose.AsyncImage

enum class BackdropStyle(
    /** Opacity of the picture itself. */
    val imageAlpha: Float,
    /** Where the bottom fade to black starts (fraction of the height). */
    val fadeFrom: Float,
    /** Strength of the left scrim behind text. */
    val leftScrim: Float,
) {
    /** Home: the picture owns the top of the screen, rows sit on black below. */
    Hero(1f, 0.3f, 0.96f),
    /** Detail: the picture fills the screen behind the text. */
    Full(1f, 0.55f, 0.97f),
    /** Library, settings: a quiet reminder of the picture behind content. */
    Quiet(0.28f, 0.2f, 0.6f),
}

/**
 * Full-screen picture on pure black, faded where text sits, plus the ambient light it
 * spills into the room ([LocalAmbient]). When the house lights go down, the scrims lift and
 * the picture shows whole.
 */
@Composable
fun Backdrop(image: ImageRef?, style: BackdropStyle, modifier: Modifier = Modifier) {
    val black = Hl.colors.background
    val ambient = LocalAmbient.current
    val lights = LocalHouseLights.current
    val url = image?.url(screenWidthPx())
    Box(modifier.fillMaxSize().background(black)) {
        Crossfade(targetState = url, animationSpec = tween(HlMotion.SLOW, easing = HlMotion.standard), label = "backdrop") { u ->
            if (u != null) {
                AsyncImage(
                    model = u,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopEnd,
                    alpha = style.imageAlpha,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = HouseLightCue.scrims(lights()) }.background(
                Brush.horizontalGradient(
                    0f to black.copy(alpha = style.leftScrim),
                    0.34f to black.copy(alpha = style.leftScrim * 0.9f),
                    0.58f to black.copy(alpha = style.leftScrim * 0.5f),
                    0.85f to Color.Transparent,
                )
            )
        )
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = HouseLightCue.scrims(lights()) }.background(
                Brush.verticalGradient(
                    0f to black.copy(alpha = 0.45f),
                    0.14f to Color.Transparent,
                    style.fadeFrom to Color.Transparent,
                    (style.fadeFrom + 0.3f).coerceAtMost(0.98f) to black.copy(alpha = 0.92f),
                    1f to black,
                )
            )
        )
        // Light from the picture, reflected on the room below it; stronger once the room is dark.
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.radialGradient(
                        0f to ambient.copy(alpha = 0.22f + 0.14f * (1f - HouseLightCue.room(lights()))),
                        1f to Color.Transparent,
                        center = Offset(size.width * 0.28f, size.height * 1.05f),
                        radius = size.width * 0.62f,
                    )
                )
            }
        )
    }
}
