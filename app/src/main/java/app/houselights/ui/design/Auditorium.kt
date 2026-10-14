package app.houselights.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * How lit the room is, from the house lights on its walls: [RoomLight.UP] while browsing,
 * lower on a title's page, 0 in the dark. A function, read while drawing.
 */
val LocalRoomLight = staticCompositionLocalOf<() -> Float> { { RoomLight.UP } }

object RoomLight {
    const val UP = 1f

    /** On a title's page: the lights go half down as the film gets close. */
    const val DIMMED = 0.35f
}

/**
 * How far the house lights are down: 0 while browsing, 1 when the film starts. A function, so
 * that layers read it while drawing and the dimming never recomposes the screens.
 */
val LocalHouseLights = staticCompositionLocalOf<() -> Float> { { 0f } }

/**
 * Lights down, in order, as in a theatre: the room goes first (text and controls), while the
 * screen stays lit and its scrims lift so the picture shows whole; then black. Lights up runs
 * the same steps backwards.
 */
object HouseLightCue {
    /** Opacity of the room's content (text, buttons, rows). */
    fun room(down: Float) = 1f - smooth(0f, 0.3f, down)

    /** Strength of the scrims laid over the picture for legibility. */
    fun scrims(down: Float) = 1f - smooth(0.05f, 0.4f, down)

    /** Black over everything, last. */
    fun darkness(down: Float) = smooth(0.58f, 1f, down)

    private fun smooth(from: Float, to: Float, x: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/** Content of the room: it fades when the house lights go down. */
fun Modifier.houseLit(lights: () -> Float): Modifier = graphicsLayer { alpha = HouseLightCue.room(lights()) }

/**
 * Aisle light: a warm pool of light on the floor below what has focus, like the step lights
 * of a theatre. [scale] is how much the element grows when focused, so the pool stays under it.
 */
@Composable
fun Modifier.aisleLight(lit: Boolean, scale: Float = 1f): Modifier {
    val light by animateFloatAsState(if (lit) 1f else 0f, tween(HlMotion.STANDARD, easing = HlMotion.standard), label = "aisle")
    val tungsten = Hl.colors.tungsten
    return this.drawBehind {
        if (light <= 0f) return@drawBehind
        val center = Offset(size.width / 2f, size.height * (1f + scale) / 2f + 4.dp.toPx())
        val radius = size.width * 0.62f
        // A flat ellipse: light spread on the floor, seen from the seats.
        scale(scaleX = 1f, scaleY = 18.dp.toPx() / radius, pivot = center) {
            drawCircle(
                Brush.radialGradient(
                    0f to tungsten.copy(alpha = 0.5f * light),
                    0.45f to tungsten.copy(alpha = 0.16f * light),
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
