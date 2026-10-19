package app.houselights.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.emberLight

/** The keys of a [LetterGrid], row by row: the alphabet, then the digits. */
val LETTER_KEYS: List<List<Char>> = ("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789").chunked(LETTER_COLUMNS).map { it.toList() }

/** Keys per row of a [LetterGrid]. */
const val LETTER_COLUMNS = 6

/** The typed [query] above the ember typing rule, or the quiet [placeholder], then [hint]; heads the screen. */
@Composable
fun SearchQuery(query: String, placeholder: String, hint: String, modifier: Modifier = Modifier) {
    val c = Hl.colors
    Column(modifier) {
        Text(
            query.ifEmpty { placeholder },
            style = Hl.type.headline,
            color = if (query.isEmpty()) c.contentTertiary else c.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(HlSpace.xs))
        // Ember marks typing only; here the keys always type.
        Box(Modifier.fillMaxWidth().height(HlSize.focusBorder).background(c.emberLight()))
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
                        KeyTile(key.toString(), onClick = { onKey(key) }, modifier = start.size(HlSize.tile))
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

/** One key: a tile showing [label], white on focus. */
@Composable
private fun KeyTile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Hl.colors
    HlFocusSurface(
        onClick = onClick,
        look = FocusStyle.fill(rest = c.content.copy(alpha = HlAlpha.FILL), restInk = c.content),
        shape = HlShapes.control,
        modifier = modifier.semantics { contentDescription = label },
    ) {
        Box(Modifier.size(HlSize.tile), contentAlignment = Alignment.Center) {
            Text(label, style = Hl.type.entry, maxLines = 1)
        }
    }
}
