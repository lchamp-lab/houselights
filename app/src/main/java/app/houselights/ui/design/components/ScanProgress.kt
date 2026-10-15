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
 * A scan going on, read like the counter of a projection booth: the ember line, the count at
 * its end ("3 / 9", in tabular figures, so it does not jitter as it climbs), then the file being
 * read, quiet. [label] says what is counted, in the voice of the poster, above the line (the first
 * scan, on home); without one, the counter keeps to a single line and the file follows the count
 * (a folder's card). Until the files are counted ([total] 0), the line shows a sliver and no count.
 */
@Composable
fun ScanProgress(
    processed: Int,
    total: Int,
    modifier: Modifier = Modifier,
    label: String? = null,
    current: String? = null,
    lineWidth: Dp = HlSize.progressShort,
) {
    val c = Hl.colors
    val fraction = if (total > 0) processed.toFloat() / total else 0f
    val file: @Composable (Modifier) -> Unit = { m ->
        if (current != null) {
            Text(current, style = Hl.type.detail, color = c.contentTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = m)
        }
    }
    Column(modifier.semantics(mergeDescendants = true) { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }) {
        if (label != null) {
            Text(label, style = Hl.type.subject, color = c.content)
            Spacer(Modifier.height(HlSpace.s))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A scan that has just started still shows a sliver of progress.
            ProgressLine(fraction.coerceAtLeast(SLIVER), Modifier.width(lineWidth))
            if (total > 0) {
                Spacer(Modifier.width(HlSpace.s))
                Text(stringResource(R.string.scan_progress, processed, total), style = Hl.type.meta, color = c.content, maxLines = 1)
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
