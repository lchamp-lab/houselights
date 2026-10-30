package app.houselights.ui.design.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
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
import app.houselights.ui.design.HlSpace
import kotlinx.coroutines.launch

/**
 * A title's overview cut at [HEAD_OVERVIEW_LINES] lines. When truncated it becomes a focusable row, text
 * in place, and OK opens it whole ([onOpen]); when it fits, focus skips it.
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
 * The whole overview in a side sheet of the standard width, about 64 characters a line: its tagline in `lead` over its
 * text in `body`; the text is focused, down and up scroll it [READING_STEP] lines per press.
 */
@Composable
fun SynopsisSheet(item: MediaItem, onDismiss: () -> Unit, modifier: Modifier = Modifier) =
    SynopsisSheet(
        facts = listOf(item.title, item.year?.toString(), item.directors.firstOrNull()),
        tagline = item.tagline,
        overview = item.overview.orEmpty(),
        onDismiss = onDismiss,
        modifier = modifier,
    )

/**
 * The whole [overview] of what [facts] name in the sheet's head, after its [tagline]: a saga's film the library lacks,
 * its title, its year and what it is (« Absent de votre bibliothèque », « Sortie le 15 décembre 2026 »).
 */
@Composable
fun SynopsisSheet(facts: List<String?>, tagline: String?, overview: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = Hl.colors
    val words = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { Hl.type.body.lineHeight.toPx() * READING_STEP }
    HlSideSheet(
        stringResource(R.string.synopsis),
        onDismiss = onDismiss,
        modifier = modifier,
        facts = facts,
        initialFocus = words,
        scroll = scroll,
    ) { _ ->
        Column {
            // The tagline as far under the head as over the text, from its baseline.
            tagline?.takeIf { it.isNotBlank() }?.let {
                val apart = HlSpace.l + HlSpace.xxs
                Text(it, style = Hl.type.lead, color = c.content, modifier = Modifier.paddingFromBaseline(top = apart, bottom = apart))
            }
            Text(
                overview,
                style = Hl.type.body,
                color = c.contentSecondary,
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
}

/** Lines scrolled per press in a [SynopsisSheet]. */
private const val READING_STEP = 3

/** Grows the content by [x] and [y] on each side without changing its layout size. */
private fun Modifier.around(x: Dp, y: Dp): Modifier = layout { measurable, constraints ->
    val dx = x.roundToPx()
    val dy = y.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = 2 * dx, vertical = 2 * dy))
    layout(placeable.width - 2 * dx, placeable.height - 2 * dy) { placeable.place(-dx, -dy) }
}
