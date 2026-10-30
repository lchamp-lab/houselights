package app.houselights.ui.util

import android.content.Context
import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit
import android.icu.text.RelativeDateTimeFormatter.Direction
import android.icu.text.RelativeDateTimeFormatter.RelativeUnit
import android.icu.util.ULocale
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.core.Definition
import app.houselights.core.Editions
import app.houselights.core.HomeSection
import app.houselights.core.Job
import app.houselights.core.Languages
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.SectionType
import app.houselights.providers.local.Folder
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.ScanErrors
import app.houselights.providers.local.SourceKind
import kotlinx.coroutines.delay

/** No-break space: keeps a number with its unit, and French punctuation with its word. */
const val NBSP = Typography.nbsp

/** "2 h 02", "1 h", "45 min": never split across lines. */
fun formatDuration(ms: Long): String {
    val totalMin = ms / MS_PER_MINUTE
    val h = totalMin / MINUTES_PER_HOUR
    val m = totalMin % MINUTES_PER_HOUR
    return when {
        h > 0 && m > 0 -> "$h${NBSP}h$NBSP${"%02d".format(m)}"
        h > 0 -> "$h${NBSP}h"
        else -> "$m${NBSP}min"
    }
}

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60

/** Gap between facts inside one text, where a layout gap is impossible: three spaces wide, breaking after. */
const val FACT_GAP = "$NBSP$NBSP "

/**
 * [path] kept whole inside a sentence ("/volume1/photo"): a word joiner after each slash, where a line would otherwise
 * break, so a line breaks before the path rather than inside it.
 */
fun wholePath(path: String): String = path.replace("/", "/$WORD_JOINER")

private const val WORD_JOINER = '\u2060'

/** "1 214": thousands grouped as the locale does, with a no-break space for the narrow one Archivo lacks. */
fun formatCount(count: Long): String =
    java.text.NumberFormat.getIntegerInstance(java.util.Locale.getDefault()).format(count).replace(NARROW_NBSP, NBSP)

private const val NARROW_NBSP = '\u202F'

/** A scan's count in the Counter's notation, its thousands grouped ([formatCount]): "1 204 / 3 871". */
@Composable
fun scanCount(processed: Int, total: Int): String =
    stringResource(R.string.scan_progress, formatCount(processed.toLong()), formatCount(total.toLong()))

/** How many films and shows, its thousands grouped ([formatCount]): "1 240 titres". */
@Composable
fun titleCount(count: Int): String = pluralStringResource(R.plurals.title_count, count, formatCount(count.toLong()))

/** "1 214 h": total whole hours; under an hour, its minutes ([formatDuration]). */
fun formatHours(context: Context, ms: Long): String {
    val hours = ms / HOUR_MS
    return if (hours == 0L) formatDuration(ms) else context.getString(R.string.hours, formatCount(hours))
}

@Composable
fun formatHours(ms: Long): String = formatHours(LocalContext.current, ms)

private const val HOUR_MS = 3_600_000L

/** A picture format token as a disc case names it: a definition by name ("Ultra HD"), a range as is. */
fun pictureName(token: String): String = Definition.entries.find { it.token == token }?.label ?: token

/** "2,39:1", "1,85:1": the way aspect ratios are written, in the local decimal style. */
fun formatRatio(ratio: Float): String = String.format(java.util.Locale.getDefault(), "%.2f", ratio) + ":1"

/** "3840 × 2160". */
fun formatDimensions(width: Int, height: Int): String = "$width$NBSP×$NBSP$height"

/** A library's name by kind ("Films", "Séries"): its Home tab, and its title when it has none of its own. */
@StringRes
fun LibraryKind.titleRes(): Int = when (this) {
    LibraryKind.MOVIES -> R.string.library_movies
    LibraryKind.SHOWS -> R.string.library_shows
    LibraryKind.COLLECTIONS -> R.string.library_collections
    LibraryKind.MIXED -> R.string.library_mixed
}

@Composable
fun libraryName(kind: LibraryKind): String = stringResource(kind.titleRes())

/** A copy's name: its edition (translated if known), else its source's label, else [index] from 0 ("Copie 2"). */
@Composable
fun versionName(edition: String?, label: String?, index: Int): String =
    EDITION_NAMES[edition]?.let { stringResource(it) } ?: edition ?: label ?: stringResource(R.string.version_copy, index + 1)

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
    },
)

/** What a network folder holds, by the name of the library it fills: "Films" or "Séries". */
@Composable
fun sourceKindName(kind: SourceKind): String = stringResource(
    when (kind) {
        SourceKind.MOVIES -> R.string.library_movies
        SourceKind.SHOWS -> R.string.library_shows
    },
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

/**
 * [folder]'s scan failure, explained with what to check. A folder gone from a NAS that answered
 * ([ScanErrors.isFolderGone]) was found when it was added, so is gone rather than misspelled, its titles held.
 */
fun scanErrorText(context: Context, error: ScanError, folder: Folder): String = when (error.kind) {
    ScanErrorKind.UNREACHABLE -> context.getString(R.string.error_nas_unreachable, folder.host)
    ScanErrorKind.ACCESS_DENIED -> context.getString(R.string.error_access_denied)
    ScanErrorKind.LOGIN_FAILED -> context.getString(R.string.error_login_failed)
    ScanErrorKind.PASSWORD_UNREAD -> context.getString(R.string.error_scan_password_unread)
    ScanErrorKind.NOT_FOUND ->
        context.getString(if (ScanErrors.isFolderGone(folder.protocol, error)) R.string.error_folder_gone else R.string.error_not_found)
    ScanErrorKind.EMPTY -> context.getString(R.string.error_folder_empty)
    ScanErrorKind.STORAGE_FULL -> context.getString(R.string.error_storage_full)
    ScanErrorKind.OTHER -> context.getString(R.string.error_scan_other)
}

@Composable
fun scanErrorText(error: ScanError, folder: Folder): String = scanErrorText(LocalContext.current, error, folder)

/** "eng", "en-US" or "fre" as a name in the interface language ("Anglais"); an unknown code as is. */
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
        bytes >= GIGABYTE -> context.getString(R.string.gigabytes, amount(bytes / GIGABYTE.toDouble(), 2))
        bytes >= MEGABYTE -> context.getString(R.string.megabytes, amount(bytes / MEGABYTE.toDouble(), 1))
        else -> context.getString(R.string.kilobytes, amount(bytes / KILOBYTE.toDouble(), 0))
    }
}

private const val TERABYTE = 1_000_000_000_000L
private const val GIGABYTE = 1_000_000_000L
private const val MEGABYTE = 1_000_000L
private const val KILOBYTE = 1_000L

@Composable
fun formatBytes(bytes: Long): String = formatBytes(LocalContext.current, bytes)

/** "Il reste 1 h 26" for a started title, at least a minute; null when unstarted or of unknown length. */
@Composable
fun timeLeft(item: MediaItem): String? {
    val left = (item.runtimeMs ?: 0L) - item.user.positionMs
    return if (item.user.positionMs > 0 && left > 0) stringResource(R.string.time_left, formatDuration(maxOf(left, MINUTE_MS))) else null
}

private const val MINUTE_MS = 60_000L

/**
 * "Introuvable sur le NAS depuis 14 h": how long the file of a title held ([MediaItem.missingSince]) has been missing,
 * rounded down to the hour, in minutes under one ([formatHours]).
 */
@Composable
fun heldSince(since: Long): String =
    stringResource(R.string.held_since, formatHours((System.currentTimeMillis() - since).coerceAtLeast(MINUTE_MS)))

/**
 * Now, by the device's [clock], read again every [tickMs] while shown: a time said relative to it moves on with it, on a
 * screen left open (« Analysé il y a 2 minutes », « Vérifié il y a 2 h »).
 */
@Composable
internal fun rememberNow(tickMs: Long = NOW_TICK_MS, clock: () -> Long = System::currentTimeMillis): Long {
    var now by remember { mutableLongStateOf(clock()) }
    val read by rememberUpdatedState(clock)
    LaunchedEffect(tickMs) {
        while (true) {
            delay(tickMs)
            now = read()
        }
    }
    return now
}

/** How often [rememberNow] reads the clock again: a time said to the minute is never more than half a minute late. */
internal const val NOW_TICK_MS = 30_000L

/** How long ago [at] was, relative to [now], which moves on while shown: see the other [relativeTime]. */
@Composable
internal fun relativeTime(at: Long, alone: Boolean = false, now: Long = rememberNow()): String =
    relativeTime(LocalContext.current, at, now, alone)

/** When a folder was last scanned, relative to [now], which moves on while shown: "Analysé il y a 3 minutes", "Jamais analysé". */
@Composable
internal fun lastScanned(at: Long, now: Long = rememberNow()): String = lastScanned(LocalContext.current, at, now)

internal fun lastScanned(context: Context, at: Long, now: Long): String =
    if (at <= 0) context.getString(R.string.never_scanned) else context.getString(R.string.last_scan, relativeTime(context, at, now))

/**
 * How long ago [at] was, for mid-sentence ("il y a 3 minutes", "hier"), capitalised with [alone], its unit
 * abbreviated when [short] ("il y a 2 h"). The system's casing is not used: it capitalises wherever the words fall
 * ("Analyse : Il y a…").
 */
internal fun relativeTime(context: Context, at: Long, now: Long, alone: Boolean = false, short: Boolean = false): String {
    val locale = context.resources.configuration.locales[0]
    val elapsed = now - at
    val text = when {
        at <= 0 -> context.getString(R.string.never)
        elapsed < DateUtils.MINUTE_IN_MILLIS -> context.getString(R.string.just_now)
        else -> {
            val words = RelativeDateTimeFormatter.getInstance(
                ULocale.forLocale(locale),
                null,
                if (short) RelativeDateTimeFormatter.Style.SHORT else RelativeDateTimeFormatter.Style.LONG,
                DisplayContext.CAPITALIZATION_FOR_MIDDLE_OF_SENTENCE,
            )
            val (count, unit) = when {
                elapsed < DateUtils.HOUR_IN_MILLIS -> elapsed / DateUtils.MINUTE_IN_MILLIS to RelativeUnit.MINUTES
                elapsed < DateUtils.DAY_IN_MILLIS -> elapsed / DateUtils.HOUR_IN_MILLIS to RelativeUnit.HOURS
                elapsed < DateUtils.WEEK_IN_MILLIS -> elapsed / DateUtils.DAY_IN_MILLIS to RelativeUnit.DAYS
                elapsed < MONTH_IN_MILLIS -> elapsed / DateUtils.WEEK_IN_MILLIS to RelativeUnit.WEEKS
                elapsed < YEAR_IN_MILLIS -> elapsed / MONTH_IN_MILLIS to RelativeUnit.MONTHS
                else -> elapsed / YEAR_IN_MILLIS to RelativeUnit.YEARS
            }
            if (count == 1L && unit == RelativeUnit.DAYS) {
                words.format(Direction.LAST, AbsoluteUnit.DAY)
            } else {
                words.format(count.toDouble(), Direction.LAST, unit)
            }
        }
    }
    return if (alone) text.replaceFirstChar { it.titlecase(locale) } else text
}

private const val MONTH_IN_MILLIS = 30 * DateUtils.DAY_IN_MILLIS

/** A year of 365 days: DateUtils.YEAR_IN_MILLIS, deprecated, counts 52 weeks (364 days). */
private const val YEAR_IN_MILLIS = 365 * DateUtils.DAY_IN_MILLIS
