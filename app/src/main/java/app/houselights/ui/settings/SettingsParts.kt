package app.houselights.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.PaneHead
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.focusGroupKey
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.revealOnFocus
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
