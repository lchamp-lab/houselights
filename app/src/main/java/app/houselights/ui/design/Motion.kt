package app.houselights.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween

/**
 * Motion answers the user: focus, opening, playing. The only orchestrated moment is the house
 * lights going down: on the mark at the launch ([LaunchCue]), on the room before playback ([DIM]).
 */
object HlMotion {
    const val FAST = 150
    const val STANDARD = 250
    /** Backdrop and ambient light changes. */
    const val SLOW = 600
    /** House lights: room, then screen, then black before the player starts; backwards after. */
    const val DIM = 1000
    /**
     * Wait for focus to settle before the title under it changes the screen (its backdrop, its
     * hero): longer than the focus motion ([STANDARD]), so laying out the new title's words (25 ms
     * on the player) lands on a still frame, never in the middle of a card growing.
     */
    const val SETTLE = 300L
    /** Placeholders wait this long: a slow load shows them, a quick one never flashes. */
    const val SKELETON_DELAY = 400L
    /**
     * What follows waits this long at most for what leads it: across a cut, the picture of the
     * screen left for the arriving screen's own; a hero's name for its picture.
     */
    const val HOLD = 400L
    /** One loop of the welcome poster wall: slow enough to be felt more than seen. */
    const val WALL_DRIFT = 140_000
    /** Dialogs grow from this scale as they appear. */
    const val ENTER_SCALE = 0.96f

    val standard = FastOutSlowInEasing
    val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** A light going out (ease-out): at once, then settling into the dark. The steps of [LaunchCue]. */
    val lightsOut = LinearOutSlowInEasing
}

/**
 * Waits while a motion of [durationMs] plays, at the pace the system sets for animations: what
 * follows a motion (a screen opening once the pill has slid) comes when it ends, and at once when
 * animations are off. Never a fixed delay, which would hold a still screen. Call it from a
 * composition's scope: its frame clock drives the motion.
 */
suspend fun awaitMotion(durationMs: Int) {
    animate(0f, 1f, animationSpec = tween(durationMs)) { _, _ -> }
}
