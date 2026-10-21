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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    // The connect or disconnect row, which focus comes back to when the code goes; the code holds it meanwhile.
    val row = remember { FocusRequester() }
    val code = remember { FocusRequester() }
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(vm.connecting) {
        // Once laid out: the code appears, or the rows come back.
        withFrameNanos { }
        if (vm.connecting) {
            waited = true
            code.tryRequestFocus()
        } else if (waited) {
            waited = false
            row.tryRequestFocus()
        }
    }
    BackHandler(enabled = vm.connecting) { vm.cancel() }
    // Connected while Trakt could not say the name: it is read once the pane shows.
    val nameless = live != null && live.username == null
    LaunchedEffect(nameless) { if (nameless) vm.readName() }

    Column(Modifier.revealOnFocus()) {
        val title = stringResource(R.string.section_trakt)
        val signedOut = stringResource(R.string.trakt_signed_out)
        when {
            !vm.available -> PaneHead(title, aside = stringResource(R.string.trakt_no_keys), state = StatusState.Off)
            vm.connecting -> {
                PaneHead(title, aside = stringResource(R.string.trakt_waiting), state = StatusState.Pending)
                Spacer(Modifier.height(HlSpace.l))
                val shown = vm.code
                DeviceCodePlate(
                    shown?.userCode,
                    stringResource(R.string.trakt_code_desc),
                    R.drawable.trakt_qr,
                    shown?.address.orEmpty(),
                    Modifier.focusRequester(code),
                    left = shown?.let { stringResource(R.string.trakt_code_left, formatDuration(it.leftMinutesMs)) },
                    asking = stringResource(R.string.trakt_asking),
                )
                return@Column
            }
            live != null -> {
                PaneHead(title, aside = stringResource(R.string.trakt_connected), state = StatusState.On)
                Spacer(Modifier.height(HlSpace.l))
                val sync by vm.syncState.collectAsStateWithLifecycle()
                Account(live.username, sync)
            }
            else -> {
                PaneHead(title, aside = signedOut, state = StatusState.Off, read = stringResource(R.string.trakt_desc))
                if (session?.ended == true) {
                    AttentionNote(stringResource(R.string.trakt_ended), style = Hl.type.meta)
                    Spacer(Modifier.height(HlSpace.l))
                }
            }
        }
        if (vm.available) {
            Spacer(Modifier.height(HlSpace.l))
            TraktRows(vm, connected = live != null, Modifier.focusRequester(row))
        }
    }
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
private fun TraktRows(vm: TraktViewModel, connected: Boolean, modifier: Modifier) {
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
            onClick = { if (connected) vm.disconnect() else vm.connect() },
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
