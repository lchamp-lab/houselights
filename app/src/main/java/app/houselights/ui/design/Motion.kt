package app.houselights.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing

/**
 * Motion answers the user: focus, opening, playing. The only orchestrated moment is
 * the house lights going down before playback ([DIM]).
 */
object HlMotion {
    const val FAST = 150
    const val STANDARD = 250
    /** Backdrop and ambient light changes. */
    const val SLOW = 600
    /** Lights down before the player starts, and back up after. */
    const val DIM = 700
    /** Wait for focus to settle before loading a new backdrop. */
    const val SETTLE = 220L

    val standard = FastOutSlowInEasing
    val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}
