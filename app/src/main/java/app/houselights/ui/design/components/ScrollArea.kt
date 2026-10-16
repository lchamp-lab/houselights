package app.houselights.ui.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
    val keys = remember(scroll, scope) { KeyScroll(scroll, scope) }
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

/** The edge of an area to read: the quiet panel's hairline, the ring of focus while it has it. */
@Composable
private fun readingEdge(focused: Boolean): BorderStroke =
    if (focused) FocusStyle.ring() else BorderStroke(HlSize.hairline, Hl.colors.edge)

/**
 * Up and down scroll one step per press. A key held down adds its steps to where the scroll is
 * heading and the motion carries on, keeping its speed, instead of starting over from rest at
 * each repeat.
 */
private class KeyScroll(private val scroll: ScrollState, private val scope: CoroutineScope) {
    private val position = Animatable(0f)
    private var target = 0f
    private var motion: Job? = null

    fun onKey(event: KeyEvent, step: Float): Boolean {
        val direction = when {
            event.type != KeyEventType.KeyDown -> 0
            event.key == Key.DirectionDown && scroll.value < scroll.maxValue -> 1
            event.key == Key.DirectionUp && scroll.value > 0 -> -1
            else -> 0
        }
        // At an end, the key moves focus on.
        if (direction == 0) return false
        val moving = motion?.isActive == true
        target = ((if (moving) target else scroll.value.toFloat()) + direction * step).coerceIn(0f, scroll.maxValue.toFloat())
        val to = target
        // Read now: by the time this motion takes over, the one it replaces has stopped.
        val velocity = position.velocity
        motion = scope.launch {
            if (!moving) position.snapTo(scroll.value.toFloat())
            position.animateTo(to, initialVelocity = velocity) { scroll.dispatchRawDelta(value - scroll.value) }
        }
        return true
    }
}
