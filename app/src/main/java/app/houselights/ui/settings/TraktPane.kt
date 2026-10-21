package app.houselights.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.TraktSession
import app.houselights.providers.trakt.TraktSyncState
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.DeviceCodePlate
import app.houselights.ui.design.components.HlPlate
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.PaneHead
import app.houselights.ui.design.components.PlateFact
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.util.formatCount
import app.houselights.ui.util.formatDuration

/**
 * Trakt, for the folders, as a state said beside its title: not connected, what it keeps and the row that connects;
 * connecting, its code in the pane, Back cancelling; connected, the account and its last sync, then the rows that sync
 * and disconnect. A build without Trakt's keys says so and offers nothing.
 */
internal fun LazyListScope.traktPane(vm: TraktViewModel, session: TraktSession?) {
    item { Trakt(vm, session) }
}

@Composable
private fun Trakt(vm: TraktViewModel, session: TraktSession?) {
    val live = session?.takeUnless { it.ended }
    val focus = rememberCodeFocus(vm.connecting)
    BackHandler(enabled = vm.connecting) { vm.cancel() }
    // Connected while Trakt could not say the name: it is read once the pane shows.
    val nameless = live != null && live.username == null
    LaunchedEffect(nameless) { if (nameless) vm.readName() }

    Column(Modifier.revealOnFocus()) {
        val title = stringResource(R.string.section_trakt)
        val signedOut = stringResource(R.string.trakt_signed_out)
        when {
            !vm.available -> PaneHead(title, aside = stringResource(R.string.trakt_no_keys), state = StatusState.Off)
            // The code until the row has taken its focus back: focus never leaves the pane.
            vm.connecting || focus.waited -> Waiting(vm, focus, title)
            live != null -> {
                PaneHead(title, aside = stringResource(R.string.trakt_connected), state = StatusState.On)
                Spacer(Modifier.height(HlSpace.l))
                val sync by vm.syncState.collectAsStateWithLifecycle()
                Account(live.username, sync)
            }
            else -> {
                PaneHead(title, aside = signedOut, state = StatusState.Off, read = stringResource(R.string.trakt_desc))
                if (session?.ended == true) {
                    AttentionNote(stringResource(R.string.trakt_ended))
                    Spacer(Modifier.height(HlSpace.l))
                }
            }
        }
        // The rows, but while the code waits; the row asking for it keeps focus until the code has it.
        if (vm.available && (!vm.connecting || focus.asked)) {
            Spacer(Modifier.height(HlSpace.l))
            TraktRows(vm, connected = live != null, Modifier.focusRequester(focus.row)) { focus.asked = true }
        }
    }
}

/** The code to type, its time left and its QR code, under the pane's title waiting for the viewer's approval. */
@Composable
private fun Waiting(vm: TraktViewModel, focus: CodeFocus, title: String) {
    PaneHead(title, aside = stringResource(R.string.trakt_waiting), state = StatusState.Pending)
    Spacer(Modifier.height(HlSpace.l))
    val shown = vm.code
    DeviceCodePlate(
        shown?.userCode,
        stringResource(R.string.trakt_code_desc),
        R.drawable.trakt_qr,
        shown?.address.orEmpty(),
        Modifier.focusRequester(focus.code).onFocusChanged {
            // Left for the places while it waits, it no longer holds focus to give back.
            if (it.isFocused) focus.waited = true else if (vm.connecting) focus.waited = false
        },
        left = shown?.let { stringResource(R.string.trakt_code_left, formatDuration(it.leftMinutesMs)) },
        asking = stringResource(R.string.trakt_asking),
    )
}

/**
 * Focus between the connect [row] and the [code]: the code takes it only when the row [asked] for it, arriving on the
 * pane takes none; while the code holds it ([waited]), the row takes it back as the code goes. Each stays until the
 * other has focus, so focus never falls out of the pane onto the places.
 */
@Stable
private class CodeFocus {
    val row = FocusRequester()
    val code = FocusRequester()
    var asked by mutableStateOf(false)
    var waited by mutableStateOf(false)
}

@Composable
private fun rememberCodeFocus(connecting: Boolean): CodeFocus {
    val focus = remember { CodeFocus() }
    LaunchedEffect(connecting) {
        // Once laid out: the code appears, or the rows come back.
        withFrameNanos { }
        if (connecting && focus.asked) {
            focus.asked = !focus.code.tryRequestFocus()
        } else if (!connecting && focus.waited) {
            focus.row.tryRequestFocus()
            focus.waited = false
        }
    }
    return focus
}

/** The account by its [name], when Trakt said it, and its last sync; beside them, what it holds watched and in progress. */
@Composable
private fun Account(name: String?, sync: TraktSyncState) {
    val c = Hl.colors
    HlPlate(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = HlInset.card, vertical = HlInset.plate)) {
        Row {
            Column(Modifier.weight(1f)) {
                if (name != null) {
                    Text(stringResource(R.string.trakt_account), style = Hl.type.meta, color = c.contentSecondary)
                    Spacer(Modifier.height(HlSpace.xxs))
                    Text(name, style = Hl.type.headline, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(HlSpace.m))
                }
                Row {
                    Text(
                        stringResource(R.string.trakt_last_sync),
                        style = Hl.type.meta,
                        color = c.contentSecondary,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Spacer(Modifier.width(HlSpace.fact))
                    Text(
                        relativeTime(sync.syncedAt ?: 0L, alone = true),
                        style = Hl.type.meta,
                        color = c.content,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            if (sync.syncedAt != null) {
                Spacer(Modifier.width(HlSpace.l))
                Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l)) {
                    PlateFact(stringResource(R.string.trakt_watched), formatCount(sync.watched.toLong()), technical = true)
                    PlateFact(stringResource(R.string.trakt_in_progress), formatCount(sync.inProgress.toLong()), technical = true)
                }
            }
        }
    }
}

/**
 * Sync, then connect or disconnect: one row whose words follow the account, so focus stays on it as the account
 * comes and goes. A failure is said under its row ([RowFailureNote]).
 */
@Composable
private fun TraktRows(vm: TraktViewModel, connected: Boolean, modifier: Modifier, onConnect: () -> Unit) {
    val failure = vm.note?.let { RowFailure(stringResource(R.string.action_not_done), stringResource(it)) }
    HlSettingGroup(null) {
        if (connected) {
            val sync by vm.syncState.collectAsStateWithLifecycle()
            SyncRow(vm, sync)
        }
        HlSettingRow(
            stringResource(if (connected) R.string.trakt_disconnect else R.string.trakt_connect),
            modifier,
            description = stringResource(if (connected) R.string.trakt_disconnect_desc else R.string.trakt_connect_desc),
            value = if (vm.disconnecting) stringResource(R.string.trakt_disconnecting) else failure?.value,
            valueAttention = failure != null && !vm.disconnecting,
            onClick = {
                if (connected) {
                    vm.disconnect()
                } else {
                    onConnect()
                    vm.connect()
                }
            },
        )
        RowFailureNote(failure)
    }
}

/** Syncs now, disabled while a sync runs, which it says; why the last exchange failed is said under it. */
@Composable
private fun SyncRow(vm: TraktViewModel, state: TraktSyncState) {
    val failure = state.error?.takeUnless { state.running }?.let {
        RowFailure(stringResource(R.string.action_not_done), stringResource(noteOf(it)))
    }
    HlSettingRow(
        stringResource(R.string.trakt_sync_now),
        description = stringResource(R.string.trakt_sync_now_desc),
        value = if (state.running) stringResource(R.string.trakt_syncing) else failure?.value,
        valueAttention = failure != null,
        onClick = vm::syncNow,
        enabled = !state.running,
    )
    RowFailureNote(failure)
}
