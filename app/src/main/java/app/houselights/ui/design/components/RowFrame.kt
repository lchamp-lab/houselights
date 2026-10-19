package app.houselights.ui.design.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.Scrub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The row of framed cards a surface is in ([MediaRow]), which draws their frame; null elsewhere. */
internal val LocalRowFrame = staticCompositionLocalOf<RowFrame?> { null }

/** The one frame of a row over [list]'s cards; the row draws it with [RowFrame.drawn]. */
@Composable
internal fun rememberRowFrame(list: LazyListState): RowFrame {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val white = Hl.colors.focus
    return remember(list, scope, density, white) { RowFrame(list, scope, density, white) }
}

/**
 * A framed surface as a card of [row], which draws its frame around it as far as it has [risen]: what tells the
 * row where it lies and when it takes or loses focus. The row forgets it when it leaves the composition.
 */
@Composable
internal fun rememberRowCard(row: RowFrame, lift: Lift, shape: Shape, risen: () -> Float): Modifier {
    val card = remember(row, lift, shape) { FramedCard(lift, shape, risen) }
    DisposableEffect(row, card) { onDispose { row.forget(card) } }
    return remember(row, card) { row.holds(card) }
}

/** A card whose frame its row draws: where it is laid out, how far it has [risen], its [lift] and [shape]. */
internal class FramedCard(val lift: Lift, val shape: Shape, val risen: () -> Float) {
    var at: LayoutCoordinates? = null
}

/** A frame's middle line in its row's coordinates, and its corner radius. */
private class FrameBox(val rect: Rect, val corner: Float)

private fun between(from: FrameBox, to: FrameBox, t: Float) =
    FrameBox(lerp(from.rect, to.rect, t), from.corner + (to.corner - from.corner) * t)

/** Where a frame stood from a card's frame, edge by edge and in its corner: it stays there as the card moves. */
private class Shift(at: FrameBox, card: FrameBox) {
    private val left = at.rect.left - card.rect.left
    private val top = at.rect.top - card.rect.top
    private val right = at.rect.right - card.rect.right
    private val bottom = at.rect.bottom - card.rect.bottom
    private val corner = at.corner - card.corner

    fun from(card: FrameBox) = FrameBox(
        Rect(card.rect.left + left, card.rect.top + top, card.rect.right + right, card.rect.bottom + bottom),
        card.corner + corner,
    )
}

/**
 * One frame for a row of framed cards (DESIGN.md, Focus): it travels from the card left to the card reached in
 * the focus pull, following their growth and the row's scroll, and goes out in place in the fall back when
 * focus leaves the row ([rowFocused]). While an arrow is held, it jumps. All of it is read while drawing: the row's
 * draw alone follows it, nothing recomposes.
 */
@Stable
internal class RowFrame(
    private val list: LazyListState,
    private val scope: CoroutineScope,
    private val density: Density,
    private val ink: Color,
) {
    private val stroke = Stroke(with(density) { HlFocus.frame.toPx() })

    /** From a card's edge to the frame's middle line. */
    private val off = with(density) { HlFocus.frameGap.toPx() + HlFocus.frame.toPx() / 2 }

    private var reached by mutableStateOf<FramedCard?>(null)

    /** Where the frame set out from, while it travels ([travel] below 1). */
    private var from by mutableStateOf<(() -> FrameBox?)?>(null)
    private var travel by mutableFloatStateOf(1f)
    private var light by mutableFloatStateOf(0f)

    private var lit = false
    private var travelling: Job? = null
    private var lighting: Job? = null
    private var row: LayoutCoordinates? = null

    /** How lit the frame is: 0 out, 1 on. */
    internal val shown: Float get() = light

    /** Draws the frame over the row's cards, in the row's coordinates. */
    internal val drawn: Modifier = Modifier
        .onPlaced { row = it }
        .drawWithContent {
            drawContent()
            val alpha = light
            val box = if (alpha > 0f) now() else null
            if (box != null) {
                drawRoundRect(ink, box.rect.topLeft, box.rect.size, CornerRadius(box.corner), alpha = alpha, style = stroke)
            }
        }

    /** Tells the row where [card] lies and when it takes focus. */
    internal fun holds(card: FramedCard): Modifier = Modifier
        .onPlaced { card.at = it }
        .onFocusChanged { if (it.isFocused) arrive(card) }

    internal fun forget(card: FramedCard) {
        card.at = null
    }

    /** Focus left the row ([focused] false): the frame goes out where it stands. */
    internal fun rowFocused(focused: Boolean) {
        if (!focused && lit) turn(on = false)
    }

    /** Where the frame is drawn now, in the root's coordinates. */
    internal fun boundsInRoot(): Rect? {
        val origin = row?.takeIf { it.isAttached }?.localToRoot(Offset.Zero) ?: return null
        return now()?.rect?.translate(origin)
    }

    private fun arrive(card: FramedCard) {
        val setOut = reached?.takeIf { lit && it !== card && !Scrub.active }?.let(::setOutFrom)
        from = setOut
        reached = card
        travelling?.cancel()
        travel = if (setOut != null) 0f else 1f
        if (setOut != null) {
            travelling = scope.launch {
                animate(0f, 1f, animationSpec = tween(HlMotion.FOCUS_IN, easing = HlMotion.focusPull)) { value, _ -> travel = value }
            }
        }
        if (!lit) turn(on = true)
    }

    /** Where a frame leaving [left] sets out: from it, or from where it was on its way, held to it. */
    private fun setOutFrom(left: FramedCard): () -> FrameBox? {
        val at = now()
        val card = boxOf(left)
        if (travel >= 1f || at == null || card == null) return { boxOf(left) }
        val shift = Shift(at, card)
        return { boxOf(left)?.let(shift::from) }
    }

    private fun turn(on: Boolean) {
        lit = on
        lighting?.cancel()
        val target = if (on) 1f else 0f
        if (Scrub.active) {
            light = target
            return
        }
        val spec = if (on) {
            tween<Float>(HlMotion.FOCUS_IN, easing = HlMotion.focusPull)
        } else {
            tween(HlMotion.FOCUS_OUT, easing = HlMotion.fallBack)
        }
        lighting = scope.launch { animate(light, target, animationSpec = spec) { value, _ -> light = value } }
    }

    /** The frame now: on its way from where it set out, or on the card reached. */
    private fun now(): FrameBox? {
        // Read while drawing, so the row's scroll redraws the frame.
        if (list.layoutInfo.visibleItemsInfo.isEmpty()) return null
        val to = reached?.let(::boxOf) ?: return null
        val t = travel
        if (t >= 1f) return to
        return from?.invoke()?.let { between(it, to, t) } ?: to
    }

    /** [card]'s frame: its bounds in the row grown as far as it has risen, then [off] beyond, corners parallel. */
    private fun boxOf(card: FramedCard): FrameBox? {
        val row = row?.takeIf { it.isAttached } ?: return null
        val at = card.at?.takeIf { it.isAttached } ?: return null
        val bounds = row.localBoundingBoxOf(at, clipBounds = false)
        val grown = 1f + (card.lift.scale - 1f) * card.risen()
        val x = bounds.width * (grown - 1f) / 2 + off
        val y = bounds.height * (grown - 1f) / 2 + off
        val corner = (card.shape as? RoundedCornerShape)?.topStart?.toPx(bounds.size, density) ?: 0f
        return FrameBox(Rect(bounds.left - x, bounds.top - y, bounds.right + x, bounds.bottom + y), corner * grown + off)
    }
}
