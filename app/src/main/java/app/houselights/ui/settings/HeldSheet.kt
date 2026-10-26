package app.houselights.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.Typeset
import app.houselights.providers.local.Folder
import app.houselights.providers.local.HeldRow
import app.houselights.providers.local.ItemKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.ReadingPlate
import app.houselights.ui.util.FACT_GAP

// Titles not found (#70): many files of a folder gone at once while it answers are held, shown, and asked about once.

/** A folder's titles held while their files are missing ([HeldRow]): its [folder] and its [rows], in title order. */
@Immutable
data class HeldTitles(val folder: Folder, val rows: List<HeldRow>) {
    /** The rows the viewer is shown, which an answer is about. */
    val ids: List<Long> get() = rows.map { it.id }

    /** The folder's name as its showcase and its sheet write it. */
    val name: String get() = folder.name.replaceFirstChar { it.uppercase() }

    /** Whether the user kept them (« Garder »): nothing asks about them again. */
    val kept: Boolean get() = folder.heldKept
}

/** What the user answers for a folder's titles held: « Garder » ([keep]) or « Retirer » ([remove]). */
class HeldAnswers(val keep: (HeldTitles) -> Unit, val remove: (HeldTitles) -> Unit)

/** Each folder's titles held, in the folders' order; none for a folder that holds none. */
fun heldByFolder(rows: List<HeldRow>, folders: List<Folder>): List<HeldTitles> {
    val byFolder = rows.groupBy { it.sourceId }
    return folders.mapNotNull { folder -> byFolder[folder.id]?.let { HeldTitles(folder, it) } }
}

/**
 * The sheet of a folder's titles held ([held]): how many and where, what the app sees and what to do, then the two
 * answers above the list, « Garder » first and focused, as nothing leaves with it ([onKeep]), « Retirer » saying in a
 * line that nothing is deleted ([onRemove], no second question); then the titles on a reading plate, each over its
 * file, ten at most and how many more. Never ember: nothing may have failed. Back closes it without an answer; Down
 * past the answers rings the plate and reads the sheet through when it is taller than the screen.
 */
@Composable
internal fun HeldSheet(held: HeldTitles, onKeep: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val c = Hl.colors
    val count = held.rows.size
    val scroll = rememberScrollState()
    val overflows = scroll.maxValue > 0
    val keep = remember { FocusRequester() }
    val lines = remember(held.rows) { heldLines(held.rows) }
    HlSideSheet(
        pluralStringResource(R.plurals.held_titles, count, count),
        onDismiss,
        facts = listOf(held.name),
        badges = listOf(held.folder.protocol.name),
        initialFocus = keep,
        scroll = scroll,
    ) { sheet ->
        val one = count == 1
        val text = stringResource(if (one) R.string.held_text_one else R.string.held_text, held.name)
        Text(text, style = Hl.type.body, color = c.contentSecondary)
        HlSettingGroup(null) {
            HlSettingRow(
                stringResource(R.string.held_keep),
                Modifier.focusRequester(keep),
                description = stringResource(if (one) R.string.held_keep_desc_one else R.string.held_keep_desc),
                onClick = { sheet.dismiss(onKeep) },
            )
            HlSettingRow(
                stringResource(R.string.remove),
                description = stringResource(R.string.held_remove_desc),
                onClick = { sheet.dismiss(onRemove) },
            )
        }
        ReadingPlate(Modifier.fillMaxWidth().focusProperties { canFocus = overflows }.readsThrough(scroll)) {
            Column(verticalArrangement = Arrangement.spacedBy(HlSpace.l)) { lines.forEach { HeldItem(it) } }
        }
    }
}

/** A line of the list: a film by its title and year, an episode by its show and number, over its file; or how many more. */
internal data class HeldLine(
    val title: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val path: String? = null,
    val more: Int = 0,
)

/** The first [MAX_HELD] titles, in the order given, then how many more: the sheet is to decide, not to list them all. */
internal fun heldLines(rows: List<HeldRow>): List<HeldLine> {
    val shown = rows.take(MAX_HELD).map { row ->
        val episode = row.kind == ItemKind.EPISODE && row.showTitle != null
        HeldLine(
            title = Typeset.text(if (episode) row.showTitle.orEmpty() else row.title),
            year = row.year.takeUnless { episode },
            season = row.seasonNumber.takeIf { episode },
            episode = row.episodeNumber.takeIf { episode },
            path = row.filePath,
        )
    }
    val more = rows.size - shown.size
    return shown + listOfNotNull(HeldLine(more = more).takeIf { more > 0 })
}

/** Titles the sheet names at most, as the entries a Trakt sync left out ([skippedLines]). */
private const val MAX_HELD = 10

/** A title and its year, or a show and its episode, in white, over its file as the NAS names it; else how many more. */
@Composable
private fun HeldItem(line: HeldLine) {
    val c = Hl.colors
    val title = line.title
    if (title == null) {
        Text(pluralStringResource(R.plurals.held_more, line.more, line.more), style = Hl.type.entry, color = c.content)
        return
    }
    val number = line.episode?.let { stringResource(R.string.home_episode, line.season ?: 0, it.toString()) }
    Column {
        Text(
            listOfNotNull(title, line.year?.toString(), number).joinToString(FACT_GAP),
            style = Hl.type.entry,
            color = c.content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        line.path?.let {
            Spacer(Modifier.height(HlSpace.xxs))
            Text(it, style = Hl.type.meta, color = c.contentSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
