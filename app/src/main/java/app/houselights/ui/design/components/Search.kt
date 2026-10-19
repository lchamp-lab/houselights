package app.houselights.ui.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.emberLight
import app.houselights.ui.design.plateEdge

/** The keys of a [LetterGrid], row by row: the alphabet, then the digits. */
val LETTER_KEYS: List<List<Char>> = ("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789").chunked(LETTER_COLUMNS).map { it.toList() }

/** Keys per row of a [LetterGrid]. */
const val LETTER_COLUMNS = 6

/**
 * The typed [query] on a field the keys always type in: a glass plate ringed by the ember light, the search
 * icon, the words, then the raspberry cursor; [hint] below. The screen's title names the place above it.
 */
@Composable
fun SearchQuery(query: String, hint: String, modifier: Modifier = Modifier) {
    val c = Hl.colors
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(HlSize.control)
                .clip(HlShapes.field)
                .background(c.glass)
                .border(BorderStroke(HlSize.focusBorder, c.emberLight()), HlShapes.field)
                .padding(horizontal = HlSpace.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HlIcons.Search, contentDescription = null, tint = c.contentSecondary, modifier = Modifier.size(HlSize.icon))
            Spacer(Modifier.width(HlSpace.s))
            Text(
                query,
                style = Hl.type.lead,
                color = c.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // As tall as the words, and still: only a wait moves on its own.
            val cursor = with(LocalDensity.current) { Hl.type.lead.fontSize.toDp() }
            Box(Modifier.width(HlSize.focusBorder).height(cursor).background(c.emberTo))
        }
        Spacer(Modifier.height(HlSpace.xs))
        Text(hint, style = Hl.type.caption, color = c.contentTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Search keys (letters, digits, delete, space) without the system keyboard; focus starts on [first]. */
@Composable
fun LetterGrid(
    onKey: (Char) -> Unit,
    onSpace: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    first: FocusRequester? = null,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
            LETTER_KEYS.forEachIndexed { row, keys ->
                Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                    keys.forEachIndexed { column, key ->
                        val start = if (row == 0 && column == 0 && first != null) Modifier.focusRequester(first) else Modifier
                        KeyTile(key.toString(), onClick = { onKey(key) }, modifier = start.size(HlSize.tileCompact))
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
            HlButton(stringResource(R.string.search_delete), onClick = onDelete, icon = HlIcons.Back)
            HlButton(stringResource(R.string.search_space), onClick = onSpace)
        }
    }
}

/** One key: a square tile showing [label] on its glass plate, white on focus. */
@Composable
private fun KeyTile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Hl.colors
    HlFocusSurface(
        onClick = onClick,
        look = FocusStyle.fill(rest = c.glass, restInk = c.content, edge = BorderStroke(HlSize.hairline, c.plateEdge)),
        shape = HlShapes.control,
        modifier = modifier.semantics { contentDescription = label },
    ) {
        Box(Modifier.size(HlSize.tileCompact), contentAlignment = Alignment.Center) {
            Text(label, style = Hl.type.entry, maxLines = 1)
        }
    }
}
