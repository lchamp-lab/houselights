package app.houselights.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.FileTypes
import app.houselights.core.Formats
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.Tally
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.util.PartFailed
import app.houselights.ui.util.formatBytes
import app.houselights.ui.util.formatCount
import app.houselights.ui.util.formatHours
import app.houselights.ui.util.pictureName

/** Where a row of the library in numbers leads. */
internal sealed interface NumbersDoor {
    data class Wall(val library: Library, val filter: LibraryFilter) : NumbersDoor

    data class Person(val id: String) : NumbersDoor
}

/** A group of rows in the library in numbers, by what they count. */
internal enum class TallyKind(@StringRes val title: Int) {
    PICTURE(R.string.filter_picture),
    SOUND(R.string.filter_sound),
    FILES(R.string.numbers_files),
    DIRECTORS(R.string.label_directors),
}

/** A row: a value by its case name ([label]), its film count (or shows, [shows]), and where OK leads ([door]). */
internal data class TallyRow(val key: String, val label: String, val count: Int, val shows: Boolean, val door: NumbersDoor?)

internal data class TallyGroup(val kind: TallyKind, val rows: List<TallyRow>)

/**
 * The counted library: titles, file size, total hours, then rows by group. What the source does not report is
 * left out, never shown as zero (no [app.houselights.core.ProviderCapabilities.formatNumbers]: no size or formats).
 */
internal data class NumbersPage(
    val films: Int,
    val shows: Int,
    val episodes: Int,
    val sizeBytes: Long?,
    val runtimeMs: Long?,
    val groups: List<TallyGroup>,
)

/** Builds the page from [counted]: a format opens its filtered films (else shows), a director their page. */
internal fun numbersPage(counted: NumbersState.Counted): NumbersPage {
    val n = counted.numbers
    val can = counted.capabilities
    val formats = can.formatNumbers

    fun rows(kind: TallyKind, tallies: List<Tally>, label: (String) -> String) = TallyGroup(
        kind,
        tallies.filter { it.films > 0 || it.shows > 0 }.map { tally ->
            val shows = tally.films == 0
            val wall = counted.walls[if (shows) LibraryKind.SHOWS else LibraryKind.MOVIES]
            val door = when {
                kind == TallyKind.DIRECTORS -> tally.personId?.takeIf { can.people }?.let { NumbersDoor.Person(it) }
                else -> tally.filter?.let { filter -> wall?.let { NumbersDoor.Wall(it, filter) } }
            }
            val count = if (shows) tally.shows else tally.films
            TallyRow("${kind.name}-${tally.personId ?: tally.label}", label(tally.label), count, shows, door)
        },
    )

    val groups = buildList {
        if (formats) {
            add(rows(TallyKind.PICTURE, n.picture, ::pictureName))
            add(rows(TallyKind.SOUND, n.sound, Formats::soundName))
            add(rows(TallyKind.FILES, n.files, FileTypes::label))
        }
        add(rows(TallyKind.DIRECTORS, n.directors) { it })
    }.filter { it.rows.isNotEmpty() }
    return NumbersPage(
        n.films, n.shows, n.episodes,
        sizeBytes = n.sizeBytes?.takeIf { formats && it > 0 },
        runtimeMs = n.runtimeMs?.takeIf { formats && it > 0 },
        groups = groups,
    )
}

/**
 * The library in numbers: three head figures, then rows by group (Image, Son, Fichiers, Réalisation), each
 * opening its films or a director's page. Rows are keyed in [rows], so returning refocuses the row left.
 */
internal fun LazyListScope.numbersPane(
    state: NumbersState,
    rows: PaneRows,
    onOpenWall: (Library, LibraryFilter) -> Unit,
    onOpenPerson: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val open = { door: NumbersDoor ->
        when (door) {
            is NumbersDoor.Wall -> onOpenWall(door.library, door.filter)
            is NumbersDoor.Person -> onOpenPerson(door.id)
        }
    }
    when (state) {
        // Counting is quick: the title shows alone rather than over placeholder figures.
        NumbersState.Counting -> item { Section(stringResource(R.string.numbers_title)) {} }
        is NumbersState.Failed -> item {
            Section(stringResource(R.string.numbers_title)) {
                PartFailed(state.error, state.retrying, onRetry, action = rows.focus(RETRY))
            }
        }
        is NumbersState.Counted -> {
            val page = numbersPage(state)
            // The first group shares the head's item, so scrolling back up shows the figures and title.
            item {
                Section(stringResource(R.string.numbers_title)) {
                    NumbersHead(page)
                    page.groups.firstOrNull()?.let {
                        Spacer(Modifier.height(HlSpace.xl))
                        Tallies(it, rows, open)
                    }
                }
            }
            page.groups.drop(1).forEach { group -> item(key = group.kind.name) { Tallies(group, rows, open, Modifier.revealOnFocus()) } }
        }
    }
}

/** Films (else shows), size and hours, each over what it counts; unreported ones leave their place empty. */
@Composable
private fun NumbersHead(page: NumbersPage) {
    if (page.films == 0 && page.shows == 0) {
        Hint(stringResource(R.string.empty_title))
        return
    }
    val figures = listOfNotNull(
        titlesFigure(page),
        page.sizeBytes?.let { formatBytes(it) to stringResource(R.string.numbers_room) },
        page.runtimeMs?.let { formatHours(it) to stringResource(R.string.numbers_hours) },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HlSpace.l)) {
        figures.forEach { (figure, counts) ->
            Column(Modifier.weight(1f)) {
                // Never wraps away from its noun: short of room (large text), it ends in an ellipsis.
                Text(figure, style = Hl.type.subject, color = Hl.colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(HlSpace.xxs))
                Text(counts, style = Hl.type.caption, color = Hl.colors.contentSecondary)
            }
        }
        repeat(HEAD_FIGURES - figures.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** "412 films" over its shows and episodes (or "in your library"); with shows only, shows over episodes. */
@Composable
private fun titlesFigure(page: NumbersPage): Pair<String, String> {
    val films = pluralStringResource(R.plurals.numbers_films, page.films, formatCount(page.films.toLong()))
    val shows = pluralStringResource(R.plurals.numbers_shows, page.shows, formatCount(page.shows.toLong()))
    val episodes = pluralStringResource(R.plurals.numbers_episodes, page.episodes, formatCount(page.episodes.toLong()))
    return when {
        page.films == 0 -> shows to episodes
        page.shows == 0 -> films to stringResource(R.string.numbers_in_library)
        else -> films to stringResource(R.string.numbers_and, shows, episodes)
    }
}

/** A group's rows: each value by name, how many films (or shows) have it, and where it leads, if anywhere. */
@Composable
private fun Tallies(group: TallyGroup, rows: PaneRows, open: (NumbersDoor) -> Unit, modifier: Modifier = Modifier) {
    HlSettingGroup(stringResource(group.kind.title), modifier) {
        group.rows.forEach { row ->
            val door = row.door
            val count = formatCount(row.count.toLong())
            HlSettingRow(
                row.label,
                if (door != null) rows.focus(row.key) else Modifier,
                value = if (row.shows) {
                    pluralStringResource(R.plurals.numbers_shows, row.count, count)
                } else {
                    pluralStringResource(R.plurals.numbers_films, row.count, count)
                },
                onClick = door?.let { { open(it) } },
            )
        }
    }
}

/** The figures a head has room for. */
private const val HEAD_FIGURES = 3

/** Focus key of Réessayer, shown when the library could not be counted. */
private const val RETRY = "numbers-retry"
