package app.houselights.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween

/** Durations (ms) and easings; only the house lights going down is orchestrated ([LaunchCue], [DIM]). */
object HlMotion {
    const val FAST = 150
    const val STANDARD = 250
    /** Backdrop and ambient light changes. */
    const val SLOW = 600
    /** House lights: room, then screen, then black before the player starts; backwards after. */
    const val DIM = 1000
    /**
     * Focus rest before the title under it changes the screen: longer than [STANDARD], so laying out its
     * text (25 ms on the Zidoo) lands on a still frame, not on a growing card.
     */
    const val SETTLE = 300L
    /** Placeholders wait this long: a slow load shows them, a quick one never flashes. */
    const val SKELETON_DELAY = 400L
    /** Longest wait for what leads: a cut's old picture for the new one, a hero's name for its picture. */
    const val HOLD = 400L
    /** One loop of the welcome poster wall: slow enough to be felt more than seen. */
    const val WALL_DRIFT = 140_000
    /** Dialogs grow from this scale as they appear. */
    const val ENTER_SCALE = 0.96f

    val standard = FastOutSlowInEasing
    val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Ease-out for lights going out, used by every [LaunchCue] step. */
    val lightsOut = LinearOutSlowInEasing
}

/**
 * Suspends while a motion of [durationMs] plays at the system animation scale, so what follows comes when
 * it ends, or at once with animations off. Call it from a composition's scope: its frame clock drives it.
 */
suspend fun awaitMotion(durationMs: Int) {
    animate(0f, 1f, animationSpec = tween(durationMs)) { _, _ -> }
}
