package app.houselights.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.TraktSession
import app.houselights.providers.trakt.TraktSyncState
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.DefinitionRow
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.QrCode
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.formatDuration

/**
 * Trakt, for the folders: what it keeps, the account's state and its last sync, then the rows that sync it and
 * connect it (its code in a side sheet) or disconnect it. A build without Trakt's keys says so and offers nothing.
 */
internal fun LazyListScope.traktPane(vm: TraktViewModel, session: TraktSession?) {
    item { Section(stringResource(R.string.section_trakt)) { TraktState(vm, session) } }
    if (vm.available) item { TraktRow(vm, session) }
}

/** The account connected, by its name, and its last sync; none; the connection Trakt ended; or no keys in this build. */
@Composable
private fun TraktState(vm: TraktViewModel, session: TraktSession?) {
    val live = session?.takeUnless { it.ended }
    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.m)) {
        Hint(stringResource(R.string.trakt_desc))
        when {
            !vm.available -> HlStatus(StatusState.Off, stringResource(R.string.trakt_no_keys))
            live != null -> {
                HlStatus(
                    StatusState.On,
                    live.username?.let { stringResource(R.string.trakt_signed_in_as, it) } ?: stringResource(R.string.trakt_connected),
                )
                val sync by vm.syncState.collectAsStateWithLifecycle()
                LastSync(sync)
            }
            session?.ended == true -> AttentionNote(stringResource(R.string.trakt_ended))
            else -> HlStatus(StatusState.Off, stringResource(R.string.trakt_signed_out))
        }
    }
    // Connected while Trakt could not say the name: it is read once the pane shows.
    val nameless = live != null && live.username == null
    LaunchedEffect(nameless) { if (nameless) vm.readName() }
}

/** When Trakt and the library last agreed, and how many titles they share watched and in progress. */
@Composable
private fun LastSync(state: TraktSyncState) {
    val watched = pluralStringResource(R.plurals.trakt_watched_count, state.watched, state.watched).takeIf { state.watched > 0 }
    val started = pluralStringResource(R.plurals.trakt_in_progress_count, state.inProgress, state.inProgress)
        .takeIf { state.inProgress > 0 }
    val counts = when {
        watched != null && started != null -> stringResource(R.string.trakt_counts, watched, started)
        else -> watched ?: started
    }
    val at = state.syncedAt
    DefinitionRow(stringResource(R.string.trakt_last_sync)) {
        Text(
            listOfNotNull(relativeTime(at ?: 0L, alone = true), counts.takeIf { at != null }).joinToString(FACT_GAP),
            style = Hl.type.meta,
            color = Hl.colors.content,
        )
    }
}

/**
 * Sync, then connect or disconnect: one row whose words follow the account, so focus stays on it as the account
 * comes and goes. A failure is said under its row ([RowFailureNote]).
 */
@Composable
private fun TraktRow(vm: TraktViewModel, session: TraktSession?) {
    val connected = session != null && !session.ended
    // Shown again over a connection that outlived the screen.
    var showing by remember { mutableStateOf(vm.connecting) }
    val failure = vm.note?.let { RowFailure(stringResource(R.string.action_not_done), stringResource(it)) }
    HlSettingGroup(null, Modifier.revealOnFocus()) {
        if (connected) {
            val sync by vm.syncState.collectAsStateWithLifecycle()
            SyncRow(vm, sync)
        }
        HlSettingRow(
            stringResource(if (connected) R.string.trakt_disconnect else R.string.trakt_connect),
            description = stringResource(if (connected) R.string.trakt_disconnect_desc else R.string.trakt_connect_desc),
            value = if (vm.disconnecting) stringResource(R.string.trakt_disconnecting) else failure?.value,
            valueAttention = failure != null && !vm.disconnecting,
            onClick = {
                if (connected) vm.disconnect() else if (vm.connect()) showing = true
            },
            // Connecting opens the code's sheet; disconnecting happens here.
            chevron = !connected,
        )
        RowFailureNote(failure)
    }
    if (showing) {
        TraktCodeSheet(
            vm,
            onDismiss = {
                showing = false
                vm.cancel()
            },
        )
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
        chevron = false,
        enabled = !state.running,
    )
    RowFailureNote(failure)
}

/**
 * The code to enter on Trakt's page, beside the QR code that opens it, with the time it has left. Nothing in it
 * takes focus: Back cancels; approval, a failure or the code's end close it, and the pane says how it went.
 */
@Composable
private fun TraktCodeSheet(vm: TraktViewModel, onDismiss: () -> Unit) {
    HlSideSheet(stringResource(R.string.trakt_connect), onDismiss, description = stringResource(R.string.trakt_code_desc)) { sheet ->
        LaunchedEffect(vm.connecting) { if (!vm.connecting) sheet.dismiss() }
        val code = vm.code
        if (code == null) {
            HlStatus(StatusState.Pending, stringResource(R.string.trakt_asking))
            return@HlSideSheet
        }
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                // Read from across the room: white, in the voice of a place, never lit.
                Text(code.userCode, style = Hl.type.place, color = Hl.colors.content)
                Spacer(Modifier.height(HlSpace.m))
                HlStatus(
                    StatusState.Pending,
                    stringResource(R.string.trakt_waiting),
                    detail = stringResource(R.string.trakt_code_left, formatDuration(code.leftMinutesMs)),
                )
            }
            Spacer(Modifier.width(HlSpace.l))
            QrCode(R.drawable.trakt_qr, code.address)
        }
    }
}
