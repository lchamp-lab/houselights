package app.houselights.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import app.houselights.core.ImageRef
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.SourceKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.revealOnOpen
import app.houselights.ui.design.components.FieldLabel
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlEmptyState
import app.houselights.ui.design.components.HlFormScaffold
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.StatusPanel
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.roomPosters
import app.houselights.ui.util.scanErrorText
import app.houselights.ui.util.sourceKindName

/** Display order of the options: NFS first, since it needs no password. */
private val PROTOCOLS = listOf(Protocol.NFS, Protocol.SMB)
private val KINDS = listOf(SourceKind.MOVIES, SourceKind.SHOWS)

/**
 * Adds a network folder, or edits folder [folderId] except its kind; [onDone] gets whether the source changed. A first
 * folder that replaces a server keeps it signed in.
 */
@Composable
fun AddFolderScreen(folderId: Long?, focusOn: FolderField = FolderField.FIRST, onDone: (switched: Boolean) -> Unit) {
    val app = appContainer()
    val vm: AddFolderViewModel = viewModel(factory = AddFolderViewModel.factory(app, folderId))
    AddFolderForm(vm, focusOn, roomPosters(), onDone)
}

/** The form of [vm], focus starting on [focusOn], with the folder's connection status beside it, on [room]'s posters. */
@Composable
internal fun AddFolderForm(
    vm: AddFolderViewModel,
    focusOn: FolderField,
    room: List<ImageRef> = emptyList(),
    onDone: (switched: Boolean) -> Unit,
) {
    val form = vm.form
    val focus = remember { FormFocus() }
    // Wait until an edited folder is read, so its fields are laid out (the password only for SMB).
    LaunchedEffect(vm.ready) { if (vm.ready) focus.request(focusOn, vm.form.user) }
    val kind = form.kind
    val protocol = form.protocol
    val identity = identityText(form).takeIf { protocol == Protocol.NFS }

    HlFormScaffold(
        title = stringResource(if (vm.edits) R.string.edit_folder else R.string.add_folder),
        description = stringResource(if (vm.edits) R.string.edit_folder_desc else R.string.add_folder_desc),
        aside = { FolderStatus(vm, identity) },
        room = room,
    ) {
        if (vm.unread) {
            // In the form's place: none of it is the folder's, and saving it would erase the folder's password.
            HlEmptyState(
                stringResource(R.string.folder_unread),
                // The library is read from the player's storage, as the settings are.
                message = stringResource(R.string.error_settings),
                attention = true,
                action = stringResource(R.string.retry),
                actionIcon = HlIcons.Refresh,
                onAction = vm::readAgain,
                busy = stringResource(R.string.retrying).takeIf { vm.rereading },
            )
            return@HlFormScaffold
        }
        // The choices over the fields' columns, a little further from them than the fields from each other.
        Row(Modifier.padding(bottom = HlSpace.xs), horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
            // An edited folder keeps its kind (its titles stay): the panel beside shows it.
            if (!vm.edits) {
                Column(Modifier.weight(1f)) {
                    FieldLabel(stringResource(R.string.folder_kind))
                    HlSegmented(
                        KINDS.map { sourceKindName(it) },
                        selected = KINDS.indexOf(kind),
                        onSelect = { i -> vm.edit { copy(kind = KINDS[i]) } },
                        modifier = Modifier.focusRequester(focus.first),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
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
                optional = true,
            )
        }
        if (protocol == Protocol.SMB) {
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                HlTextField(
                    stringResource(R.string.field_user), form.user, { v -> vm.edit { copy(user = v) } },
                    modifier = Modifier.weight(1f).focusRequester(focus.user),
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
        // Under the actions, so the form above stays whole on screen; SMB says who reads by its account.
        if (protocol == Protocol.NFS) NfsIdentity(vm, identity)
    }
}

/**
 * Who the NFS client presents itself as to the NAS, on its own row under the actions, saying its value: [identity],
 * else root. OK opens what it changes and its two fields, empty for root, and folds them, what they hold kept.
 */
@Composable
private fun NfsIdentity(vm: AddFolderViewModel, identity: String?) {
    val form = vm.form
    // Opened with OK, what it changes and its fields come into view under the row, which keeps focus.
    Column(Modifier.revealOnOpen(vm.identityOpen)) {
        HlSettingGroup(null) {
            HlSettingRow(
                stringResource(R.string.nfs_identity_title),
                description = stringResource(R.string.nfs_identity_desc),
                value = identity ?: stringResource(R.string.nfs_identity_root),
                valueStyle = if (identity != null) Hl.type.jaquette else Hl.type.meta,
                onClick = vm::toggleIdentity,
                // At the largest text size, or with long numbers, the user over the group.
                valueBreaks = true,
            )
        }
        if (vm.identityOpen) {
            // Over the fields, so it reads whole while they have focus.
            Text(
                stringResource(R.string.nfs_identity_hint),
                style = Hl.type.meta,
                color = Hl.colors.contentSecondary,
                modifier = Modifier.padding(top = HlSpace.s, bottom = HlSpace.l),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                HlTextField(
                    stringResource(R.string.field_uid), form.uid, { v -> if (FolderForm.isId(v)) vm.edit { copy(uid = v) } },
                    Modifier.weight(1f),
                    placeholder = stringResource(R.string.placeholder_uid), keyboardType = KeyboardType.Number,
                )
                HlTextField(
                    stringResource(R.string.field_gid), form.gid, { v -> if (FolderForm.isId(v)) vm.edit { copy(gid = v) } },
                    Modifier.weight(1f),
                    placeholder = stringResource(R.string.placeholder_gid), keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                )
            }
        }
    }
}

/** The NFS user and group typed, in the technical voice ("UID 1026 · GID 100"), one left empty a dash; null for root. */
@Composable
private fun identityText(form: FolderForm): String? {
    if (form.uid.isEmpty() && form.gid.isEmpty()) return null
    val none = stringResource(R.string.placeholder_none)
    return stringResource(R.string.nfs_identity, form.uid.ifEmpty { none }, form.gid.ifEmpty { none })
}

/**
 * Where the folder of [vm] stands beside its form: the address the player will open, without credentials, its
 * protocol, kind and NFS [identity], the last test's result; then what the NAS must allow.
 */
@Composable
private fun FolderStatus(vm: AddFolderViewModel, identity: String?) {
    val check = vm.check
    val protocol = vm.form.protocol
    val s = vm.form.toSource()
    val folder = "/" + listOf(s.share, s.path).map { it.trim('/') }.filter { it.isNotEmpty() }.joinToString("/")
    val (state, stateText) = connectionState(check, testing = vm.testing, gone = vm.gone)
    // A folder deleted or unread was never read: its protocol and kind would be the form's defaults.
    val unknown = vm.gone || vm.unread
    StatusPanel(
        state = state,
        stateText = stringResource(stateText),
        label = stringResource(R.string.label_address),
        value = s.host.ifBlank { stringResource(R.string.placeholder_pending) },
        detail = folder.takeIf { it.length > 1 },
        badges = if (unknown) emptyList() else listOf(protocol.name),
        facts = if (unknown) emptyList() else listOf(sourceKindName(vm.form.kind), identity),
        result = resultText(check, gone = vm.gone),
        resultIsError = check !is FolderCheck.Reachable || vm.gone,
    )
    if (!vm.unread) {
        Spacer(Modifier.height(HlSpace.l))
        Text(
            stringResource(if (protocol == Protocol.NFS) R.string.nfs_hint else R.string.smb_hint),
            style = Hl.type.meta,
            color = Hl.colors.contentSecondary,
        )
    }
}

/** Add (or Save) and Test, both testing first: the pressed one shows progress, the other is disabled meanwhile. */
@Composable
private fun FolderActions(vm: AddFolderViewModel, onDone: (switched: Boolean) -> Unit) {
    // Which button started the running check.
    var adding by remember { mutableStateOf(false) }
    val checking = stringResource(R.string.checking)
    val busy = vm.busy
    Row(Modifier.padding(top = HlSpace.xs), horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
        HlButton(
            stringResource(if (vm.edits) R.string.save else R.string.add),
            {
                adding = true
                vm.submit(onDone)
            },
            icon = if (vm.edits) HlIcons.Check else HlIcons.Add,
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
    val user = FocusRequester()
    val password = FocusRequester()

    /** Focuses [field], else the first control; a password with no [user] yet (a refused guest) focuses the user. */
    fun request(field: FolderField, user: String) {
        val wanted = when (field) {
            FolderField.FIRST -> first
            FolderField.HOST -> host
            FolderField.PASSWORD -> if (user.isBlank()) this.user else password
        }
        if (!wanted.tryRequestFocus()) first.tryRequestFocus()
    }
}

/** The last test's result, or why the edited folder cannot be saved: it is [gone]. */
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
        // The first folder is added from Welcome, before Settings is reachable: the message never points there.
        is FolderCheck.SaveFailed -> stringResource(welcomeProblem(check.error))
    }
}

private fun shareExample(protocol: Protocol): Int =
    if (protocol == Protocol.NFS) R.string.placeholder_export else R.string.placeholder_share

private fun pathExample(kind: SourceKind): Int =
    if (kind == SourceKind.SHOWS) R.string.placeholder_path_shows else R.string.placeholder_path_movies

/** The folder's connection status and its text; an edited folder may be [gone]. */
private fun connectionState(check: FolderCheck?, testing: Boolean, gone: Boolean): Pair<StatusState, Int> = when {
    gone -> StatusState.Attention to R.string.folder_gone_state
    testing -> StatusState.Pending to R.string.connection_testing
    check is FolderCheck.Reachable -> StatusState.Done to R.string.connection_ok
    check is FolderCheck.Failed -> StatusState.Attention to R.string.connection_failed
    check is FolderCheck.Overlaps -> StatusState.Attention to R.string.folder_overlaps_state
    check is FolderCheck.SaveFailed -> StatusState.Attention to R.string.folder_not_saved_state
    else -> StatusState.Off to R.string.connection_untested
}
