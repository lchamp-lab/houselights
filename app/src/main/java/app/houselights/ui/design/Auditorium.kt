package app.houselights.ui.design

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

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
