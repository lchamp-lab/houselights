package app.houselights.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch

/**
 * Scrolls only by what is needed to bring an element into the window [leading, containerSize −
 * trailing] (px): never to a third of the screen as the TV default does, which scrolls titles and
 * heroes away for no reason. An element longer than the window aligns its start with the window's.
 */
@OptIn(ExperimentalFoundationApi::class)
class EdgeScroll(private val leading: Float, private val trailing: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = leading
        val end = containerSize - trailing
        val itemEnd = offset + size
        return when {
            offset >= start && itemEnd <= end -> 0f
            offset < start || size > end - start -> offset - start
            else -> itemEnd - end
        }
    }
}

/**
 * How vertical lists scroll, provided for the whole app by [HouselightsTheme]: what has focus
 * stops [HlFocus.edge] away from the bottom, so the last row rests inside the safe area and a
 * focused poster grows into the margin rather than off the screen, and [top] away from the top.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberListScroll(top: Dp = HlFocus.edge): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(density, top) { with(density) { EdgeScroll(top.toPx(), HlFocus.edge.toPx()) } }
}

/**
 * For a list that starts below the top of the screen, under a hero or a header, already inside
 * the safe area: its rows come back to [top], where its first row rests (its top padding), and
 * the row above leaves the list whole instead of lingering as a sliver.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListScroll(top: Dp, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberListScroll(top), content = content)
}

/**
 * Rows keep what has focus [ROW_MARGIN] away from both ends of the screen. With no margin, a row
 * scrolled back to its first card would stop with that card against the screen edge; with a
 * margin wider than the row's padding, the row comes back to where it rests, and moving right
 * always lets the next card peek in.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowScroll(content: @Composable () -> Unit) {
    val margin = with(LocalDensity.current) { ROW_MARGIN.toPx() }
    val spec = remember(margin) { EdgeScroll(margin, margin) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** Wider than any row's start padding, so the first card always scrolls fully home. */
private val ROW_MARGIN = HlSpace.safeH + HlSpace.xl

/**
 * Keeps vertical navigation inside this column: up or down at its edges does nothing instead
 * of jumping to a neighbouring column. Left and right still leave it.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.keepVerticalFocus(): Modifier = this
    .focusProperties {
        exit = { direction ->
            if (direction == FocusDirection.Up || direction == FocusDirection.Down) FocusRequester.Cancel else FocusRequester.Default
        }
    }
    .focusGroup()

/**
 * When focus enters this element, scroll it fully into view, not only the focused child.
 * Use it on groups whose title or context sits above their first focusable element
 * (a row and its header, a hero and its buttons, a settings section).
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.revealOnFocus(): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    this
        .bringIntoViewRequester(requester)
        .onFocusChanged { if (it.hasFocus) scope.launch { requester.bringIntoView() } }
}

/**
 * State of a row whose content can change while it is shown (new titles found by a scan). A list
 * keeps showing the item it was showing first, so titles arriving in front would land out of
 * sight on the left. If the row was at its start, it stays there and the new titles show; if the
 * viewer had moved along it, nothing moves under them.
 */
@Composable
fun rememberRowState(keys: List<Any>): LazyListState {
    val state = rememberLazyListState()
    val first = keys.firstOrNull()
    var shownFirst by remember { mutableStateOf(first) }
    LaunchedEffect(first) {
        // Let the list place itself with the new content first.
        withFrameNanos { }
        val was = keys.indexOf(shownFirst)
        if (was > 0 && state.firstVisibleItemIndex == was) state.scrollToItem(0)
        shownFirst = first
    }
    return state
}
