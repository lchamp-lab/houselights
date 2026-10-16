package app.houselights.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.Text
import android.content.Context
import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit
import android.icu.text.RelativeDateTimeFormatter.Direction
import android.icu.text.RelativeDateTimeFormatter.RelativeUnit
import android.icu.util.ULocale
import android.text.format.DateUtils
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.util.UiError

/**
 * First group of a pane: the space aligning it with the navigation, its title, in the voice of the
 * poster like the subject of a sheet, and its first settings, revealed together so going back up
 * always shows the title.
 */
@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.revealOnFocus()) {
        Spacer(Modifier.height(HlLayout.paneTop))
        Text(title, style = Hl.type.subject, color = Hl.colors.content)
        Spacer(Modifier.height(HlSpace.l))
        content()
    }
}

/**
 * The rows of a pane that focus comes back to, keyed in the screen's memory under the pane's entry:
 * coming back from the screen a row opened finds that row, else the entry.
 */
internal class PaneRows(private val memory: FocusMemory, private val pane: String) {
    private fun key(row: String) = FocusKey.within(FocusKey.pane(pane), FocusKey.row(row))

    /** Names a row that opens another screen. */
    fun focus(row: String): Modifier = Modifier.focusKey(key(row), memory)

    /** Focuses that row from the pane itself (after a folder is removed). */
    fun requester(row: String): FocusRequester = memory.requester(key(row))
}

/** What a row says once the change made from it did not take: in its value, lit; why, in its description. */
internal class RowFailure(val value: String, val description: String)

/**
 * What a row says of [error], the failure of the change made from it, where focus is: not saved (or,
 * for an action, [notDone]), and why. Null when the change took.
 */
@Composable
internal fun rowFailure(error: UiError?, notDone: Boolean = false): RowFailure? = error?.let {
    RowFailure(stringResource(if (notDone) R.string.action_not_done else R.string.action_not_saved), stringResource(it.message))
}

/** A line of explanation under a group, in the reading measure. */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Hl.type.caption, color = Hl.colors.contentSecondary, modifier = modifier.widthIn(max = HlLayout.captionMeasure))
}

/** When something happened, in words: see the other [relativeTime]. */
@Composable
internal fun relativeTime(at: Long, alone: Boolean = false): String =
    relativeTime(LocalContext.current, at, System.currentTimeMillis(), alone)

/** "Analysé il y a 3 minutes", "Jamais analysé": when a folder was last read. */
@Composable
internal fun lastScanned(at: Long): String = lastScanned(LocalContext.current, at, System.currentTimeMillis())

internal fun lastScanned(context: Context, at: Long, now: Long): String =
    if (at <= 0) context.getString(R.string.never_scanned) else context.getString(R.string.last_scan, relativeTime(context, at, now))

/**
 * When something happened, in words, as it reads inside a sentence: "il y a 3 minutes", "hier",
 * "à l’instant", "jamais". Set [alone], as a value by itself, it takes a capital: "Il y a 3 minutes".
 * Never the system's casing, which capitalises wherever the words fall ("Analyse : Il y a…").
 */
internal fun relativeTime(context: Context, at: Long, now: Long, alone: Boolean = false): String {
    val locale = context.resources.configuration.locales[0]
    val elapsed = now - at
    val text = when {
        at <= 0 -> context.getString(R.string.never)
        elapsed < DateUtils.MINUTE_IN_MILLIS -> context.getString(R.string.just_now)
        else -> {
            val words = RelativeDateTimeFormatter.getInstance(
                ULocale.forLocale(locale),
                null,
                RelativeDateTimeFormatter.Style.LONG,
                DisplayContext.CAPITALIZATION_FOR_MIDDLE_OF_SENTENCE,
            )
            val (count, unit) = when {
                elapsed < DateUtils.HOUR_IN_MILLIS -> elapsed / DateUtils.MINUTE_IN_MILLIS to RelativeUnit.MINUTES
                elapsed < DateUtils.DAY_IN_MILLIS -> elapsed / DateUtils.HOUR_IN_MILLIS to RelativeUnit.HOURS
                elapsed < DateUtils.WEEK_IN_MILLIS -> elapsed / DateUtils.DAY_IN_MILLIS to RelativeUnit.DAYS
                elapsed < MONTH_IN_MILLIS -> elapsed / DateUtils.WEEK_IN_MILLIS to RelativeUnit.WEEKS
                elapsed < DateUtils.YEAR_IN_MILLIS -> elapsed / MONTH_IN_MILLIS to RelativeUnit.MONTHS
                else -> elapsed / DateUtils.YEAR_IN_MILLIS to RelativeUnit.YEARS
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
