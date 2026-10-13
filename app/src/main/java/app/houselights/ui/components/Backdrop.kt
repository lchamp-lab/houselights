package app.houselights.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import app.houselights.ui.theme.Palette

/**
 * Full-screen cinema-style backdrop: cross-faded image, darkened on the left (text)
 * and at the bottom (rows).
 */
@Composable
fun ImmersiveBackdrop(url: String?, modifier: Modifier = Modifier, bottomWeight: Float = 0.55f) {
    Box(modifier.fillMaxSize().background(Palette.Background)) {
        Crossfade(targetState = url, animationSpec = tween(700), label = "backdrop") { u ->
            if (u != null) {
                AsyncImage(
                    model = u,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopEnd,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val bg = Palette.Background
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to bg.copy(alpha = 0.97f),
                    0.30f to bg.copy(alpha = 0.82f),
                    0.62f to bg.copy(alpha = 0.15f),
                    1f to Color.Transparent,
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to bg.copy(alpha = 0.35f),
                    0.15f to Color.Transparent,
                    bottomWeight to bg.copy(alpha = 0.55f),
                    (bottomWeight + 0.2f).coerceAtMost(1f) to bg.copy(alpha = 0.95f),
                    1f to bg,
                )
            )
        )
    }
}
