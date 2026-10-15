package app.houselights.ui.design.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.key
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.Image
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
import app.houselights.ui.design.BackdropDefaults
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HouseLightCue
import app.houselights.ui.design.LocalHouseLights
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.colorStops
import app.houselights.ui.util.screenWidthPx
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
 * the room. The veils are [HlScrim]'s; under the text column, the left veil keeps the floor the
 * picture needs, so the quietest text reads on the lightest image while a dark one keeps its veil.
 * When the house lights go down, the veils lift and the picture shows whole. Without a picture
 * ([image] null) it is the black room: nothing glows in its place.
 *
 * A picture, its veils and its light are drawn once, into a layer of their own, and each frame then
 * lays that one texture: on the player's small graphics chip, drawn layer by layer each frame, they
 * filled its whole budget. One picture gives way to the next by the alpha of those layers.
 */
@Composable
fun Backdrop(image: ImageRef?, style: BackdropStyle, modifier: Modifier = Modifier, recede: () -> Float = { 0f }) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // The text column of heroes and detail pages; the veil follows it, whatever the screen's width.
    val column = with(density) { (HlSpace.safeH + HlLayout.readableNarrow).toPx() }
    val fade = with(density) { BackdropDefaults.veilFade.toPx() }
    val screen = screenWidthPx()
    val url = image?.url(style.maxWidthPx?.coerceAtMost(screen) ?: screen)
    var bounds by remember { mutableStateOf(Size.Zero) }
    // The next picture is prepared before it shows; until then the last one stays.
    var shown by remember { mutableStateOf<PreparedBackdrop?>(null) }
    LaunchedEffect(url) {
        shown = if (image == null || url == null) null else {
            prepareBackdrop(context, image, url, screen, screen * BACKDROP_HEIGHT / BACKDROP_WIDTH)
        }
    }
    FadeBetween(shown, modifier.fillMaxSize().onSizeChanged { bounds = it.toSize() }) { prepared, alpha ->
        if (prepared != null) BackdropPicture(prepared, style, bounds, column, fade, alpha, recede)
    }
}

/** The shape a backdrop is asked for: the screen's. */
private const val BACKDROP_WIDTH = 16
private const val BACKDROP_HEIGHT = 9

/**
 * One picture of a [Backdrop], with its veils and its light, as one cached layer at [alpha]. All of
 * it is ready before it shows, so the layer is drawn once; only the house lights draw it again.
 */
@Composable
private fun BackdropPicture(
    prepared: PreparedBackdrop,
    style: BackdropStyle,
    bounds: Size,
    column: Float,
    fade: Float,
    alpha: () -> Float,
    recede: () -> Float,
) {
    val black = Hl.colors.background
    val lights = LocalHouseLights.current
    val floor = remember(prepared, bounds, style) {
        prepared.sample.floor(bounds, column, style.imageAlpha, style.leftScrim, HlScrim.down(style.fadeFrom))
    }
    // Stepping back, the picture takes the veil text needs over all of it, never less than VEIL.
    val receded = remember(prepared, bounds, style) {
        max(HlAlpha.VEIL, prepared.sample.floorAcross(bounds, style.imageAlpha, HlScrim.down(style.fadeFrom)))
    }
    val tone = prepared.tone
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                // Stepping back is black laid over the picture; on the black room that is the
                // layer's own alpha, which costs nothing, where a veil would draw the layer again.
                this.alpha = alpha() * (1f - recede() * receded * HouseLightCue.scrims(lights()))
            }
            .background(black),
    ) {
        Image(
            prepared.picture,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopEnd,
            alpha = style.imageAlpha,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().drawBehind {
                // The veils lift as the house lights go down; the light, stronger once the room is dark.
                val scrims = HouseLightCue.scrims(lights())
                drawRect(leftVeil(black, style.leftScrim, floor, column, fade), alpha = scrims)
                drawRect(Brush.verticalGradient(*HlScrim.down(style.fadeFrom).colorStops(black)), alpha = scrims)
                // Light from the picture, reflected on the room below it.
                if (tone.alpha > 0f) {
                    val spill = HlScrim.SPILL + HlScrim.SPILL_DARK * (1f - HouseLightCue.room(lights()))
                    drawRect(
                        Brush.radialGradient(
                            0f to tone.copy(alpha = tone.alpha * spill),
                            1f to Color.Transparent,
                            center = Offset(size.width * HlScrim.SPILL_X, size.height * HlScrim.SPILL_Y),
                            radius = size.width * HlScrim.SPILL_RADIUS,
                        )
                    )
                }
            }
        )
    }
}

/**
 * [Crossfade], with the alpha handed to the content instead of laid on a layer of its own: a
 * content that is a cached layer takes it as that layer's alpha, and fades for the cost of one
 * texture rather than two.
 */
@Composable
private fun <T> FadeBetween(target: T, modifier: Modifier, content: @Composable (T, alpha: () -> Float) -> Unit) {
    val transition = updateTransition(target, label = "backdrop")
    val shown = remember { mutableStateListOf(transition.currentState) }
    if (transition.currentState == transition.targetState && (shown.size != 1 || shown[0] != transition.targetState)) {
        shown.removeAll { it != transition.targetState }
    }
    if (transition.targetState !in shown) shown.add(transition.targetState)
    Box(modifier) {
        shown.forEach { state ->
            key(state) {
                val alpha = transition.animateFloat(
                    transitionSpec = { tween(HlMotion.SLOW, easing = HlMotion.standard) },
                    label = "backdropAlpha",
                ) { if (it == state) 1f else 0f }
                content(state) { alpha.value }
            }
        }
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
