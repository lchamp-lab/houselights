package app.houselights.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.providers.local.IgnoredFileRow
import app.houselights.providers.local.IgnoredReason
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.ReadingPlate
import app.houselights.ui.util.FACT_GAP

/**
 * The videos the shows folders hold that the library leaves out, as the last row of the library's actions: "3 fichiers
 * ignorés", what they have in common under it, never in ember, as nothing failed. OK lists them ([onOpen]); Menu does
 * nothing.
 */
@Composable
internal fun IgnoredFilesRow(ignored: List<IgnoredFileRow>, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val count = ignored.size
    HlSettingRow(
        pluralStringResource(R.plurals.ignored_files, count, count),
        modifier,
        description = stringResource(if (count == 1) R.string.ignored_files_desc_one else R.string.ignored_files_desc),
        onClick = onOpen,
    )
}

/**
 * The videos a shows folder holds that the library leaves out, as [SkippedSheet] lists Trakt's: their count as its
 * title, why, then each by its file's name over why it is left out, its folder and where it lies in it, ten at most,
 * the rest counted; then how to fix it, its label set as a name. Taller than the sheet, Down rings the plate and reads
 * it through; else nothing takes focus and Back is the one key.
 */
@Composable
internal fun IgnoredFilesSheet(ignored: List<IgnoredFileRow>, onDismiss: () -> Unit) {
    val c = Hl.colors
    val count = ignored.size
    val one = count == 1
    val scroll = rememberScrollState()
    val overflows = scroll.maxValue > 0
    val lines = remember(ignored) { ignoredLines(ignored) }
    HlSideSheet(pluralStringResource(R.plurals.ignored_files, count, count), onDismiss, scroll = scroll) { _ ->
        Text(
            stringResource(if (one) R.string.ignored_files_text_one else R.string.ignored_files_text),
            style = Hl.type.body,
            color = c.contentSecondary,
        )
        ReadingPlate(Modifier.fillMaxWidth().focusProperties { canFocus = overflows }.readsThrough(scroll)) {
            Column(verticalArrangement = Arrangement.spacedBy(HlSpace.l)) { lines.forEach { IgnoredItem(it) } }
        }
        FixText(
            stringResource(R.string.ignored_files_fix_label),
            stringResource(if (one) R.string.ignored_files_fix_one else R.string.ignored_files_fix),
        )
    }
}

/**
 * [ignored], or while it is empty the last files it listed: an open list keeps them when a scan places them all, until
 * it closes.
 */
@Composable
internal fun lastListed(ignored: List<IgnoredFileRow>): List<IgnoredFileRow> {
    val last = remember { mutableStateOf(ignored) }
    SideEffect { if (ignored.isNotEmpty()) last.value = ignored }
    return ignored.ifEmpty { last.value }
}

/** A line of the list: an ignored [file], or how many [more] there are past those named. */
internal class IgnoredLine(val file: IgnoredFileRow?, val more: Int = 0)

/** The first [MAX_LISTED] files, as the library lists them, then one line counting the rest ("Et 590 autres"). */
internal fun ignoredLines(ignored: List<IgnoredFileRow>): List<IgnoredLine> {
    val shown = ignored.take(MAX_LISTED).map { IgnoredLine(it) }
    val more = ignored.size - shown.size
    return if (more > 0) shown + IgnoredLine(null, more) else shown
}

/** Files the sheet names at most. */
private const val MAX_LISTED = 10

/**
 * A file by its name, over why it is left out, its folder and where it lies in it, spaced as facts: the reason first,
 * so that a line too long cuts the place, not the reason. Else how many more there are.
 */
@Composable
private fun IgnoredItem(line: IgnoredLine) {
    val c = Hl.colors
    val file = line.file
    if (file == null) {
        Text(pluralStringResource(R.plurals.ignored_files_more, line.more, line.more), style = Hl.type.entry, color = c.content)
        return
    }
    val name = file.filePath.substringAfterLast('/')
    val place = file.filePath.substringBeforeLast('/', "").ifEmpty { null }
    val reason = stringResource(if (file.reason == IgnoredReason.NO_SHOW) R.string.ignored_no_show else R.string.ignored_no_number)
    Column {
        Text(name, style = Hl.type.entry, color = c.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(HlSpace.xxs))
        Text(
            listOfNotNull(reason, file.folder, place).joinToString(FACT_GAP),
            style = Hl.type.meta,
            color = c.contentSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
