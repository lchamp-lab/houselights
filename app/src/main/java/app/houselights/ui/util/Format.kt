package app.houselights.ui.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.core.HomeSection
import app.houselights.core.LibraryKind
import app.houselights.core.SectionType
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanErrorKind

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
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
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
    SectionType.LATEST -> when {
        section.libraryName != null -> stringResource(R.string.section_latest, section.libraryName)
        section.libraryKind == LibraryKind.SHOWS -> stringResource(R.string.section_latest_shows)
        else -> stringResource(R.string.section_latest_movies)
    }
    SectionType.RECENT -> stringResource(R.string.section_recent)
    SectionType.FAVORITES -> stringResource(R.string.section_favorites)
    SectionType.UNWATCHED -> stringResource(R.string.section_unwatched)
}

/** A scan or connection failure, explained with what to check. */
fun scanErrorText(context: Context, error: ScanError, host: String): String = when (error.kind) {
    ScanErrorKind.UNREACHABLE -> context.getString(R.string.error_nas_unreachable, host)
    ScanErrorKind.ACCESS_DENIED -> context.getString(R.string.error_access_denied)
    ScanErrorKind.LOGIN_FAILED -> context.getString(R.string.error_login_failed)
    ScanErrorKind.NOT_FOUND -> context.getString(R.string.error_not_found)
    ScanErrorKind.OTHER -> context.getString(R.string.error_scan_other, error.detail)
}

@Composable
fun scanErrorText(error: ScanError, host: String): String = scanErrorText(LocalContext.current, error, host)
