package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace

/**
 * A running scan: the ember line, the count in tabular figures ("3 / 9"), then the file being read in `meta`, on the
 * count's line or under a [label] above the line. Before files are counted ([total] 0) the line shows a
 * sliver and no count. On a [focused] plate its words take focus's ink and the line is a raspberry mark ([ProgressLine]).
 */
@Composable
fun ScanProgress(
    processed: Int,
    total: Int,
    modifier: Modifier = Modifier,
    label: String? = null,
    current: String? = null,
    lineWidth: Dp = HlSize.progressShort,
    focused: Boolean = false,
) {
    val fraction = if (total > 0) processed.toFloat() / total else 0f
    val ink = FocusStyle.ink(focused)
    val file: @Composable (Modifier) -> Unit = { m ->
        if (current != null) {
            val quiet = FocusStyle.hintInk(focused)
            Text(current, style = Hl.type.meta, color = quiet, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = m)
        }
    }
    Column(modifier.semantics(mergeDescendants = true) { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }) {
        if (label != null) {
            Text(label, style = Hl.type.subject, color = ink)
            Spacer(Modifier.height(HlSpace.s))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressLine(fraction.coerceAtLeast(SLIVER), Modifier.width(lineWidth), focused = focused)
            if (total > 0) {
                Spacer(Modifier.width(HlSpace.s))
                Text(stringResource(R.string.scan_progress, processed, total), style = Hl.type.meta, color = ink, maxLines = 1)
            }
            if (label == null) {
                Spacer(Modifier.width(HlSpace.m))
                file(Modifier.weight(1f))
            }
        }
        if (label != null && current != null) {
            Spacer(Modifier.height(HlSpace.xxs))
            file(Modifier)
        }
    }
}

private const val SLIVER = 0.02f
