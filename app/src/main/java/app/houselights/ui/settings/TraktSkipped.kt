package app.houselights.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.providers.trakt.SkippedEntry
import app.houselights.providers.trakt.SkippedKind
import app.houselights.providers.trakt.readInPart
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.ReadingPlate
import app.houselights.ui.util.FACT_GAP
import kotlinx.coroutines.launch

/**
 * What the last sync left out unread, in the Trakt pane's rows: not an error, the sync went through, so nothing in
 * ember. OK opens what it was, what it holds back and what to do ([SkippedSheet]).
 */
@Composable
internal fun SkippedRow(skipped: List<SkippedEntry>) {
    var open by remember { mutableStateOf(false) }
    val count = skipped.size
    HlSettingRow(
        pluralStringResource(R.plurals.trakt_skipped, count, count),
        description = pluralStringResource(R.plurals.trakt_skipped_desc, count),
        onClick = { open = true },
    )
    if (open) SkippedSheet(skipped, onDismiss = { open = false })
}

/**
 * The entries a sync left out, each by what Trakt still names of it and where it keeps it, on a plate; then, for a
 * history read in part, the marks it holds back, and how to fix it. Taller than the sheet, Down rings the plate and
 * reads the sheet through; else nothing takes focus and Back is the one key.
 */
@Composable
internal fun SkippedSheet(skipped: List<SkippedEntry>, onDismiss: () -> Unit) {
    val c = Hl.colors
    val count = skipped.size
    val scroll = rememberScrollState()
    val overflows = scroll.maxValue > 0
    val lines = remember(skipped) { skippedLines(skipped) }
    HlSideSheet(pluralStringResource(R.plurals.trakt_skipped, count, count), onDismiss, scroll = scroll) { _ ->
        Text(pluralStringResource(R.plurals.trakt_skipped_text, count), style = Hl.type.body, color = c.contentSecondary)
        ReadingPlate(Modifier.fillMaxWidth().focusProperties { canFocus = overflows }.readsThrough(scroll)) {
            Column(verticalArrangement = Arrangement.spacedBy(HlSpace.l)) { lines.forEach { SkippedItem(it) } }
        }
        heldBack(skipped.readInPart)?.let { Text(stringResource(it), style = Hl.type.meta, color = c.contentSecondary) }
        Fix(count)
    }
}

/** Down and up scroll the sheet a step a press while it has more that way, so all of it is read. */
@Composable
private fun Modifier.readsThrough(scroll: ScrollState): Modifier {
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { HlSize.scrollStep.toPx() }
    return onPreviewKeyEvent { event ->
        val by = when (event.key) {
            Key.DirectionDown -> step
            Key.DirectionUp -> -step
            else -> return@onPreviewKeyEvent false
        }
        val more = if (by > 0) scroll.canScrollForward else scroll.canScrollBackward
        if (more && event.type == KeyEventType.KeyDown) scope.launch { scroll.animateScrollBy(by) }
        more
    }
}

/** A line of the list: an entry Trakt still names, how many of a kind it names nothing of, or how many more there are. */
internal data class SkippedLine(
    val kind: SkippedKind,
    val title: String? = null,
    val year: Int? = null,
    val untitled: Int = 0,
    val more: Int = 0,
)

/**
 * The entries by kind, in the order read, the first [MAX_NAMED] by name. After those of a kind, one line counts the
 * rest of it, those naming nothing included ("Et 590 autres"), else those naming nothing ("1 élément sans titre"): a
 * history Trakt changed the shape of stays a few presses long.
 */
internal fun skippedLines(skipped: List<SkippedEntry>): List<SkippedLine> {
    var room = MAX_NAMED
    return skipped.groupBy { it.kind }.flatMap { (kind, entries) ->
        val (named, unnamed) = entries.partition { it.title != null }
        val shown = named.take(room)
        room -= shown.size
        val rest = when {
            shown.size < named.size -> SkippedLine(kind, more = entries.size - shown.size)
            unnamed.isNotEmpty() -> SkippedLine(kind, untitled = unnamed.size)
            else -> null
        }
        shown.map { SkippedLine(kind, it.title, it.year) } + listOfNotNull(rest)
    }
}

/** Entries the sheet names at most. */
private const val MAX_NAMED = 10

/** What a history read in part holds back: the films or episodes watched here that Trakt seems to lack. */
@StringRes
internal fun heldBack(partial: Set<SkippedKind>): Int? {
    val movies = SkippedKind.MOVIE_HISTORY in partial
    val episodes = SkippedKind.EPISODE_HISTORY in partial
    return when {
        movies && episodes -> R.string.trakt_skipped_partial_both
        movies -> R.string.trakt_skipped_partial_movies
        episodes -> R.string.trakt_skipped_partial_episodes
        else -> null
    }
}

/** An entry by its title and year, else how many more there are or name nothing, over where Trakt keeps it. */
@Composable
private fun SkippedItem(line: SkippedLine) {
    val c = Hl.colors
    val name = when {
        line.title != null -> listOfNotNull(line.title, line.year?.toString()).joinToString(FACT_GAP)
        line.more > 0 -> pluralStringResource(R.plurals.trakt_skipped_more, line.more, line.more)
        else -> pluralStringResource(R.plurals.trakt_skipped_untitled, line.untitled, line.untitled)
    }
    Column {
        Text(name, style = Hl.type.lead, color = c.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(HlSpace.xxs))
        Text(stringResource(kindName(line.kind)), style = Hl.type.meta, color = c.contentSecondary, maxLines = 1)
    }
}

@StringRes
private fun kindName(kind: SkippedKind): Int = when (kind) {
    SkippedKind.MOVIE_HISTORY -> R.string.trakt_skipped_movies
    SkippedKind.EPISODE_HISTORY -> R.string.trakt_skipped_episodes
    SkippedKind.PAUSED -> R.string.trakt_skipped_paused
}

/** How to fix it, its label in white and set as a name ([app.houselights.ui.design.HlType.entry]). */
@Composable
private fun Fix(count: Int) {
    val c = Hl.colors
    val t = Hl.type
    val label = stringResource(R.string.trakt_skipped_fix_label)
    val fix = pluralStringResource(R.plurals.trakt_skipped_fix, count)
    val text = remember(label, fix, c, t) {
        buildAnnotatedString {
            if (fix.startsWith(label)) withStyle(t.entry.toSpanStyle().copy(color = c.content)) { append(label) }
            append(fix.removePrefix(label))
        }
    }
    Text(text, style = t.body, color = c.contentSecondary)
}
