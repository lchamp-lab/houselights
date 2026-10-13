package app.houselights.ui.design

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import app.houselights.core.MediaItem
import kotlin.math.abs

/**
 * The screen. Artwork is projected at the film's own aspect ratio, measured in the file, and
 * framed by black masking as in a cinema: flat films and television fill the display, scope
 * films get masking above and below. The masking stops at the ratios a projection booth uses.
 */
object HlScreen {
    /** The display itself, 16:9: no masking. */
    const val DISPLAY = 16f / 9f

    private val STOPS = floatArrayOf(1.85f, 1.90f, 2.00f, 2.20f, 2.39f, 2.76f)

    /** Ratio of the screen for [item]; the display's when the picture is not wider than it. */
    fun ratioOf(item: MediaItem?): Float {
        val raw = item?.media?.video?.aspectRatio ?: return DISPLAY
        if (raw < 1.82f) return DISPLAY
        return STOPS.minBy { abs(it - raw) }
    }
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
