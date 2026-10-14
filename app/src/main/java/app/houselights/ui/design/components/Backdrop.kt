package app.houselights.ui.design.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.toSize
import app.houselights.core.ImageRef
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HouseLightCue
import app.houselights.ui.design.LocalHouseLights
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.colorStops
import app.houselights.ui.util.screenWidthPx
import coil.compose.AsyncImage
import kotlin.math.max

enum class BackdropStyle(
    /** Opacity of the picture itself. */
    val imageAlpha: Float,
    /** Where the bottom fade to black starts (share of the height). */
    val fadeFrom: Float,
    /** Strength of the left veil behind text, at the screen's edge. */
    val leftScrim: Float,
    /** Widest file worth asking for; null: as wide as the screen. */
    val maxWidthPx: Int? = null,
) {
    /** Home: the picture owns the top of the screen, rows sit on black below. */
    Hero(1f, 0.3f, 0.96f),
    /** Detail: the picture fills the screen behind the text. */
    Full(1f, 0.55f, 0.97f),
    /** Library, settings: a quiet reminder of the picture behind content; dimmed this much, a lighter file does. */
    Quiet(0.28f, 0.2f, 0.6f, maxWidthPx = 1280),
}

/**
 * Full-screen picture on pure black, veiled where text sits, plus the ambient light it spills into
 * the room ([LocalAmbient]). The veils are [HlScrim]'s; under the text column, the left veil keeps
 * the floor the picture needs, so the quietest text reads on the lightest image while a dark one
 * keeps its veil. When the house lights go down, the veils lift and the picture shows whole.
 * Without a picture ([image] null) it is the black room: nothing glows in its place.
 */
@Composable
fun Backdrop(image: ImageRef?, style: BackdropStyle, modifier: Modifier = Modifier) {
    val black = Hl.colors.background
    val ambient = LocalAmbient.current
    val lights = LocalHouseLights.current
    val screen = screenWidthPx()
    val url = image?.url(style.maxWidthPx?.coerceAtMost(screen) ?: screen)
    val density = LocalDensity.current
    // The text column of heroes and detail pages; the veil follows it, whatever the screen's width.
    val column = with(density) { (HlSpace.safeH + HlLayout.readableNarrow).toPx() }
    val fade = with(density) { HlLayout.veilFade.toPx() }
    var bounds by remember { mutableStateOf(Size.Zero) }
    val sample = rememberArtworkSample(image)
    val needed = remember(sample, bounds, style) {
        sample?.floor(bounds, column, style.imageAlpha, style.leftScrim, HlScrim.down(style.fadeFrom)) ?: 0f
    }
    val floor by animateFloatAsState(needed, tween(HlMotion.SLOW, easing = HlMotion.standard), label = "floor")
    Box(modifier.fillMaxSize().background(black).onSizeChanged { bounds = it.toSize() }) {
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
            Modifier.fillMaxSize().graphicsLayer { alpha = HouseLightCue.scrims(lights()) }.drawBehind {
                drawRect(leftVeil(black, style.leftScrim, floor, column, fade))
            }
        )
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = HouseLightCue.scrims(lights()) }.background(
                Brush.verticalGradient(*HlScrim.down(style.fadeFrom).colorStops(black))
            )
        )
        // Light from the picture, reflected on the room below it; stronger once the room is dark. Read
        // here, while drawing: the light's animation redraws this layer alone. No picture, no light
        // (a transparent tone): the room stays black.
        Box(
            Modifier.fillMaxSize().drawBehind {
                val tone = ambient()
                if (tone.alpha == 0f) return@drawBehind
                drawRect(
                    Brush.radialGradient(
                        0f to tone.copy(alpha = tone.alpha * (HlScrim.SPILL + HlScrim.SPILL_DARK * (1f - HouseLightCue.room(lights())))),
                        1f to Color.Transparent,
                        center = Offset(size.width * HlScrim.SPILL_X, size.height * HlScrim.SPILL_Y),
                        radius = size.width * HlScrim.SPILL_RADIUS,
                    )
                )
            }
        )
    }
}

/**
 * The left veil: [strength] at the screen's edge, easing along the [column] (px) as [HlScrim.LEFT]
 * says but never under [floor], then fading out over [fade] px.
 */
private fun leftVeil(black: Color, strength: Float, floor: Float, column: Float, fade: Float): Brush {
    val end = column + fade
    val stops = HlScrim.LEFT.map { it.at * column / end to black.copy(alpha = max(it.alpha * strength, floor)) } +
        (1f to Color.Transparent)
    return Brush.horizontalGradient(*stops.toTypedArray(), startX = 0f, endX = end)
}
