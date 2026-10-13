package app.houselights.ui.design.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlShapes
import coil.compose.AsyncImage

private val POSTER_W = 148.dp
private val POSTER_H = POSTER_W * 1.5f
private val GAP = 18.dp
private const val COLUMNS = 9
private const val ROWS = 4

/**
 * A tilted wall of posters drifting upwards in the dark, like a cinema lobby seen from the
 * back of the room. The only ambient motion in the app; it loops seamlessly every
 * [ROWS] posters.
 */
@Composable
fun PosterWall(urls: List<String>, modifier: Modifier = Modifier, opacity: Float = 0.3f) {
    if (urls.isEmpty()) return
    val drift by rememberInfiniteTransition(label = "wall").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(140_000, easing = LinearEasing)),
        label = "drift",
    )
    val cycle = (POSTER_H + GAP) * ROWS
    Box(modifier.fillMaxSize().clipToBounds()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(GAP),
            modifier = Modifier
                .align(Alignment.Center)
                .requiredSize(width = (POSTER_W + GAP) * COLUMNS, height = cycle * 2 + POSTER_H)
                .graphicsLayer {
                    rotationZ = -9f
                    translationY = -drift * cycle.toPx()
                    alpha = opacity
                },
        ) {
            repeat(COLUMNS) { col ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(GAP),
                    modifier = Modifier.offset(y = if (col % 2 == 1) -POSTER_H / 2 else 0.dp),
                ) {
                    // Rows r and r + ROWS show the same poster, so the drift loops without a seam.
                    repeat(ROWS * 2 + 1) { row ->
                        AsyncImage(
                            model = urls[(col * 5 + (row % ROWS) * 11) % urls.size],
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(POSTER_W, POSTER_H).clip(HlShapes.artwork).background(Hl.colors.surface),
                        )
                    }
                }
            }
        }
    }
}
