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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.LocalContentColor
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.Scrub
import kotlinx.coroutines.launch

/**
 * The surface every focusable control, row, panel and picture is drawn on, lit by its [look] from
 * [FocusStyle] (DESIGN.md, Focus). Fill, frame and [glow] are read while drawing, a plate's light in its
 * content's clip layer: focus moving recomposes it as focus turns, and a plate again half way through its fill,
 * when its ink turns. A framed surface that glows or draws its own frame is drawn above its neighbours until its
 * light is out, so that light lies over them, the focused one on top (its card's column in its stead,
 * [CardColumn]). While an arrow is held ([Scrub]), focus shows at once and nothing glows.
 *
 * OK (centre, enter) presses on its way down and clicks on its way up if the press was taken here; held, it
 * long-clicks instead. Focus reaches it when it cannot act ([enabled] false, or no [onClick]), and OK then does
 * nothing. It is one element to accessibility services, a [role] with its clicks, the long one named by
 * [onLongClickLabel]. [content], clipped to [shape], is told when it is [lit]: when its ink is focus's. Framed in
 * a [row], it leaves its frame to the row ([RowFrame]). What its content covers from the top ([covered], in px,
 * read while drawing) takes no plate under it. A picture made of several ([glowTones], left to right) glows in
 * their tones across it rather than in [glow]'s one.
 */
@Composable
internal fun HlFocusSurface(
    onClick: (() -> Unit)?,
    look: FocusLook,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    role: Role? = Role.Button,
    selected: Boolean? = null,
    glow: (() -> Color)? = null,
    interactionSource: MutableInteractionSource? = null,
    row: RowFrame? = null,
    covered: (() -> Float)? = null,
    glowTones: (() -> List<Color>)? = null,
    content: @Composable BoxScope.(lit: Boolean) -> Unit,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val held = interaction.collectIsFocusedAsState()
    // Under an open sheet, the screen shows no focus: what opened it keeps it, unlit.
    val inSheet = LocalInSheet.current
    val focus = remember(held, inSheet) { derivedStateOf { held.value && (inSheet || OpenSheets.count == 0) } }
    val focused by focus
    val framed = look.gesture == Gesture.Frame
    // One tone, or several across the picture: what its glow is drawn with.
    val tones = remember(glow, glowTones) { tonesOf(glow, glowTones) }
    val glows = enabled && tones != null
    val lights = rememberLights(focused, framed, glows)
    val lit = if (framed) focused else lights.halfLit
    val ring = FocusStyle.ring()
    val inRow = row?.takeIf { framed && enabled }?.let { rememberRowCard(it, shape) }
    val frame = ring.brush.takeIf { enabled && inRow == null }
    val light = remember(look, shape, frame, tones, lights) { framedLight(look, shape, frame, tones, lights) }
    val plate = remember(look, enabled, lights) { ColorProducer { look.plateAt(enabled, lights.on.value) } }
    Box(
        modifier
            .then(inRow ?: Modifier)
            .then(rememberRaise(framed && (glows || frame != null), focus, lights))
            .pressWithOk(interaction, onClick.takeIf { enabled }, onLongClick.takeIf { enabled })
            .menuForLongClick(onLongClick.takeIf { enabled })
            .focusable(interactionSource = interaction)
            .semantics(mergeDescendants = true) { describe(role, selected, enabled, onClick, onLongClick, onLongClickLabel) }
            .then(light),
        propagateMinConstraints = true,
    ) {
        CompositionLocalProvider(LocalContentColor provides look.ink(enabled, lit), LocalRaise provides null, LocalPlate provides plate) {
            // In the clip's layer, the plate's light redraws that layer alone, never the screen's.
            val disabledRing = ring.takeUnless { enabled }
            Box(Modifier.clip(shape).drawWithCache { drawPlate(look, shape, disabledRing, lights, covered) }) { content(lit) }
        }
    }
}

/** A surface's light (0 at rest, 1 with focus) and a picture's glow. */
@Stable
private class Lights {
    val on = Animatable(0f)
    val glow = Animatable(0f)

    /** Past half its light, a plate's words take the ink of focus. */
    val halfLit by derivedStateOf { on.value >= HALF }

    /** Until its light is out, a framed surface is drawn above its neighbours. */
    val showing by derivedStateOf { on.value > 0f || glow.value > 0f }
}

/**
 * The light follows focus, at once while an arrow is held; the glow comes only when focus rests, and always
 * falls back, so the picture an arrow leaves goes out as it does after a press.
 */
@Composable
private fun rememberLights(focused: Boolean, framed: Boolean, glows: Boolean): Lights {
    val lights = remember { Lights() }
    LaunchedEffect(focused) { lights.on.follow(focused, if (framed) FrameTiming else FillTiming, jumps = Scrub.active) }
    val glowing = glows && focused && !Scrub.active
    LaunchedEffect(glowing) { lights.glow.follow(glowing, FrameTiming, jumps = false) }
    return lights
}

/**
 * What a surface draws past its edge, when [raised], lies over its neighbours: on top with focus, then while its
 * light goes out. Read while placing; the card it is the picture of ([LocalRaise]) is raised in its stead.
 */
@Composable
private fun rememberRaise(raised: Boolean, focus: State<Boolean>, lights: Lights): Modifier {
    if (!raised) return Modifier
    val level = remember(focus, lights) { { if (focus.value) ON_TOP else if (lights.showing) ABOVE else 0f } }
    val card = LocalRaise.current ?: return remember(level) { Modifier.raisedBy(level) }
    DisposableEffect(card, level) {
        card.level = level
        onDispose { card.level = AtRest }
    }
    return Modifier
}

/**
 * The root of a card whose framed surface is not its root ([CardColumn]): only siblings are ordered, so it is
 * drawn above its neighbours as its surface would be.
 */
@Stable
internal class Raise {
    /** How far above its neighbours, read while placing. */
    var level by mutableStateOf(AtRest)
}

/** The plate a surface's content lies on, read while drawing: what a picture on it fades into. */
internal val LocalPlate = staticCompositionLocalOf<ColorProducer?> { null }

/** The plate as drawn at [on] (0 at rest, 1 with focus): focus's white over the rest for a plate that fills. */
private fun FocusLook.plateAt(enabled: Boolean, on: Float): Color = when {
    !enabled -> disabled.fill()
    gesture == Gesture.Fill && on > 0f -> focus.fill().copy(alpha = on).compositeOver(rest.fill())
    else -> rest.fill()
}

/** The card a framed surface is the picture of; null where the surface is its own root. */
internal val LocalRaise = staticCompositionLocalOf<Raise?> { null }

/** Drawn above its siblings by [level], read while placing: a change places it again and recomposes nothing. */
internal fun Modifier.raisedBy(level: () -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { placeable.place(0, 0, zIndex = level()) }
}

private val AtRest: () -> Float = { 0f }
private const val ABOVE = 1f
private const val ON_TOP = 2f

/** A glow's tones: several across a picture ([across]), else its [one] tone; none without either. */
private fun tonesOf(one: (() -> Color)?, across: (() -> List<Color>)?): (() -> List<Color>)? =
    across ?: one?.let { tone -> { listOf(tone()) } }

/** A framed surface's glow in its [tones] and its [frame], outside its plate; nothing for a plate that fills. */
private fun framedLight(look: FocusLook, shape: Shape, frame: Brush?, tones: (() -> List<Color>)?, lights: Lights): Modifier =
    if (look.gesture == Gesture.Frame) Modifier.drawWithCache { drawLight(shape, frame, tones, lights) } else Modifier

/** One element to accessibility services: its role, whether it is chosen, its clicks, whether it can act. */
@Suppress("LongParameterList") // One per property it states.
private fun SemanticsPropertyReceiver.describe(
    role: Role?,
    selected: Boolean?,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    longClickLabel: String?,
) {
    role?.let { this.role = it }
    selected?.let { this.selected = it }
    if (onClick != null) this.onClick(label = null) { enabled.also { if (it) onClick() } }
    if (onLongClick != null) this.onLongClick(label = longClickLabel) { enabled.also { if (it) onLongClick() } }
    if (!enabled) disabled()
}

/** The ink of what a surface holds. */
private fun FocusLook.ink(enabled: Boolean, lit: Boolean): Color = when {
    !enabled -> disabled.ink
    lit -> focus.ink
    else -> rest.ink
}

/**
 * The plate under the content, below what the content [covered], then the content, its edge and, given when it
 * cannot act, the [disabledRing] of focus. The white of focus lies over the plate at the light's strength, as a
 * light would, never blending through the colours between.
 */
private fun CacheDrawScope.drawPlate(
    look: FocusLook,
    shape: Shape,
    disabledRing: BorderStroke?,
    lights: Lights,
    covered: (() -> Float)?,
): DrawResult {
    val enabled = disabledRing == null
    val area = shape.createOutline(size, layoutDirection, this)
    val edge = look.edge?.let { inside(shape, it) }
    val ring = disabledRing?.let { inside(shape, it) }
    val fills = enabled && look.gesture == Gesture.Fill
    return onDrawWithContent {
        val on = lights.on.value
        val rest = if (enabled) look.rest.fill() else look.disabled.fill()
        val white = if (fills) on else 0f
        val top = covered?.invoke() ?: 0f
        if (top > 0f) clipRect(top = top) { fillPlate(area, rest, look, white) } else fillPlate(area, rest, look, white)
        drawContent()
        edge?.draw(this, 1f)
        if (on > 0f) ring?.draw(this, on)
    }
}

/** The plate at rest, then focus's white over it at [white]; a clear plate draws nothing, as a transparent rectangle costs a pass. */
private fun DrawScope.fillPlate(area: Outline, rest: Color, look: FocusLook, white: Float) {
    if (rest.alpha > 0f) drawOutline(area, rest)
    if (white > 0f) drawOutline(area, look.focus.fill(), alpha = white)
}

/**
 * A framed picture's glow under it and its [frame] over it, [HlFocus.frameGap] off its edge, the frame's corners
 * parallel to the picture's ([shape]'s first corner). Without [frame], its row draws it ([RowFrame]), or,
 * disabled, its ring shows focus.
 */
private fun CacheDrawScope.drawLight(shape: Shape, frame: Brush?, tones: (() -> List<Color>)?, lights: Lights): DrawResult {
    val corner = (shape as? RoundedCornerShape)?.topStart?.toPx(size, this) ?: 0f
    val width = HlFocus.frame.toPx()
    val off = HlFocus.frameGap.toPx() + width / 2
    val stroke = Stroke(width)
    val band = Stroke(HlFocus.glow.toPx() / HlScrim.GLOW_STEPS)
    val framed = Size(size.width + 2 * off, size.height + 2 * off)
    // Several tones lie across the glow, each over its share of the picture; made once per list.
    var across: Pair<List<Color>, Brush>? = null
    return onDrawWithContent {
        val glowing = lights.glow.value
        // Read once glowing only: at rest, the light it follows redraws nothing.
        val lit = if (glowing > 0f) tones?.invoke().orEmpty() else emptyList()
        if (lit.size > 1) {
            val brush = across?.takeIf { it.first == lit }?.second
                ?: Brush.horizontalGradient(lit, startX = 0f, endX = size.width).also { across = lit to it }
            drawGlow(glowing, band, corner, across = brush)
        } else {
            lit.firstOrNull()?.let { tone -> drawGlow(tone.alpha * glowing, band, corner, tone = tone) }
        }
        drawContent()
        val on = lights.on.value
        if (frame != null && on > 0f) {
            drawRoundRect(frame, Offset(-off, -off), framed, CornerRadius(corner + off), alpha = on, style = stroke)
        }
    }
}

/**
 * The glow around a picture at [strength], in [HlScrim.GLOW_STEPS] rings [ring] wide: each ring is drawn once at the
 * combined alpha of stacked layers, so each layer costs a ring's fill, not a picture's, on the player's GPU. In its
 * [tone], or across the colours of [across].
 */
private fun DrawScope.drawGlow(strength: Float, ring: Stroke, corner: Float, tone: Color = Color.Unspecified, across: Brush? = null) {
    if (strength == 0f) return
    val steps = HlScrim.GLOW_STEPS
    val band = ring.width
    // Transmittance of the stacked layers, accumulated from the outermost lit ring inward; the last ring is clear.
    var through = 1f
    for (k in steps - 1 downTo 1) {
        through *= 1f - strength * HlScrim.GLOW_LAYER * (1f - k / steps.toFloat())
        // The band's middle line: a stroke is drawn half inside, half outside it.
        val middle = band * k - band / 2
        val topLeft = Offset(-middle, -middle)
        val ringSize = Size(size.width + 2 * middle, size.height + 2 * middle)
        if (across != null) {
            drawRoundRect(across, topLeft, ringSize, CornerRadius(corner + middle), alpha = 1f - through, style = ring)
        } else {
            drawRoundRect(tone.copy(alpha = 1f - through), topLeft, ringSize, CornerRadius(corner + middle), style = ring)
        }
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

/** Runs to 1 when [on], else to 0: in the focus pull, out in the fall back, at once if it [jumps]; idle when there. */
private suspend fun Animatable<Float, AnimationVector1D>.follow(on: Boolean, timing: Timing, jumps: Boolean) {
    val target = if (on) 1f else 0f
    if (value == target && targetValue == target) return
    when {
        jumps -> snapTo(target)
        on -> animateTo(target, tween(timing.on, easing = HlMotion.focusPull))
        else -> animateTo(target, tween(timing.off, easing = HlMotion.fallBack))
    }
}

/**
 * OK on the remote (centre, enter, the keypad's enter): pressed on its way down, a click on its way up;
 * held until it repeats, a long click instead, when there is one. Without [onClick] the key goes on to
 * what holds the surface. Focus leaving lets go of a press, and a key let go here without its press (taken
 * by another surface, or spent on a long click) clicks nothing.
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
                val pressed = press.value != null
                release()
                if (longClicked.value) longClicked.value = false else if (pressed) onClick()
            }
        }
        true
    }
}

/**
 * Menu or Pop Menu does [onLongClick] on its way up, if it went down here: remotes that send no repeats reach it so
 * (the Zidoo's turns a held OK into a click; AVA and Unfolded Circle remotes never repeat). Without one, it goes on.
 */
@Composable
private fun Modifier.menuForLongClick(onLongClick: (() -> Unit)?): Modifier {
    val down = remember { mutableStateOf(false) }
    return onFocusChanged { if (!it.isFocused) down.value = false }.onKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (onLongClick == null || native.keyCode !in MENU_KEYS) return@onKeyEvent false
        when (native.action) {
            KeyEvent.ACTION_DOWN -> if (native.repeatCount == 0) down.value = true
            KeyEvent.ACTION_UP -> if (down.value) {
                down.value = false
                onLongClick()
            }
        }
        true
    }
}

private const val HALF = 0.5f

private val OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)

/** Menu, and the Zidoo remote's Pop Menu, which its firmware sends as F12. */
private val MENU_KEYS = setOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_F12)
