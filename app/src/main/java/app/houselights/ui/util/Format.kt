package app.houselights.ui.util

import app.houselights.core.MediaItem
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.core.Definition
import app.houselights.core.HomeSection
import app.houselights.core.Languages
import app.houselights.core.LibraryKind
import app.houselights.core.SectionType
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.SourceKind
import app.houselights.core.Job
import app.houselights.core.Editions

/** No-break space: keeps a number with its unit, and French punctuation with its word. */
const val NBSP = Typography.nbsp

/** "2 h 02", "1 h", "45 min": never split across lines. */
fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "$h${NBSP}h$NBSP${"%02d".format(m)}"
        h > 0 -> "$h${NBSP}h"
        else -> "$m${NBSP}min"
    }
}

/**
 * Between facts inside one piece of text (a button's detail, a card's caption), where a layout
 * gap is not possible: as wide as three spaces, breaking only after it.
 */
const val FACT_GAP = "$NBSP$NBSP "

/**
 * "1 214": a count grouped by thousands as the language groups them, by a no-break space where it groups
 * by a space (Archivo has no narrow one, which French takes).
 */
fun formatCount(count: Long): String =
    java.text.NumberFormat.getIntegerInstance(java.util.Locale.getDefault()).format(count).replace(NARROW_NBSP, NBSP)

private const val NARROW_NBSP = '\u202F'

/** "1 214 h": hours set end to end, whole; under an hour, its minutes ([formatDuration]). */
fun formatHours(context: Context, ms: Long): String {
    val hours = ms / HOUR_MS
    return if (hours == 0L) formatDuration(ms) else context.getString(R.string.hours, formatCount(hours))
}

@Composable
fun formatHours(ms: Long): String = formatHours(LocalContext.current, ms)

private const val HOUR_MS = 3_600_000L

/**
 * A picture format as badges keep it ("4K", "Dolby Vision") as a case names it: a definition by its name
 * ("Ultra HD"), a range as it is.
 */
fun pictureName(token: String): String = Definition.entries.find { it.token == token }?.label ?: token

/** "2,39:1", "1,85:1": the way aspect ratios are written, in the local decimal style. */
fun formatRatio(ratio: Float): String = String.format(java.util.Locale.getDefault(), "%.2f", ratio) + ":1"

/** "3840 × 2160". */
fun formatDimensions(width: Int, height: Int): String = "$width$NBSP×$NBSP$height"

/** A library's name by its kind ("Films", "Séries"): its tab on Home, and its screen's title when it has none of its own. */
@StringRes
fun LibraryKind.titleRes(): Int = when (this) {
    LibraryKind.MOVIES -> R.string.library_movies
    LibraryKind.SHOWS -> R.string.library_shows
    LibraryKind.COLLECTIONS -> R.string.library_collections
    LibraryKind.MIXED -> R.string.library_mixed
}

@Composable
fun libraryName(kind: LibraryKind): String = stringResource(kind.titleRes())

/**
 * A copy of a film by its name: its edition as the screen's language says it ("Version longue"), one
 * no token knows as written, else the name its source gives it, else its place among the copies
 * ([index], from 0: "Copie 2").
 */
@Composable
fun versionName(edition: String?, label: String?, index: Int): String =
    EDITION_NAMES[edition]?.let { stringResource(it) } ?: edition ?: label ?: stringResource(R.string.version_copy, index + 1)

/** The editions the library knows ([Editions]), by the names the screen gives them. */
private val EDITION_NAMES = mapOf(
    Editions.EXTENDED to R.string.edition_extended,
    Editions.DIRECTORS_CUT to R.string.edition_directors_cut,
    Editions.THEATRICAL to R.string.edition_theatrical,
    Editions.UNRATED to R.string.edition_unrated,
    Editions.UNCUT to R.string.edition_uncut,
    Editions.REMASTERED to R.string.edition_remastered,
    Editions.IMAX to R.string.edition_imax,
    Editions.FINAL_CUT to R.string.edition_final_cut,
    Editions.ULTIMATE to R.string.edition_ultimate,
    Editions.SPECIAL to R.string.edition_special,
    Editions.CRITERION to R.string.edition_criterion,
    Editions.ANNIVERSARY to R.string.edition_anniversary,
    Editions.OPEN_MATTE to R.string.edition_open_matte,
)

/** A job as the credits name it: "Réalisation", "Musique"; a show's creators, "Création". */
@Composable
fun jobName(job: Job): String = stringResource(
    when (job) {
        Job.CAST -> R.string.job_cast
        Job.DIRECTOR -> R.string.job_direction
        Job.CREATOR -> R.string.job_creation
        Job.WRITER -> R.string.job_writing
        Job.COMPOSER -> R.string.job_music
        Job.CINEMATOGRAPHY -> R.string.job_cinematography
        Job.EDITING -> R.string.job_editing
    }
)

/** What a network folder holds, by the name of the library it fills: "Films" or "Séries". */
@Composable
fun sourceKindName(kind: SourceKind): String = stringResource(
    when (kind) {
        SourceKind.MOVIES -> R.string.library_movies
        SourceKind.SHOWS -> R.string.library_shows
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
    SectionType.DIRECTOR -> listOfNotNull(stringResource(R.string.row_films_of), section.person?.name).joinToString(" ")
    SectionType.BEST_RATED -> stringResource(R.string.row_best_rated)
}

/** A scan or connection failure, explained with what to check. */
fun scanErrorText(context: Context, error: ScanError, host: String): String = when (error.kind) {
    ScanErrorKind.UNREACHABLE -> context.getString(R.string.error_nas_unreachable, host)
    ScanErrorKind.ACCESS_DENIED -> context.getString(R.string.error_access_denied)
    ScanErrorKind.LOGIN_FAILED -> context.getString(R.string.error_login_failed)
    ScanErrorKind.NOT_FOUND -> context.getString(R.string.error_not_found)
    ScanErrorKind.EMPTY -> context.getString(R.string.error_folder_empty)
    ScanErrorKind.STORAGE_FULL -> context.getString(R.string.error_storage_full)
    ScanErrorKind.OTHER -> context.getString(R.string.error_scan_other)
}

@Composable
fun scanErrorText(error: ScanError, host: String): String = scanErrorText(LocalContext.current, error, host)

/**
 * "eng", "en-US" or "fre" as a language name in the interface language ("Anglais", "Français"), by the
 * code the sheet tells tracks apart by ([Languages.code]); a code no language answers to as it is.
 */
fun languageName(code: String?): String? {
    val tag = Languages.code(code) ?: return null
    val name = java.util.Locale.forLanguageTag(tag).getDisplayLanguage(java.util.Locale.getDefault())
    return name.takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) }
        ?.replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) }
        ?: code
}

/** Decimal units, as drives and NAS report them; "To", "Go", "Mo", "Ko" in French. */
fun formatBytes(context: Context, bytes: Long): String {
    // Whole amounts lose their decimals: "1 Go", not "1,00 Go".
    fun amount(value: Double, decimals: Int): String {
        val locale = java.util.Locale.getDefault()
        val rounded = String.format(locale, "%.${decimals}f", value)
        return if (String.format(locale, "%.${decimals}f", Math.rint(value)) == rounded) String.format(locale, "%.0f", value) else rounded
    }
    return when {
        // A library's room, to a tenth: "38,4 To".
        bytes >= TERABYTE -> context.getString(R.string.terabytes, amount(bytes / TERABYTE.toDouble(), 1))
        bytes >= 1_000_000_000L -> context.getString(R.string.gigabytes, amount(bytes / 1e9, 2))
        bytes >= 1_000_000L -> context.getString(R.string.megabytes, amount(bytes / 1e6, 1))
        else -> context.getString(R.string.kilobytes, amount(bytes / 1e3, 0))
    }
}

private const val TERABYTE = 1_000_000_000_000L

@Composable
fun formatBytes(bytes: Long): String = formatBytes(LocalContext.current, bytes)

/**
 * "Il reste 1 h 26" for a title started: a duration, never a timecode, never "0 min" (a minute at
 * least); nothing when it is not started or its length is unknown.
 */
@Composable
fun timeLeft(item: MediaItem): String? {
    val left = (item.runtimeMs ?: 0L) - item.user.positionMs
    return if (item.user.positionMs > 0 && left > 0) stringResource(R.string.time_left, formatDuration(maxOf(left, MINUTE_MS))) else null
}

private const val MINUTE_MS = 60_000L
