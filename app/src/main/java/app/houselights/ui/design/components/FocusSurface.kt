package app.houselights.ui.design.components

import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.tv.material3.LocalContentColor
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.Scrub
import kotlinx.coroutines.launch

/**
 * The surface every focusable control, row, panel and picture is drawn on, lit by its [look] from
 * [FocusStyle] (DESIGN.md, Focus). Fill, frame, growth and [glow] are read while drawing: focus moving
 * recomposes it only when a plate's ink turns, half way through its fill. Only a framed picture grows, in
 * a layer of its own, above its neighbours until its light is out. While an arrow is held ([Scrub]), focus
 * shows at once and nothing grows or glows.
 *
 * OK (centre, enter) presses on its way down and clicks on its way up; held, it long-clicks. Focus reaches
 * it when it cannot act ([enabled] false, or no [onClick]), and OK then does nothing. It is one element to
 * accessibility services, a [role] with its click. [content], clipped to [shape], is told when it is [lit]:
 * when its ink is focus's. Framed in a [row], it leaves its frame to the row ([RowFrame]).
 */
@Composable
internal fun HlFocusSurface(
    onClick: (() -> Unit)?,
    look: FocusLook,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    role: Role? = Role.Button,
    selected: Boolean? = null,
    glow: (() -> Color)? = null,
    interactionSource: MutableInteractionSource? = null,
    row: RowFrame? = null,
    content: @Composable BoxScope.(lit: Boolean) -> Unit,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val framed = look.gesture == Gesture.Frame
    val lights = rememberLights(focused, framed, rises = enabled && (look.lift != Lift.Still || glow != null))
    val lit = if (framed) focused else lights.halfLit
    val ring = FocusStyle.ring()
    val inRow = row?.takeIf { framed && enabled }?.let { rememberRowCard(it, look.lift, shape) { lights.risen.value } }
    Box(
        modifier
            .then(inRow ?: Modifier)
            .raisedWhileLit(framed, lights)
            .pressWithOk(interaction, onClick.takeIf { enabled }, onLongClick.takeIf { enabled })
            .focusable(interactionSource = interaction)
            .semantics(mergeDescendants = true) { describe(role, selected, enabled, onClick, onLongClick) }
            .framedLight(look, shape, ring.brush.takeIf { enabled && inRow == null }, glow, lights)
            .drawWithCache { drawPlate(look, shape, enabled, ring, lights) },
        propagateMinConstraints = true,
    ) {
        CompositionLocalProvider(LocalContentColor provides look.ink(enabled, lit)) {
            Box(Modifier.clip(shape)) { content(lit) }
        }
    }
}

/** A surface's light (0 at rest, 1 with focus) and how far it has risen: a picture's growth and glow. */
@Stable
private class Lights {
    val on = Animatable(0f)
    val risen = Animatable(0f)

    /** Past half its light, a plate's words take the ink of focus. */
    val halfLit by derivedStateOf { on.value >= HALF }

    /** Until its light is out, a framed surface is drawn above its neighbours. */
    val showing by derivedStateOf { on.value > 0f || risen.value > 0f }
}

/** The light follows focus; growth and glow follow it only when focus rests, never while an arrow is held. */
@Composable
private fun rememberLights(focused: Boolean, framed: Boolean, rises: Boolean): Lights {
    val lights = remember { Lights() }
    LaunchedEffect(focused) { lights.on.follow(focused, if (framed) FrameTiming else FillTiming) }
    val rising = rises && focused && !Scrub.active
    LaunchedEffect(rising) { lights.risen.follow(rising, FrameTiming) }
    return lights
}

private fun Modifier.raisedWhileLit(framed: Boolean, lights: Lights): Modifier =
    if (framed) zIndex(if (lights.showing) 1f else 0f) else this

/** A framed surface's glow, [frame] and growth, outside its plate; nothing for a plate that fills. */
private fun Modifier.framedLight(look: FocusLook, shape: Shape, frame: Brush?, glow: (() -> Color)?, lights: Lights): Modifier {
    if (look.gesture != Gesture.Frame) return this
    val lit = drawWithCache { drawLight(shape, look.lift, frame, glow, lights) }
    return if (look.lift == Lift.Still) lit else lit.grown(look.lift, lights.risen)
}

/** One element to accessibility services: its role, whether it is chosen, its clicks, whether it can act. */
private fun SemanticsPropertyReceiver.describe(
    role: Role?,
    selected: Boolean?,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
    role?.let { this.role = it }
    selected?.let { this.selected = it }
    if (onClick != null) this.onClick(label = null) { enabled.also { if (it) onClick() } }
    if (onLongClick != null) this.onLongClick(label = null) { enabled.also { if (it) onLongClick() } }
    if (!enabled) disabled()
}

/** The ink of what a surface holds. */
private fun FocusLook.ink(enabled: Boolean, lit: Boolean): Color = when {
    !enabled -> disabled.ink
    lit -> focus.ink
    else -> rest.ink
}

/** A picture grown by [lift] as far as it has [risen], in its own layer, read while drawing. */
private fun Modifier.grown(lift: Lift, risen: Animatable<Float, AnimationVector1D>): Modifier = graphicsLayer {
    val scale = 1f + (lift.scale - 1f) * risen.value
    scaleX = scale
    scaleY = scale
}

/**
 * The plate under the content, then the content, its edge and, when it cannot act, the [ring] of focus.
 * The white of focus lies over the plate at the light's strength, as a light would, never blending through
 * the colours between.
 */
private fun CacheDrawScope.drawPlate(look: FocusLook, shape: Shape, enabled: Boolean, ring: BorderStroke, lights: Lights): DrawResult {
    val area = shape.createOutline(size, layoutDirection, this)
    val edge = look.edge?.let { inside(shape, it) }
    val disabledRing = if (enabled) null else inside(shape, ring)
    val fills = enabled && look.gesture == Gesture.Fill
    return onDrawWithContent {
        val on = lights.on.value
        val rest = if (enabled) look.rest.fill() else look.disabled.fill()
        // A clear plate draws nothing: a transparent rectangle still costs a pass.
        if (rest.alpha > 0f) drawOutline(area, rest)
        if (fills && on > 0f) drawOutline(area, look.focus.fill(), alpha = on)
        drawContent()
        edge?.draw(this, 1f)
        if (on > 0f) disabledRing?.draw(this, on)
    }
}

/**
 * A framed picture's glow under it and its [frame] over it, [HlFocus.frameGap] off its grown edge, the
 * frame's corners parallel to the picture's ([shape]'s first corner). Outside the picture's layer, so the
 * frame keeps its width as the picture grows; without [frame] (it cannot act), its ring shows focus.
 */
private fun CacheDrawScope.drawLight(shape: Shape, lift: Lift, frame: Brush?, glow: (() -> Color)?, lights: Lights): DrawResult {
    val corner = (shape as? RoundedCornerShape)?.topStart?.toPx(size, this) ?: 0f
    val width = HlFocus.frame.toPx()
    val off = HlFocus.frameGap.toPx() + width / 2
    val stroke = Stroke(width)
    val reach = HlFocus.glow.toPx()
    return onDrawWithContent {
        val risen = lights.risen.value
        val grown = 1f + (lift.scale - 1f) * risen
        val tone = glow?.invoke()
        if (tone != null && risen > 0f) drawGlow(tone.copy(alpha = tone.alpha * risen), grown, reach, corner)
        drawContent()
        val on = lights.on.value
        if (frame != null && on > 0f) {
            val x = size.width * (grown - 1f) / 2 + off
            val y = size.height * (grown - 1f) / 2 + off
            drawRoundRect(
                frame,
                topLeft = Offset(-x, -y),
                size = Size(size.width + 2 * x, size.height + 2 * y),
                cornerRadius = CornerRadius(corner * grown + off),
                alpha = on,
                style = stroke,
            )
        }
    }
}

/**
 * The glow [reach] around a picture at scale [grown]: each ring is drawn once at the combined alpha of
 * stacked layers, so each layer costs a ring's fill, not a picture's, on the player's GPU.
 */
private fun DrawScope.drawGlow(tone: Color, grown: Float, reach: Float, corner: Float) {
    if (tone.alpha == 0f) return
    val grow = Offset(size.width * (grown - 1f) / 2f, size.height * (grown - 1f) / 2f)
    val steps = HlScrim.GLOW_STEPS
    val band = reach / steps
    val ring = Stroke(width = band)
    // Transmittance of the stacked layers, accumulated from the outermost ring inward.
    var through = 1f
    for (k in steps downTo 1) {
        through *= 1f - tone.alpha * HlScrim.GLOW_LAYER * (1f - k / steps.toFloat())
        // The band's middle line: a stroke is drawn half inside, half outside it.
        val middle = band * k - band / 2
        drawRoundRect(
            color = tone.copy(alpha = 1f - through),
            topLeft = Offset(-grow.x - middle, -grow.y - middle),
            size = Size(size.width + 2 * (grow.x + middle), size.height + 2 * (grow.y + middle)),
            cornerRadius = CornerRadius(corner * grown + middle),
            style = ring,
        )
    }
}

/** A stroke along an outline, inside the surface's edge as a border is. */
private class Inside(private val outline: Outline, private val inset: Float, private val brush: Brush, private val stroke: Stroke) {
    fun draw(scope: DrawScope, alpha: Float) = with(scope) {
        translate(inset, inset) { drawOutline(outline, brush, alpha = alpha, style = stroke) }
    }
}

private fun CacheDrawScope.inside(shape: Shape, border: BorderStroke): Inside {
    val width = border.width.toPx()
    val outline = shape.createOutline(Size(size.width - width, size.height - width), layoutDirection, this)
    return Inside(outline, width / 2, border.brush, Stroke(width))
}

/** How long a light takes to come on with focus and to go out after it. */
private class Timing(val on: Int, val off: Int)

private val FrameTiming = Timing(HlMotion.FOCUS_IN, HlMotion.FOCUS_OUT)
private val FillTiming = Timing(HlMotion.PLATE_IN, HlMotion.PLATE_OUT)

/** Runs to 1 when [on], else to 0: in the focus pull, out in the fall back; at once while an arrow is held. */
private suspend fun Animatable<Float, AnimationVector1D>.follow(on: Boolean, timing: Timing) {
    val target = if (on) 1f else 0f
    when {
        Scrub.active -> snapTo(target)
        on -> animateTo(target, tween(timing.on, easing = HlMotion.focusPull))
        else -> animateTo(target, tween(timing.off, easing = HlMotion.fallBack))
    }
}

/**
 * OK on the remote (centre, enter, the keypad's enter): pressed on its way down, a click on its way up;
 * held until it repeats, a long click instead, when there is one. Without [onClick] the key goes on to
 * what holds the surface. Focus leaving lets go of a press.
 */
@Composable
private fun Modifier.pressWithOk(
    interaction: MutableInteractionSource,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
): Modifier {
    val scope = rememberCoroutineScope()
    val press = remember { mutableStateOf<PressInteraction.Press?>(null) }
    val longClicked = remember { mutableStateOf(false) }
    fun release() {
        press.value?.let { down -> scope.launch { interaction.emit(PressInteraction.Release(down)) } }
        press.value = null
    }
    return onFocusChanged {
        if (!it.isFocused) {
            release()
            longClicked.value = false
        }
    }.onKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (onClick == null || native.keyCode !in OK_KEYS) return@onKeyEvent false
        when (native.action) {
            KeyEvent.ACTION_DOWN -> when (native.repeatCount) {
                0 -> {
                    release()
                    longClicked.value = false
                    PressInteraction.Press(Offset.Zero).let { down ->
                        press.value = down
                        scope.launch { interaction.emit(down) }
                    }
                }
                1 -> if (onLongClick != null) {
                    longClicked.value = true
                    release()
                    onLongClick()
                }
            }
            KeyEvent.ACTION_UP -> {
                release()
                if (longClicked.value) longClicked.value = false else onClick()
            }
        }
        true
    }
}

private const val HALF = 0.5f

private val OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
