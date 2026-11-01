package app.houselights.ui.design.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
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

/** The group of framed cards a surface is in ([MediaRow], [PosterWallGrid], [Gallery]), which draws their frame; null elsewhere. */
internal val LocalGroupFrame = staticCompositionLocalOf<GroupFrame?> { null }

/** The one frame over the cards of [list], a row or a column of lines; the list draws it with [GroupFrame.drawn]. */
@Composable
internal fun rememberRowFrame(list: LazyListState): GroupFrame =
    rememberFrame(list, { list.layoutInfo.visibleItemsInfo.isNotEmpty() }, { list.layoutInfo.orientation })

/** The one frame over the cards of a wall, [grid]; the grid draws it with [GroupFrame.drawn]. */
@Composable
internal fun rememberGridFrame(grid: LazyGridState): GroupFrame =
    rememberFrame(grid, { grid.layoutInfo.visibleItemsInfo.isNotEmpty() }, { grid.layoutInfo.orientation })

/** The one frame over cards laid out once, never scrolled inside their group ([Gallery]). */
@Composable
internal fun rememberGroupFrame(): GroupFrame = rememberFrame(Unit, { true }, { null })

@Composable
private fun rememberFrame(state: Any, laidOut: () -> Boolean, axis: () -> Orientation?): GroupFrame {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val white = Hl.colors.focus
    val frame = remember(state, scope, density, white) { GroupFrame(laidOut, scope, density, white, axis) }
    // Under an open sheet the screen shows no focus, as its surfaces do (HlFocusSurface); a group in a sheet keeps it.
    val inSheet = LocalInSheet.current
    LaunchedEffect(frame, inSheet) { snapshotFlow { !inSheet && OpenSheets.count > 0 }.collect(frame::underSheet) }
    return frame
}

/**
 * A framed surface as a card of [frame]'s group, which draws its frame around it: what tells the group where it
 * lies and when it takes or loses focus. The group forgets it when it leaves the composition.
 */
@Composable
internal fun rememberFramedCard(frame: GroupFrame, shape: Shape): Modifier {
    val card = remember(frame, shape) { FramedCard(shape) }
    DisposableEffect(frame, card) { onDispose { frame.forget(card) } }
    return remember(frame, card) { frame.holds(card) }
}

/** A card whose frame its group draws: where it is laid out, and its [shape]. */
internal class FramedCard(val shape: Shape) {
    var at: LayoutCoordinates? = null
}

/** A frame's middle line in its group's coordinates, and its corner radius. */
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
 * One frame for a group of framed cards (DESIGN.md, Focus): it travels in a straight line from the card left to the
 * card reached in the focus pull, following the group's scroll, and is lit while the card reached has focus, no
 * sheet over it ([underSheet]); otherwise it goes out in place in the fall back. While an arrow is held, it jumps.
 * It is drawn over the group's content, cut where the group cuts its cards ([axis], the one it scrolls along, if any).
 * All of it is read while drawing, once the group is [laidOut]: the group's draw alone follows it, nothing recomposes.
 */
@Stable
internal class GroupFrame(
    private val laidOut: () -> Boolean,
    private val scope: CoroutineScope,
    private val density: Density,
    private val ink: Color,
    private val axis: () -> Orientation?,
) {
    private val stroke = Stroke(with(density) { HlFocus.frame.toPx() })

    /** From a card's edge to the frame's middle line. */
    private val off = with(density) { HlFocus.frameGap.toPx() + HlFocus.frame.toPx() / 2 }

    private var reached by mutableStateOf<FramedCard?>(null)

    /**
     * Where the frame set out, held to the card reached, while it travels ([travel] below 1): both move with the
     * group's scroll, so the journey goes on when the card left is gone from the composition.
     */
    private var setOut by mutableStateOf<Shift?>(null)
    private var travel by mutableFloatStateOf(1f)
    private var light by mutableFloatStateOf(0f)

    /** Focus is on the card [reached]. */
    private var held = false

    /** A sheet is open over the group ([OpenSheets]): the screen under it shows no focus. */
    private var covered = false

    private var lit = false
    private var travelling: Job? = null
    private var lighting: Job? = null
    private var group: LayoutCoordinates? = null

    /** How lit the frame is: 0 out, 1 on. */
    internal val shown: Float get() = light

    /** Draws the frame over the group's content, its glow and a raised card included, in the group's coordinates. */
    internal val drawn: Modifier = Modifier
        .onPlaced { group = it }
        .drawWithContent {
            drawContent()
            val alpha = light
            val box = if (alpha > 0f) now() else null
            if (box != null) {
                cut { drawRoundRect(ink, box.rect.topLeft, box.rect.size, CornerRadius(box.corner), alpha = alpha, style = stroke) }
            }
        }

    /** Tells the group where [card] lies, when it takes focus and when it loses it. */
    internal fun holds(card: FramedCard): Modifier = Modifier
        .onPlaced { card.at = it }
        .onFocusChanged { if (it.isFocused) arrive(card) else if (reached === card) leave() }

    internal fun forget(card: FramedCard) {
        card.at = null
    }

    /** A sheet opened over the group ([open]): the frame goes out where it stands, and lights again as the sheet goes. */
    internal fun underSheet(open: Boolean) {
        covered = open
        follow()
    }

    /** Where the frame is drawn now, in the root's coordinates, uncut. */
    internal fun boundsInRoot(): Rect? {
        val origin = group?.takeIf { it.isAttached }?.localToRoot(Offset.Zero) ?: return null
        return now()?.rect?.translate(origin)
    }

    /** Focus left the card reached, for another card or anything else: the frame goes out where it stands. */
    private fun leave() {
        held = false
        follow()
    }

    private fun arrive(card: FramedCard) {
        // Decided on the frame as seen: the card left lets go of it in the same focus change.
        val shift = reached?.takeIf { light > 0f && it !== card && !Scrub.active }?.let { setOutTowards(card) }
        setOut = shift
        reached = card
        travelling?.cancel()
        travel = if (shift != null) 0f else 1f
        if (shift != null) {
            travelling = scope.launch {
                animate(0f, 1f, animationSpec = tween(HlMotion.FOCUS_IN, easing = HlMotion.focusPull)) { value, _ -> travel = value }
            }
        }
        held = true
        follow()
    }

    /** Lit while focus is on the card reached, no sheet over it. */
    private fun follow() {
        val on = held && !covered
        if (on != lit) turn(on)
    }

    /** Where the frame sets out for [card], from where it is now, on the card left or on its way from it. */
    private fun setOutTowards(card: FramedCard): Shift? {
        val at = now() ?: return null
        val to = boxOf(card) ?: return null
        return Shift(at, to)
    }

    private fun turn(on: Boolean) {
        lit = on
        lighting?.cancel()
        val target = if (on) 1f else 0f
        if (Scrub.active) {
            light = target
            return
        }
        lighting = scope.launch { animate(light, target, animationSpec = HlMotion.inOut(on)) { value, _ -> light = value } }
    }

    /** The frame now: on its way from where it set out, or on the card reached. */
    private fun now(): FrameBox? {
        // Read while drawing, so the group's scroll redraws the frame.
        if (!laidOut()) return null
        val to = reached?.let(::boxOf) ?: return null
        val t = travel
        if (t >= 1f) return to
        return setOut?.let { between(it.from(to), to, t) } ?: to
    }

    /** Within the group's bounds along the [axis] it scrolls on, as its cards are; free past them across it. */
    private inline fun DrawScope.cut(draw: DrawScope.() -> Unit) {
        val past = stroke.width + off
        when (axis()) {
            Orientation.Horizontal -> clipRect(0f, -past, size.width, size.height + past, block = draw)
            Orientation.Vertical -> clipRect(-past, 0f, size.width + past, size.height, block = draw)
            null -> draw()
        }
    }

    /** [card]'s frame: its bounds in the group, then [off] beyond, corners parallel. */
    private fun boxOf(card: FramedCard): FrameBox? {
        val group = group?.takeIf { it.isAttached } ?: return null
        val at = card.at?.takeIf { it.isAttached } ?: return null
        val bounds = group.localBoundingBoxOf(at, clipBounds = false)
        val corner = (card.shape as? RoundedCornerShape)?.topStart?.toPx(bounds.size, density) ?: 0f
        return FrameBox(bounds.inflate(off), corner + off)
    }
}
