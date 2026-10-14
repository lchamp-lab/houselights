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
    /** House lights: room, then screen, then black before the player starts; backwards after. */
    const val DIM = 1000
    /** Wait for focus to settle before loading a new backdrop. */
    const val SETTLE = 220L
    /** Placeholders wait this long: a slow load shows them, a quick one never flashes. */
    const val SKELETON_DELAY = 400L
    /** One loop of the welcome poster wall: slow enough to be felt more than seen. */
    const val WALL_DRIFT = 140_000
    /** Dialogs grow from this scale as they appear. */
    const val ENTER_SCALE = 0.96f

    val standard = FastOutSlowInEasing
    val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}
