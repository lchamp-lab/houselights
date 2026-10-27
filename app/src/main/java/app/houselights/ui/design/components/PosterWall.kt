package app.houselights.ui.design.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.colorStops
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest

private val POSTER_W = HlSize.welcomePoster
private val POSTER_H = POSTER_W * 1.5f
private val GAP = HlSpace.fact
private const val COLUMNS = 9
private const val ROWS = 4

/** The welcome screen's tilted wall of drifting posters, the app's only ambient motion; loops every [ROWS]. */
@Composable
fun PosterWall(urls: List<String>, modifier: Modifier = Modifier, opacity: Float = HlScrim.WALL) {
    if (urls.isEmpty()) return
    val drift by rememberInfiniteTransition(label = "wall").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(HlMotion.WALL_DRIFT, easing = LinearEasing)),
        label = "drift",
    )
    val cycle = (POSTER_H + GAP) * ROWS
    // One painter per poster, shared by its tiles, so each is decoded once, not per tile (81 tiles).
    val context = LocalContext.current
    val tile = with(LocalDensity.current) { IntSize(POSTER_W.roundToPx(), POSTER_H.roundToPx()) }
    val posters = urls.distinct().associateWith { url ->
        key(url) {
            val request = remember(url, tile) { ImageRequest.Builder(context).data(url).size(tile.width, tile.height).build() }
            rememberAsyncImagePainter(request, contentScale = ContentScale.Crop)
        }
    }
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
                    // Posters never overlap, so per-draw alpha equals layer alpha without an offscreen buffer.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        ) {
            repeat(COLUMNS) { col ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(GAP),
                    modifier = Modifier.offset(y = if (col % 2 == 1) -POSTER_H / 2 else 0.dp),
                ) {
                    // Rows r and r + ROWS show the same poster, so the drift loops without a seam.
                    repeat(ROWS * 2 + 1) { row ->
                        Image(
                            painter = posters.getValue(urls[(col * 5 + (row % ROWS) * 11) % urls.size]),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(POSTER_W, POSTER_H)
                                .clip(HlShapes.artwork)
                                .background(Hl.colors.content.copy(alpha = HlAlpha.FILL)),
                        )
                    }
                }
            }
        }
    }
}

/** The welcome wall's veil, black and darker where text sits; nothing glows behind the lockup. */
@Composable
fun PosterWallScrim(modifier: Modifier = Modifier) {
    val black = Hl.colors.background
    // Across, then down over it.
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(*HlScrim.WALL_ACROSS.colorStops(black)))
            .background(Brush.verticalGradient(*HlScrim.WALL_DOWN.colorStops(black))),
    )
}

