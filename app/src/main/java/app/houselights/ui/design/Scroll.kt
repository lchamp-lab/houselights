package app.houselights.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Lists scroll only by what is needed to reveal the focused element. The TV default pivots
 * focus to a third of the screen, which scrolls titles and heroes away for no reason.
 */
@OptIn(ExperimentalFoundationApi::class)
val HlBringIntoView = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0 && trailing <= containerSize -> 0f
            offset < 0 && trailing > containerSize -> 0f
            abs(offset) < abs(trailing - containerSize) -> offset
            else -> trailing - containerSize
        }
    }
}

/**
 * Rows keep what has focus [ROW_MARGIN] away from both ends of the screen. The minimal spec
 * above would stop a row scrolled back to its first card with that card against the screen
 * edge; with a margin wider than the row's padding, the row comes back to where it rests, and
 * moving right always lets the next card peek in.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowScroll(content: @Composable () -> Unit) {
    val margin = with(LocalDensity.current) { ROW_MARGIN.toPx() }
    val spec = remember(margin) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val start = margin
                val end = containerSize - margin
                val trailing = offset + size
                return when {
                    offset >= start && trailing <= end -> 0f
                    offset < start || size > end - start -> offset - start
                    else -> trailing - end
                }
            }
        }
    }
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
