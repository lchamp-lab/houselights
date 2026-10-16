package app.houselights.ui.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.edge
import app.houselights.ui.design.groundFill
import app.houselights.ui.design.groundStep
import app.houselights.ui.design.groundStepFill
import app.houselights.ui.design.scrollEdges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Text or tables longer than the space they have, read with the remote, on a quiet panel: the area
 * takes focus (outlined in projector white) only when there is something to scroll, and up and
 * down then scroll it until an end, where focus moves on. Where there is more to read, the text
 * fades into the panel's fill as it shows.
 */
@Composable
fun HlScrollArea(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { HlSize.scrollStep.toPx() }
    val keys = remember(scroll, scope) { TextKeyScroll(scroll, scope) }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .panel(HlShapes.panel, groundStepFill(), readingEdge(focused))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { keys.onKey(it, step) }
            .focusProperties { canFocus = scroll.maxValue > 0 }
            .focusable()
            .scrollEdges(scroll, groundFill(groundStep()))
            .verticalScroll(scroll)
            .padding(HlInset.card),
        content = content,
    )
}

/**
 * An [HlScrollArea] for what is too long to lay out whole (the XML of an NFO, up to a megabyte): a
 * lazy list, which lays out only the lines on screen, read with the remote as the text is, at the
 * same step and with the same carry, on the same panel. Its lines are [lineHeight] apart, which is
 * how far it counts each one it has not laid out yet.
 */
@Composable
fun HlScrollArea(lineHeight: TextUnit, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
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
            .panel(HlShapes.panel, groundStepFill(), readingEdge(focused))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { keys.onKey(it, step) }
            .focusProperties { canFocus = list.canScrollForward || list.canScrollBackward }
            .focusable()
            .scrollEdges(list, groundFill(groundStep())),
        state = list,
        contentPadding = PaddingValues(HlInset.card),
        content = content,
    )
}

/** The edge of an area to read: the quiet panel's hairline, the ring of focus while it has it. */
@Composable
private fun readingEdge(focused: Boolean): BorderStroke =
    if (focused) FocusStyle.ring() else BorderStroke(HlSize.hairline, Hl.colors.edge)

/**
 * Up and down scroll one step per press. A key held down adds its steps to where the scroll is
 * heading and the motion carries on, keeping its speed, instead of starting over from rest at
 * each repeat. Each step stops at an end, so the motion slows into it rather than against it.
 * What scrolls says where it stands ([at]), how far the motion may go ([range]), and moves.
 */
private abstract class KeyScroll(private val state: ScrollableState, private val scope: CoroutineScope) {
    private val position = Animatable(0f)
    private var target = 0f
    private var motion: Job? = null

    /** Where the content stands, as the motion counts it. */
    protected abstract val at: Float

    /** Where the motion may go, from the top end to the bottom one, as it counts them. */
    protected abstract fun range(): ClosedFloatingPointRange<Float>

    /** Moves the content to [to], as the motion counts it. */
    protected abstract fun moveTo(to: Float)

    fun onKey(event: KeyEvent, step: Float): Boolean {
        val direction = when {
            event.type != KeyEventType.KeyDown -> 0
            event.key == Key.DirectionDown && state.canScrollForward -> 1
            event.key == Key.DirectionUp && state.canScrollBackward -> -1
            else -> 0
        }
        // At an end, the key moves focus on.
        if (direction == 0) return false
        val moving = motion?.isActive == true
        target = ((if (moving) target else at) + direction * step).coerceIn(range())
        val to = target
        // Read now: by the time this motion takes over, the one it replaces has stopped.
        val velocity = position.velocity
        motion = scope.launch {
            if (!moving) position.snapTo(at)
            position.animateTo(to, initialVelocity = velocity) { moveTo(value) }
        }
        return true
    }
}

/** A text's scroll, counted from its top. */
private class TextKeyScroll(private val scroll: ScrollState, scope: CoroutineScope) : KeyScroll(scroll, scope) {
    override val at: Float get() = scroll.value.toFloat()

    override fun range() = 0f..scroll.maxValue.toFloat()

    override fun moveTo(to: Float) {
        scroll.dispatchRawDelta(to - scroll.value)
    }
}

/**
 * A lazy list's scroll, counted from where it stood when the remote first moved it. A lazy list
 * knows its length only as far as it has laid it out, and counts what lies beyond as one [line]
 * each, so an end is exact once its last line is laid out.
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

    /** How far the list can still go down: exactly once its last line is laid out. */
    private fun toEnd(): Float {
        val info = list.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
        val below = (info.totalItemsCount - 1 - last.index) * line()
        return (last.offset + last.size + below + info.afterContentPadding - info.viewportEndOffset).coerceAtLeast(0f)
    }

    /** How far it can still go up: exactly once its first line is laid out. */
    private fun toStart(): Float {
        val first = list.layoutInfo.visibleItemsInfo.firstOrNull() ?: return 0f
        return (first.index * line() - first.offset).coerceAtLeast(0f)
    }
}
