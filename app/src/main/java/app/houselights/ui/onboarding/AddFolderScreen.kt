package app.houselights.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.address
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.FieldLabel
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlFormScaffold
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.StatusPanel
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.scanErrorText
import app.houselights.ui.util.sourceKindName

/** Displayed order of the options: NFS first, since it needs no password. */
private val PROTOCOLS = listOf(Protocol.NFS, Protocol.SMB)
private val KINDS = listOf(SourceKind.MOVIES, SourceKind.SHOWS)

/**
 * Adds a network folder to the local library, after an optional connection test; with [folderId],
 * edits that folder in place, its kind aside, focus starting on [focusOn]. [onDone] hears whether
 * the source changed.
 */
@Composable
fun AddFolderScreen(folderId: Long?, focusOn: FolderField = FolderField.FIRST, onDone: (switched: Boolean) -> Unit) {
    val app = appContainer()
    val vm: AddFolderViewModel = viewModel(factory = AddFolderViewModel.factory(app, folderId))
    val form = vm.form
    val focus = remember { FormFocus() }
    // Once a folder edited is read: its fields are then laid out (the password only for SMB).
    LaunchedEffect(vm.ready) { if (vm.ready) focus.request(focusOn) }
    val check = vm.check
    val result = resultText(check, gone = vm.gone)
    val success = check is FolderCheck.Reachable && !vm.gone
    val kind = form.kind
    val protocol = form.protocol

    // Live preview of the address the player will open, without credentials: the host, then its path.
    val s = form.toSource()
    val folder = "/" + listOf(s.share, s.path).map { it.trim('/') }.filter { it.isNotEmpty() }.joinToString("/")
    val (state, stateText) = connectionState(check, testing = vm.testing, gone = vm.gone)

    HlFormScaffold(
        title = stringResource(if (vm.edits) R.string.edit_folder else R.string.add_folder),
        description = stringResource(if (vm.edits) R.string.edit_folder_desc else R.string.add_folder_desc),
        aside = {
            StatusPanel(
                state = state,
                stateText = stringResource(stateText),
                label = stringResource(R.string.label_address),
                value = s.host.ifBlank { stringResource(R.string.placeholder_pending) },
                detail = folder.takeIf { it.length > 1 },
                // A folder gone was never read: the form's defaults would pass for its own.
                badges = if (vm.gone) {
                    emptyList()
                } else {
                    listOf(
                        protocol.name,
                        sourceKindName(kind),
                    )
                },
                result = result,
                resultIsError = !success,
            )
            Spacer(Modifier.height(HlSpace.l))
            Text(
                stringResource(if (protocol == Protocol.NFS) R.string.nfs_hint else R.string.smb_hint),
                style = Hl.type.caption,
                color = Hl.colors.contentSecondary,
            )
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl)) {
            // A folder edited keeps what it holds (its titles stay): the panel beside says it.
            if (!vm.edits) {
                Column {
                    FieldLabel(stringResource(R.string.folder_kind))
                    HlSegmented(
                        KINDS.map { sourceKindName(it) },
                        selected = KINDS.indexOf(kind),
                        onSelect = { i -> vm.edit { copy(kind = KINDS[i]) } },
                        modifier = Modifier.focusRequester(focus.first),
                    )
                }
            }
            Column {
                FieldLabel(stringResource(R.string.field_protocol))
                HlSegmented(
                    PROTOCOLS.map { it.name },
                    selected = PROTOCOLS.indexOf(protocol),
                    onSelect = { i -> vm.edit { copy(protocol = PROTOCOLS[i]) } },
                    modifier = if (vm.edits) Modifier.focusRequester(focus.first) else Modifier,
                )
            }
        }
        HlTextField(
            stringResource(R.string.field_host), form.host, { v -> vm.edit { copy(host = v) } },
            Modifier.focusRequester(focus.host),
            placeholder = stringResource(R.string.placeholder_host), keyboardType = KeyboardType.Uri,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
            HlTextField(
                stringResource(if (protocol == Protocol.NFS) R.string.field_export else R.string.field_share),
                form.share, { v -> vm.edit { copy(share = v) } },
                placeholder = stringResource(shareExample(protocol)),
                modifier = Modifier.weight(1f),
            )
            HlTextField(
                stringResource(R.string.field_path), form.path, { v -> vm.edit { copy(path = v) } },
                placeholder = stringResource(pathExample(kind)),
                modifier = Modifier.weight(1f),
            )
        }
        if (protocol == Protocol.SMB) {
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                HlTextField(
                    stringResource(R.string.field_user), form.user, { v -> vm.edit { copy(user = v) } },
                    modifier = Modifier.weight(1f),
                )
                HlTextField(
                    stringResource(R.string.field_password), form.password, { v -> vm.edit { copy(password = v) } },
                    // The password saved is never shown: left empty, it stays.
                    placeholder = if (vm.keepsPassword) stringResource(R.string.password_kept) else "",
                    password = true, imeAction = ImeAction.Done, modifier = Modifier.weight(1f).focusRequester(focus.password),
                )
            }
        }
        FolderActions(vm, onDone)
    }
}

/**
 * Add (or Save, for a folder edited), and Test. Both test the folder first: the one pressed says so
 * while the check runs, and Add again while the folder is then written; the other waits. A folder
 * edited can be saved once read, and never once gone.
 */
@Composable
private fun FolderActions(vm: AddFolderViewModel, onDone: (switched: Boolean) -> Unit) {
    // Which button started the check under way.
    var adding by remember { mutableStateOf(false) }
    val checking = stringResource(R.string.checking)
    val busy = vm.busy
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
        HlButton(
            stringResource(if (vm.edits) R.string.save else R.string.add),
            {
                adding = true
                vm.submit(onDone)
            },
            icon = if (vm.edits) HlIcons.Check else HlIcons.Add,
            emphasis = Emphasis.Primary,
            enabled = vm.ready && !vm.gone && (adding || !busy),
            busy = busy && adding,
            busyLabel = if (vm.saving) stringResource(R.string.saving) else checking,
        )
        HlButton(
            stringResource(R.string.test_connection),
            {
                adding = false
                vm.test()
            },
            icon = HlIcons.Test,
            enabled = !vm.gone && (!adding || !busy),
            busy = busy && !adding,
            busyLabel = checking,
        )
    }
}

/** Where focus can start in the form: its first control, the NAS address, or the password. */
private class FormFocus {
    val first = FocusRequester()
    val host = FocusRequester()
    val password = FocusRequester()

    /** Focuses [field], else the first control: a field not shown (no password for NFS) takes none. */
    fun request(field: FolderField) {
        val wanted = when (field) {
            FolderField.FIRST -> first
            FolderField.HOST -> host
            FolderField.PASSWORD -> password
        }
        if (!wanted.tryRequestFocus()) first.tryRequestFocus()
    }
}

/** How the last test went, or why the folder edited cannot be saved: it is [gone]. */
@Composable
private fun resultText(check: FolderCheck?, gone: Boolean): String? = if (gone) {
    stringResource(R.string.folder_gone)
} else {
    when (check) {
        null -> null
        is FolderCheck.Incomplete -> stringResource(check.message)
        is FolderCheck.Reachable -> pluralStringResource(R.plurals.entries_found, check.entries, check.entries)
        is FolderCheck.Failed -> scanErrorText(check.error, check.host)
        is FolderCheck.Overlaps -> stringResource(
            if (check.same) R.string.folder_already_added else R.string.folder_overlaps,
            check.existing.name.replaceFirstChar { it.uppercase() },
        )
        // The first folder is added from the welcome screen, before the settings can be reached: never sent there.
        is FolderCheck.SaveFailed -> stringResource(welcomeProblem(check.error))
    }
}

/** An example of the share, by protocol. */
private fun shareExample(protocol: Protocol): Int =
    if (protocol == Protocol.NFS) R.string.placeholder_export else R.string.placeholder_share

/** An example of the folder inside the share, by what it holds. */
private fun pathExample(kind: SourceKind): Int =
    if (kind == SourceKind.SHOWS) R.string.placeholder_path_shows else R.string.placeholder_path_movies

/** Where the connection to the folder stands, and how to say it; a folder edited may be [gone]. */
private fun connectionState(check: FolderCheck?, testing: Boolean, gone: Boolean): Pair<StatusState, Int> = when {
    gone -> StatusState.Attention to R.string.folder_gone_state
    testing -> StatusState.Pending to R.string.connection_testing
    check is FolderCheck.Reachable -> StatusState.On to R.string.connection_ok
    check is FolderCheck.Failed -> StatusState.Attention to R.string.connection_failed
    check is FolderCheck.Overlaps -> StatusState.Attention to R.string.folder_overlaps_state
    check is FolderCheck.SaveFailed -> StatusState.Attention to R.string.folder_not_saved_state
    else -> StatusState.Off to R.string.connection_untested
}
