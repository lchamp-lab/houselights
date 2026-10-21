package app.houselights.ui.design.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import app.houselights.ui.design.Ground
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.LocalGround
import app.houselights.ui.design.edge
import app.houselights.ui.design.glassPlate
import app.houselights.ui.design.groundFill
import app.houselights.ui.design.groundStep
import app.houselights.ui.design.groundStepFill
import app.houselights.ui.design.plateEdge
import app.houselights.ui.design.scrollEdges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A quiet panel scrolled with the remote, in a sheet a group's plate in the film's light; focusable only when there
 * is something to scroll, or always when [alwaysFocusable] (what a reading sheet is about, ringed as it is read).
 */
@Composable
fun HlScrollArea(modifier: Modifier = Modifier, alwaysFocusable: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { HlSize.scrollStep.toPx() }
    val keys = remember(scroll, scope) { TextKeyScroll(scroll, scope) }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .readingArea(focused)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { keys.onKey(it, step) }
            .focusProperties { canFocus = alwaysFocusable || scroll.maxValue > 0 }
            .focusable()
            .scrollEdges(scroll, readingFill())
            .verticalScroll(scroll)
            .padding(readingPadding()),
        content = content,
    )
}

/**
 * A lazy [HlScrollArea] for content too long to lay out whole (an NFO's XML, up to a megabyte); lines not
 * laid out yet count [lineHeight] each.
 */
@Composable
fun HlScrollArea(
    lineHeight: TextUnit,
    modifier: Modifier = Modifier,
    alwaysFocusable: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val step = with(density) { HlSize.scrollStep.toPx() }
    val line by rememberUpdatedState(with(density) { lineHeight.toPx() })
    val keys = remember(list, scope) { LazyKeyScroll(list, scope) { line } }
    var focused by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .readingArea(focused)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { keys.onKey(it, step) }
            .focusProperties { canFocus = alwaysFocusable || list.canScrollForward || list.canScrollBackward }
            .focusable()
            .scrollEdges(list, readingFill()),
        state = list,
        contentPadding = readingPadding(),
        content = content,
    )
}

/** A reading area's surface: a step of the room, or on glass a group's plate; its hairline the focus ring while focused. */
@Composable
private fun Modifier.readingArea(focused: Boolean): Modifier {
    val c = Hl.colors
    val onGlass = LocalGround.current == Ground.Glass
    val edge = if (focused) FocusStyle.ring() else BorderStroke(HlSize.hairline, if (onGlass) c.plateEdge else c.edge)
    return if (onGlass) clip(HlShapes.panel).plate(edge) else panel(HlShapes.panel, groundStepFill(), edge)
}

/** Room inside a reading area: a panel's all round, or on glass a reading plate's down ([ReadingPlate]) and a card's across. */
@Composable
private fun readingPadding(): PaddingValues = if (LocalGround.current == Ground.Glass) {
    PaddingValues(start = HlInset.card, end = HlInset.card, top = HlSpace.m, bottom = HlInset.plate)
} else {
    PaddingValues(HlInset.card)
}

/** What a reading area's text fades into at a cut end, read while drawing: its fill. */
@Composable
private fun readingFill(): () -> Color {
    val c = Hl.colors
    if (LocalGround.current != Ground.Glass) return groundFill(groundStep()).let { fill -> { fill } }
    val light = LocalAmbient.current
    return remember(c, light) { { c.glassPlate(light) } }
}

/**
 * Up and down scroll one step per press; a held key adds steps to the target and keeps the velocity
 * instead of restarting from rest, and targets are clamped so the motion eases into the ends.
 */
private abstract class KeyScroll(private val state: ScrollableState, private val scope: CoroutineScope) {
    private val position = Animatable(0f)
    private var target = 0f
    private var motion: Job? = null

    /** The current scroll position, in the motion's coordinates. */
    protected abstract val at: Float

    /** The reachable positions, top end to bottom end, in the motion's coordinates. */
    protected abstract fun range(): ClosedFloatingPointRange<Float>

    /** Scrolls the content to [to], in the motion's coordinates. */
    protected abstract fun moveTo(to: Float)

    fun onKey(event: KeyEvent, step: Float): Boolean {
        val direction = when {
            event.type != KeyEventType.KeyDown -> 0
            event.key == Key.DirectionDown && state.canScrollForward -> 1
            event.key == Key.DirectionUp && state.canScrollBackward -> -1
            else -> 0
        }
        // At an end, let the key move focus.
        if (direction == 0) return false
        val moving = motion?.isActive == true
        target = ((if (moving) target else at) + direction * step).coerceIn(range())
        val to = target
        // Read before launching: once this motion takes over, the one it replaces has stopped.
        val velocity = position.velocity
        motion = scope.launch {
            if (!moving) position.snapTo(at)
            position.animateTo(to, initialVelocity = velocity) { moveTo(value) }
        }
        return true
    }
}

/** Scroll of a [ScrollState], measured from the top. */
private class TextKeyScroll(private val scroll: ScrollState, scope: CoroutineScope) : KeyScroll(scroll, scope) {
    override val at: Float get() = scroll.value.toFloat()

    override fun range() = 0f..scroll.maxValue.toFloat()

    override fun moveTo(to: Float) {
        scroll.dispatchRawDelta(to - scroll.value)
    }
}

/**
 * Lazy list scroll, measured from where the remote first moved it; items not laid out count one [line]
 * each, so an end is exact once its line is laid out.
 */
private class LazyKeyScroll(
    private val list: LazyListState,
    scope: CoroutineScope,
    private val line: () -> Float,
) : KeyScroll(list, scope) {
    private var scrolled = 0f

    override val at: Float get() = scrolled

    override fun range() = (scrolled - toStart())..(scrolled + toEnd())

    override fun moveTo(to: Float) {
        scrolled += list.dispatchRawDelta(to - scrolled)
    }

    /** Remaining distance down; exact once the last line is laid out. */
    private fun toEnd(): Float {
        val info = list.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
        val below = (info.totalItemsCount - 1 - last.index) * line()
        return (last.offset + last.size + below + info.afterContentPadding - info.viewportEndOffset).coerceAtLeast(0f)
    }

    /** Remaining distance up; exact once the first line is laid out. */
    private fun toStart(): Float {
        val first = list.layoutInfo.visibleItemsInfo.firstOrNull() ?: return 0f
        return (first.index * line() - first.offset).coerceAtLeast(0f)
    }
}
