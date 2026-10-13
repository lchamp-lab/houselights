package app.houselights.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.core.HomeSection
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.SectionType
import java.util.Locale

fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "$h h ${"%02d".format(m)}"
        h > 0 -> "$h h"
        else -> "$m min"
    }
}

fun formatTimecode(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

/** Year · runtime (or seasons) · rating · score. */
@Composable
fun metaLine(item: MediaItem): List<String> {
    val seasons = item.childCount?.takeIf { item.kind == MediaKind.SERIES && it > 0 }
        ?.let { pluralStringResource(R.plurals.season_count, it, it) }
    return buildList {
        if (item.kind == MediaKind.SERIES) {
            val start = item.year
            val end = item.endYear
            when {
                start != null && item.continuing -> add("$start –")
                start != null && end != null && end != start -> add("$start – $end")
                start != null -> add("$start")
            }
            seasons?.let(::add)
        } else {
            item.year?.let { add("$it") }
            item.runtimeMs?.let { add(formatDuration(it)) }
        }
        item.officialRating?.let { add(it) }
        item.communityRating?.let { add("★ " + String.format(Locale.getDefault(), "%.1f", it)) }
    }
}

@Composable
fun libraryName(kind: LibraryKind): String = stringResource(
    when (kind) {
        LibraryKind.MOVIES -> R.string.library_movies
        LibraryKind.SHOWS -> R.string.library_shows
        LibraryKind.COLLECTIONS -> R.string.library_collections
        LibraryKind.MIXED -> R.string.library_mixed
    }
)

@Composable
fun sectionTitle(section: HomeSection): String = when (section.type) {
    SectionType.CONTINUE -> stringResource(R.string.section_continue)
    SectionType.NEXT_UP -> stringResource(R.string.section_next_up)
    SectionType.LATEST -> stringResource(
        R.string.section_latest,
        section.libraryName ?: section.libraryKind?.let { libraryName(it) } ?: "",
    )
    SectionType.RECENT -> stringResource(R.string.section_recent)
    SectionType.FAVORITES -> stringResource(R.string.section_favorites)
    SectionType.UNWATCHED -> stringResource(R.string.section_unwatched)
}
