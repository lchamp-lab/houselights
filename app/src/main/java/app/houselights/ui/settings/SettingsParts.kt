package app.houselights.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit
import android.icu.text.RelativeDateTimeFormatter.Direction
import android.icu.text.RelativeDateTimeFormatter.RelativeUnit
import android.icu.util.ULocale
import android.text.format.DateUtils
import app.houselights.R
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.focusGroupKey
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.PaneHead
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.util.UiError

/**
 * A pane's head ([PaneHead]: its title on the screen title's baseline, an [aside] with its [state], a [sentence])
 * and its first block, revealed together so the head shows.
 */
@Composable
internal fun Section(
    title: String,
    aside: String? = null,
    state: StatusState? = null,
    sentence: String? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.revealOnFocus()) {
        PaneHead(title, aside = aside, state = state, sentence = sentence)
        Spacer(Modifier.height(HlSpace.l))
        content()
    }
}

/** A pane's rows keyed under its entry, so returning from a screen a row opened refocuses that row. */
internal class PaneRows(private val memory: FocusMemory, private val pane: String) {
    private fun key(row: String) = FocusKey.within(FocusKey.pane(pane), FocusKey.row(row))

    /** Keys a row that opens another screen; one of a [group]'s plate, gone, gives focus to the plate's first row. */
    fun focus(row: String, group: String? = null): Modifier =
        Modifier.focusKey(if (group != null) FocusKey.within(key(group), FocusKey.row(row)) else key(row), memory)

    /** Keys a plate of rows ([focus]'s group), which takes focus where one of its rows is gone. */
    fun group(group: String): Modifier = Modifier.focusGroupKey(key(group), memory)

    /** Whether [row] of [group] holds focus; read as a change composes, before it removes the row. */
    fun hasFocus(row: String, group: String): Boolean = memory.hasFocus(FocusKey.within(key(group), FocusKey.row(row)))

    /** Focuses [group]'s first row ([group]'s plate). */
    fun focusGroup(group: String): Boolean = memory.focus(key(group))

    /** Focuses that row from within the pane (after a folder is removed). */
    fun requester(row: String): FocusRequester = memory.requester(key(row))
}

/** A row's failed change: [value] highlighted, [reason] in full below ([RowFailureNote]), as descriptions are short. */
internal class RowFailure(val value: String, val reason: String)

/** The [RowFailure] for [error]: not saved (or [notDone] for an action) and why; null without error. */
@Composable
internal fun rowFailure(error: UiError?, notDone: Boolean = false): RowFailure? = error?.let {
    RowFailure(stringResource(if (notDone) R.string.action_not_done else R.string.action_not_saved), stringResource(it.message))
}

/** [failure]'s reason under the row above, brought into view while focus stays on the row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RowFailureNote(failure: RowFailure?) {
    if (failure == null) return
    val reveal = remember { BringIntoViewRequester() }
    LaunchedEffect(failure.reason) { reveal.bringIntoView() }
    AttentionNote(failure.reason, Modifier.padding(horizontal = HlInset.card, vertical = HlSpace.xs).bringIntoViewRequester(reveal))
}

/** How long ago [at] was, relative to now: see the other [relativeTime]. */
@Composable
internal fun relativeTime(at: Long, alone: Boolean = false): String =
    relativeTime(LocalContext.current, at, System.currentTimeMillis(), alone)

/** When a folder was last scanned: "Analysé il y a 3 minutes", "Jamais analysé". */
@Composable
internal fun lastScanned(at: Long): String = lastScanned(LocalContext.current, at, System.currentTimeMillis())

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
