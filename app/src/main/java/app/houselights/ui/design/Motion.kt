package app.houselights.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween

/**
 * Durations (ms) and the system's own curves (DESIGN.md, Motion); only the house lights going down is
 * orchestrated ([LaunchCue], [DIM]). No other file draws a curve (`MotionGuardTest`).
 */
object HlMotion {
    const val FAST = 150
    const val STANDARD = 250
    /** The launch cue, a picture going out on black, a page's backdrop stepping back. */
    const val SLOW = 600
    /** House lights: room, then screen, then black before the player starts; backwards after. */
    const val DIM = 1000
    /** Focus arriving on what it frames: growth, frame, light, the siblings' recede ([focusPull]). */
    const val FOCUS_IN = 220
    /** Focus leaving it, the same undone ([fallBack]). */
    const val FOCUS_OUT = 260
    /** A plate turning white with focus, and back. */
    const val PLATE_IN = 120
    const val PLATE_OUT = 180
    /** One backdrop picture crossfading to the next, with its veils and the plates' dark, once settled ([SETTLE]); linear. */
    const val TINT = 480
    /** One turn of a wait's light around its frame, looped: the only motion that does not stop. */
    const val WAIT_TURN = 1400
    /**
     * Focus rest before the title under it changes the screen: longer than [FOCUS_IN], so laying out its
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

    /** "Mise au point": off at once, then long to settle, as a lens finding focus. What arrives. */
    val focusPull = CubicBezierEasing(0.20f, 0.75f, 0.25f, 1f)

    /** "Retombée": even at both ends. What leaves, and what crosses from one state to the next. */
    val fallBack = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

    /** Ease-out for lights going out, used by every [LaunchCue] step. */
    val lightsOut = CubicBezierEasing(0f, 0f, 0.2f, 1f)
}

/**
 * Suspends while a motion of [durationMs] plays at the system animation scale, so what follows comes when
 * it ends, or at once with animations off. Call it from a composition's scope: its frame clock drives it.
 */
suspend fun awaitMotion(durationMs: Int) {
    animate(0f, 1f, animationSpec = tween(durationMs, easing = LinearEasing)) { _, _ -> }
}
