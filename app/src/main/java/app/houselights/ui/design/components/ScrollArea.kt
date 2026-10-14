package app.houselights.ui.design.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.ui.unit.Dp
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Text or tables longer than the space they have, read with the remote: the area takes focus
 * (outlined in projector white) only when there is something to scroll, and up and down then
 * scroll it until an end, where focus moves on. [framed] sets it on a quiet panel.
 */
@Composable
fun HlScrollArea(
    modifier: Modifier = Modifier,
    framed: Boolean = true,
    padding: Dp = if (framed) HlSpace.l else HlSpace.xxs,
    content: @Composable BoxScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { HlSize.scrollStep.toPx() }
    var focused by remember { mutableStateOf(false) }
    val c = Hl.colors
    Box(
        modifier
            .fillMaxWidth()
            .clip(HlShapes.panel)
            .background(if (framed) c.surface.copy(alpha = HlAlpha.QUIET_SURFACE) else Color.Transparent)
            .border(
                if (focused) HlSize.focusBorder else HlSize.hairline,
                when {
                    focused -> c.focus
                    framed -> c.outline.copy(alpha = HlAlpha.BORDER)
                    else -> Color.Transparent
                },
                HlShapes.panel,
            )
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { scrollBy(it, scroll, scope, step) }
            .focusProperties { canFocus = scroll.maxValue > 0 }
            .focusable()
            .verticalScroll(scroll)
            .padding(padding),
        content = content,
    )
}

private fun scrollBy(event: KeyEvent, scroll: ScrollState, scope: CoroutineScope, step: Float): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.DirectionDown -> {
            if (scroll.value >= scroll.maxValue) return false
            scope.launch { scroll.animateScrollBy(step) }
            true
        }
        Key.DirectionUp -> {
            if (scroll.value <= 0) return false
            scope.launch { scroll.animateScrollBy(-step) }
            true
        }
        else -> false
    }
}
