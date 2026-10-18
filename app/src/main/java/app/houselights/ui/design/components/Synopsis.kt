package app.houselights.ui.design.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.MediaItem
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import kotlinx.coroutines.launch

/**
 * A title's overview in its head, cut at [HEAD_OVERVIEW_LINES] lines. Cut, it takes focus, white
 * as every row, its words where they stood, and OK opens it whole ([onOpen], a [SynopsisSheet]);
 * whole, it is words alone, which focus passes by.
 */
@Composable
fun HeadOverview(text: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    var cut by remember(text) { mutableStateOf(false) }
    val style = Hl.type.body
    if (!cut) {
        Text(
            text,
            style = style,
            color = Hl.colors.contentSecondary,
            maxLines = HEAD_OVERVIEW_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { cut = it.hasVisualOverflow },
            modifier = modifier,
        )
        return
    }
    // The fill spreads around the words, which keep their place.
    HlRowSurface(onClick = onOpen, modifier = modifier.around(HlInset.card, HlSpace.xs)) { focused ->
        Text(
            text,
            style = style,
            color = if (focused) LocalContentColor.current else Hl.colors.contentSecondary,
            maxLines = HEAD_OVERVIEW_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = HlInset.card, vertical = HlSpace.xs),
        )
    }
}

/** Lines of the overview a title's head shows. */
const val HEAD_OVERVIEW_LINES = 3

/**
 * The overview whole, beside a title's page ([HlSideSheet]): its title and year in the head, then
 * its words in the voice of reading. Focus is on them: where they run past the panel, down and up
 * move them [READING_STEP] lines at a time. Back closes.
 */
@Composable
fun SynopsisSheet(item: MediaItem, onDismiss: () -> Unit) {
    val words = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { Hl.type.body.lineHeight.toPx() * READING_STEP }
    HlSideSheet(
        stringResource(R.string.synopsis),
        onDismiss = onDismiss,
        facts = listOf(item.title, item.year?.toString()),
        artwork = item.poster?.let { poster -> { PosterThumb(poster, width = HlSize.posterThumbLarge) } },
        initialFocus = words,
        scroll = scroll,
    ) { _ ->
        Text(
            item.overview.orEmpty(),
            style = Hl.type.body,
            color = Hl.colors.contentSecondary,
            modifier = Modifier
                .focusRequester(words)
                .onPreviewKeyEvent { e ->
                    val by = when (e.key) {
                        Key.DirectionDown -> step
                        Key.DirectionUp -> -step
                        else -> return@onPreviewKeyEvent false
                    }
                    if (e.type == KeyEventType.KeyDown) scope.launch { scroll.animateScrollBy(by) }
                    true
                }
                .focusable(),
        )
    }
}

/** Lines the words of a [SynopsisSheet] move by a press. */
private const val READING_STEP = 3

/** Lays out what it holds [x] wider on each side and [y] taller on each, as if it were not. */
private fun Modifier.around(x: Dp, y: Dp): Modifier = layout { measurable, constraints ->
    val dx = x.roundToPx()
    val dy = y.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = 2 * dx, vertical = 2 * dy))
    layout(placeable.width - 2 * dx, placeable.height - 2 * dy) { placeable.place(-dx, -dy) }
}
