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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.emberLight

/** The keys of a [LetterGrid], line by line: the alphabet, then the figures. */
val LETTER_KEYS: List<List<Char>> = ("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789").chunked(LETTER_COLUMNS).map { it.toList() }

/** Keys a line of a [LetterGrid] holds. */
const val LETTER_COLUMNS = 6

/**
 * What a search looks for, as it is typed: [query] in `headline`, on the ember rule of typing, which
 * says the keys write here; before anything is typed, the screen's name in its place ([placeholder]),
 * quiet. Under the rule, what a search reads ([hint]). The screen's head: no title above it.
 */
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
        // Ember while typing, and only then: here, the keys always write.
        Box(Modifier.fillMaxWidth().height(HlSize.focusBorder).background(c.emberLight()))
        Spacer(Modifier.height(HlSpace.xs))
        Text(hint, style = Hl.type.caption, color = c.contentTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The keys of a search, without the system's keyboard: the alphabet and the figures, six a line, each
 * a pill white with focus; beside their first lines, the key that takes the last letter back, nearest
 * the words, then a space. Letters are typed as they are read, in capitals; a search ignores case and
 * accents. [first]: the key focus starts on, the first letter.
 */
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
                        KeyPill(key.toString(), onClick = { onKey(key) }, modifier = start.size(HlSize.control))
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

/** One key: a pill of [label], white with focus, as every control. */
@Composable
private fun KeyPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Hl.colors
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = label },
        shape = ClickableSurfaceDefaults.shape(HlShapes.control),
        scale = FocusStyle.scale(FocusStyle.Fill.Pill),
        border = FocusStyle.disabledRing(HlShapes.control),
        colors = FocusStyle.fill(rest = c.content.copy(alpha = HlAlpha.FILL), restContent = c.content),
    ) {
        Box(Modifier.size(HlSize.control), contentAlignment = Alignment.Center) {
            Text(label, style = Hl.type.entry, maxLines = 1)
        }
    }
}
