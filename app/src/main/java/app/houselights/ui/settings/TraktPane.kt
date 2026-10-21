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
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.TraktSession
import app.houselights.providers.trakt.TraktClient
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.QrCode
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.util.formatDuration

/**
 * Trakt, for the folders: what it keeps and the account's state, then one row that connects it (its code in a side
 * sheet) or disconnects it. A build without Trakt's keys says so and offers nothing.
 */
internal fun LazyListScope.traktPane(vm: TraktViewModel, session: TraktSession?) {
    item { Section(stringResource(R.string.section_trakt)) { TraktState(vm, session) } }
    if (vm.available) item { TraktRow(vm, session) }
}

/** The account connected, by its name; none; the connection Trakt ended; or no keys in this build. */
@Composable
private fun TraktState(vm: TraktViewModel, session: TraktSession?) {
    val live = session?.takeUnless { it.ended }
    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.m)) {
        Hint(stringResource(R.string.trakt_desc))
        when {
            !vm.available -> HlStatus(StatusState.Off, stringResource(R.string.trakt_no_keys))
            live != null -> HlStatus(
                StatusState.On,
                live.username?.let { stringResource(R.string.trakt_signed_in_as, it) } ?: stringResource(R.string.trakt_connected),
            )
            session?.ended == true -> AttentionNote(stringResource(R.string.trakt_ended))
            else -> HlStatus(StatusState.Off, stringResource(R.string.trakt_signed_out))
        }
    }
    // Connected while Trakt could not say the name: it is read once the pane shows.
    val nameless = live != null && live.username == null
    LaunchedEffect(nameless) { if (nameless) vm.readName() }
}

/**
 * Connect, or disconnect: one row whose words follow the account, so focus stays on it as the account comes and
 * goes. A failure is said under it ([RowFailureNote]).
 */
@Composable
private fun TraktRow(vm: TraktViewModel, session: TraktSession?) {
    val connected = session != null && !session.ended
    var showing by remember { mutableStateOf(false) }
    val failure = vm.note?.let { RowFailure(stringResource(R.string.action_not_done), stringResource(it)) }
    HlSettingGroup(null, Modifier.revealOnFocus()) {
        HlSettingRow(
            stringResource(if (connected) R.string.trakt_disconnect else R.string.trakt_connect),
            description = stringResource(if (connected) R.string.trakt_disconnect_desc else R.string.trakt_connect_desc),
            value = if (vm.disconnecting) stringResource(R.string.trakt_disconnecting) else failure?.value,
            valueAttention = failure != null && !vm.disconnecting,
            onClick = {
                if (connected) {
                    vm.disconnect()
                } else {
                    vm.connect()
                    showing = true
                }
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
            QrCode(R.drawable.trakt_qr, TraktClient.ACTIVATE)
        }
    }
}
