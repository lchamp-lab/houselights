package app.houselights.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Durations (ms) and the system's own curves (DESIGN.md, Motion). Two moments are orchestrated: the house
 * lights ([LaunchCue], [DIM]) and the welcome wall's drift ([WALL_DRIFT]). No other file draws a curve or a spring
 * (`MotionGuardTest`).
 */
object HlMotion {
    /** The launch cue, a picture going out on black, a page's backdrop stepping back. */
    const val SLOW = 600
    /** House lights: room, then screen, then black before the player starts; backwards after. */
    const val DIM = 1000
    /** Focus arriving on what it frames: frame, glow, the siblings' recede ([focusPull]). */
    const val FOCUS_IN = 220
    /** Focus leaving it, the same undone ([fallBack]). */
    const val FOCUS_OUT = 260
    /** A plate turning white with focus, and back; a hero's words, a menu's opacity, a scrolled end's fade. */
    const val PLATE_IN = 120
    const val PLATE_OUT = 180
    /** One backdrop picture crossfading to the next, with its veils and the plates' dark, once settled ([SETTLE]); linear. */
    const val TINT = 480
    /**
     * Focus rest before the title under it changes the screen: longer than [FOCUS_IN], so laying out its
     * text (25 ms on the Zidoo) comes once the frame is on and the glow at full strength; the first time, the
     * glow's tone may still wait for its sample to decode.
     */
    const val SETTLE = 300L
    /** Placeholders wait this long: a slow load shows them, a quick one never flashes. */
    const val SKELETON_DELAY = 400L
    /** Longest wait for what leads: a cut's old picture for the new one, a hero's name for its picture. */
    const val HOLD = 400L
    /** One loop of the welcome poster wall: slow enough to be felt more than seen. */
    const val WALL_DRIFT = 140_000

    /**
     * Every scroll: lists, rows, walls and texts, by focus or by a held key. A spring without bounce, so a held key
     * keeps its speed; it comes within 1 % of its target in about 270 ms.
     */
    val scroll: SpringSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = SCROLL_STIFFNESS)

    private const val SCROLL_STIFFNESS = 600f

    /** "Mise au point": off at once, then long to settle, as a lens finding focus. What arrives. */
    val focusPull = CubicBezierEasing(0.20f, 0.75f, 0.25f, 1f)

    /** "Retombée": even at both ends. What leaves, and what crosses from one state to the next. */
    val fallBack = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

    /** Ease-out for lights going out: each [LaunchCue] step, a picture going out on black. */
    val lightsOut = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    /**
     * Coming [on] over [inMs] in the focus pull, else going over [outMs] in the fall back: focus arriving and leaving
     * ([FOCUS_IN], [FOCUS_OUT]), a plate's ([PLATE_IN], [PLATE_OUT]).
     */
    fun <T> inOut(on: Boolean, inMs: Int = FOCUS_IN, outMs: Int = FOCUS_OUT): TweenSpec<T> =
        if (on) tween(inMs, easing = focusPull) else tween(outMs, easing = fallBack)
}
