package app.houselights.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.ImageRef
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.ui.design.BackToStart
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.revealOnFocus
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

/**
 * The form of [vm], focus starting on [focusOn], with the folder's connection status beside it, on [room]'s posters.
 * A failure is said once, with its mark, under the field to change ([spotOf]), which takes focus, the form scrolling to
 * show it whole; one no field can fix is the panel's result, focus left on the button pressed. « Terminé » on the last
 * field does what the first button does; Back first brings focus back to « Protocole » (« Contenu » for a new folder)
 * at the form's top, then leaves.
 */
@Composable
internal fun AddFolderForm(
    vm: AddFolderViewModel,
    focusOn: FolderField,
    room: List<ImageRef> = emptyList(),
    onDone: (switched: Boolean) -> Unit,
) {
    val focus = remember { FormFocus() }
    val scroll = rememberScrollState()
    // Wait until an edited folder is read, so its fields are laid out (the password only for SMB).
    LaunchedEffect(vm.ready) { if (vm.ready) focus.request(focusOn, vm.form.user) }
    val identity = identityText(vm.form).takeIf { vm.form.protocol == Protocol.NFS }
    val faults = faultsOf(vm)
    // Which button started the running check.
    var adding by remember { mutableStateOf(false) }
    val submit = {
        adding = true
        vm.submit(onDone)
    }
    FocusOnFault(vm, focus)
    val first = backToFirst(vm, focus, scroll)

    HlFormScaffold(
        title = stringResource(if (vm.edits) R.string.edit_folder else R.string.add_folder),
        description = stringResource(if (vm.edits) R.string.edit_folder_desc else R.string.add_folder_desc),
        aside = { FolderStatus(vm, identity, panel = faults.at(FaultSpot.PANEL)) },
        room = room,
        scroll = scroll,
    ) {
        if (vm.unread) {
            UnreadFolder(vm)
            return@HlFormScaffold
        }
        FolderChoices(vm, first)
        FolderFields(vm, focus, faults, submit)
        FolderActions(
            vm,
            adding,
            onAdd = submit,
            onTest = {
                adding = false
                vm.test()
            },
        )
        // Under the actions, so the form above stays whole on screen; SMB says who reads by its account.
        if (vm.form.protocol == Protocol.NFS) {
            NfsIdentity(vm, identity, focus, errors = faults.at(FaultSpot.UID) to faults.at(FaultSpot.GID), onDone = submit)
        }
    }
}

/** What went wrong, worded ([text]), and where it is said ([spot]): under a field, or in the panel. */
private class Faults(val spot: FaultSpot?, val text: String?) {
    /** The message said under [field], or in the panel for [FaultSpot.PANEL]; null when it is said elsewhere. */
    fun at(field: FaultSpot): String? = text?.takeIf { spot == field }
}

/** What [vm]'s last answer says went wrong, and where. */
@Composable
private fun faultsOf(vm: AddFolderViewModel): Faults {
    val check = vm.check
    return Faults(spotOf(check, vm.form), check?.let { faultText(it) })
}

/**
 * After each answer to a press, focus goes to the field its message is under, once laid out with it; a read refused to
 * the NFS account, to its row, unfolded. One said in the panel leaves focus on the button pressed.
 */
@Composable
private fun FocusOnFault(vm: AddFolderViewModel, focus: FormFocus) {
    LaunchedEffect(vm.answers) {
        if (vm.answers == 0) return@LaunchedEffect
        val refused = (vm.check as? FolderCheck.Failed)?.trouble is FolderTrouble.ReadRefused
        val target = if (refused) focus.identity else spotOf(vm.check, vm.form)?.let(focus::of)
        withFrameNanos { }
        target?.tryRequestFocus()
    }
}

/**
 * Back in two steps: from anywhere but the first control, or the form scrolled, to the first control at its top; then
 * out, whether focus could go there or not. A folder that could not be read shows no form: Back leaves at once. Returns
 * what the first control wears.
 */
@Composable
private fun backToFirst(vm: AddFolderViewModel, focus: FormFocus, scroll: ScrollState): Modifier {
    var atStart by remember { mutableStateOf(false) }
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    BackToStart(away = !vm.unread && (!atStart || scrolled)) {
        scroll.animateScrollTo(0)
        focus.first.tryRequestFocus()
        atStart = true
    }
    return Modifier.focusRequester(focus.first).onFocusChanged { atStart = it.hasFocus }
}

/** In the form's place: none of it is the folder's, and saving it would erase the folder's password. */
@Composable
private fun UnreadFolder(vm: AddFolderViewModel) {
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
}

/** The choices over the fields' columns, a little further from them than the fields from each other, [first] the first. */
@Composable
private fun FolderChoices(vm: AddFolderViewModel, first: Modifier) {
    Row(Modifier.padding(bottom = HlSpace.xs), horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
        // An edited folder keeps its kind (its titles stay): the panel beside shows it.
        if (!vm.edits) {
            Column(Modifier.weight(1f)) {
                FieldLabel(stringResource(R.string.folder_kind))
                HlSegmented(
                    KINDS.map { sourceKindName(it) },
                    selected = KINDS.indexOf(vm.form.kind),
                    onSelect = { i -> vm.edit { copy(kind = KINDS[i]) } },
                    modifier = first,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            FieldLabel(stringResource(R.string.field_protocol))
            HlSegmented(
                PROTOCOLS.map { it.name },
                selected = PROTOCOLS.indexOf(vm.form.protocol),
                onSelect = { i -> vm.edit { copy(protocol = PROTOCOLS[i]) } },
                modifier = if (vm.edits) first else Modifier,
            )
        }
    }
}

/**
 * The folder's place and SMB's account, each failure under its field ([faults]); « Terminé » on the last field
 * ([submit]) does what the first button does, the others go on to the next.
 */
@Composable
private fun FolderFields(vm: AddFolderViewModel, focus: FormFocus, faults: Faults, submit: () -> Unit) {
    val form = vm.form
    val nfs = form.protocol == Protocol.NFS
    HlTextField(
        stringResource(R.string.field_host), form.host, { v -> vm.edit { copy(host = v) } },
        Modifier.focusRequester(focus.host).shownWith(faults.at(FaultSpot.HOST)),
        placeholder = stringResource(R.string.placeholder_host), keyboardType = KeyboardType.Uri,
        error = faults.at(FaultSpot.HOST),
    )
    val subfolderLast = nfs && !vm.identityOpen
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
        HlTextField(
            stringResource(if (nfs) R.string.field_export else R.string.field_share),
            form.share, { v -> vm.edit { copy(share = v) } },
            placeholder = stringResource(shareExample(form.protocol)),
            modifier = Modifier.weight(1f).focusRequester(focus.share).shownWith(faults.at(FaultSpot.SHARE)),
            error = faults.at(FaultSpot.SHARE),
        )
        HlTextField(
            stringResource(R.string.field_path), form.path, { v -> vm.edit { copy(path = v) } },
            placeholder = stringResource(pathExample(form.kind)),
            modifier = Modifier.weight(1f).focusRequester(focus.subfolder).shownWith(faults.at(FaultSpot.SUBFOLDER)),
            optional = true,
            error = faults.at(FaultSpot.SUBFOLDER),
            imeAction = if (subfolderLast) ImeAction.Done else ImeAction.Next,
            onImeAction = { if (subfolderLast) submit() },
        )
    }
    if (!nfs) {
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
            HlTextField(
                stringResource(R.string.field_user), form.user, { v -> vm.edit { copy(user = v) } },
                modifier = Modifier.weight(1f).focusRequester(focus.user).shownWith(faults.at(FaultSpot.USER)),
                error = faults.at(FaultSpot.USER),
            )
            HlTextField(
                stringResource(R.string.field_password), form.password, { v -> vm.edit { copy(password = v) } },
                // The password saved is never shown: left empty, it stays.
                placeholder = if (vm.keepsPassword) stringResource(R.string.password_kept) else "",
                password = true, imeAction = ImeAction.Done, onImeAction = submit,
                modifier = Modifier.weight(1f).focusRequester(focus.password).shownWith(faults.at(FaultSpot.PASSWORD)),
                error = faults.at(FaultSpot.PASSWORD),
            )
        }
    }
}

/** A field brought into view whole, its name and message with it, as it takes focus and as a message comes under it. */
private fun Modifier.shownWith(error: String?): Modifier = revealOnFocus().revealOnOpen(error != null)

/**
 * The NFS account the NAS sees, on its own row under the actions, saying its value: [identity], else root. OK opens
 * what it changes and its two fields, empty for root, and folds them, what they hold kept; [errors] says under the
 * user's or the group's field which one the test asks for, and « Terminé » on the group's does [onDone].
 */
@Composable
private fun NfsIdentity(
    vm: AddFolderViewModel,
    identity: String?,
    focus: FormFocus,
    errors: Pair<String?, String?>,
    onDone: () -> Unit,
) {
    val form = vm.form
    val (uidError, gidError) = errors
    // Opened with OK, what it changes and its fields come into view under the row, which keeps focus.
    Column(Modifier.revealOnOpen(vm.identityOpen)) {
        HlSettingGroup(null) {
            HlSettingRow(
                stringResource(R.string.nfs_identity_title),
                Modifier.focusRequester(focus.identity),
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
                    Modifier.weight(1f).focusRequester(focus.uid).shownWith(uidError),
                    placeholder = stringResource(R.string.placeholder_uid), keyboardType = KeyboardType.Number,
                    error = uidError,
                )
                HlTextField(
                    stringResource(R.string.field_gid), form.gid, { v -> if (FolderForm.isId(v)) vm.edit { copy(gid = v) } },
                    Modifier.weight(1f).focusRequester(focus.gid).shownWith(gidError),
                    placeholder = stringResource(R.string.placeholder_gid), keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done, onImeAction = onDone,
                    error = gidError,
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
 * protocol, kind and NFS [identity], then what the last test found or the failure no field can fix ([panel]); at rest,
 * without a result, what the NAS must allow, whole or not at all.
 */
@Composable
private fun ColumnScope.FolderStatus(vm: AddFolderViewModel, identity: String?, panel: String?) {
    val check = vm.check
    val protocol = vm.form.protocol
    val s = vm.form.toSource()
    val (state, stateText) = connectionState(check, testing = vm.testing, gone = vm.gone)
    // A folder deleted or unread was never read: its protocol and kind would be the form's defaults.
    val unknown = vm.gone || vm.unread
    val pending = stringResource(R.string.placeholder_pending)
    val result = when {
        vm.gone -> stringResource(R.string.folder_gone)
        panel != null -> panel
        check is FolderCheck.Reachable -> contentText(check)
        else -> null
    }
    StatusPanel(
        state = state,
        stateText = stringResource(stateText),
        label = stringResource(R.string.label_address),
        value = s.host.ifBlank { pending },
        detail = placeOf(s, typed = vm.form.share.isNotBlank(), pending = pending),
        badges = if (unknown) emptyList() else listOf(protocol.name),
        facts = if (unknown) emptyList() else listOf(sourceKindName(vm.form.kind), identity),
        result = result,
        resultIsError = check !is FolderCheck.Reachable || vm.gone,
    )
    if (!vm.unread && result == null) {
        val player = vm.player
        val hint = when {
            protocol == Protocol.SMB -> stringResource(R.string.smb_hint)
            player != null -> stringResource(R.string.nfs_hint_player, player)
            else -> stringResource(R.string.nfs_hint)
        }
        // In the height left under the panel at the viewer's text size: never cut at the column's foot.
        WholeOrNothing(Modifier.weight(1f, fill = false)) {
            Spacer(Modifier.height(HlSpace.l))
            Text(hint, style = Hl.type.meta, color = Hl.colors.contentSecondary)
        }
    }
}

/**
 * Where the folder is on its NAS, as the player will open it ("/volume1/video/Films"), [pending] in place of a shared
 * folder not [typed] yet ("…/Films"); null while nothing of it is.
 */
private fun placeOf(s: SourceEntity, typed: Boolean, pending: String): String? {
    val share = s.share.trim('/')
    val path = s.path.trim('/')
    return when {
        share.isEmpty() && path.isEmpty() -> null
        share.isEmpty() && (!typed || s.protocol == Protocol.SMB) -> "$pending/$path"
        else -> "/" + listOf(share, path).filter { it.isNotEmpty() }.joinToString("/")
    }
}

/** [content] stacked, placed only if it fits whole in the height it is given; else nothing of it. */
@Composable
private fun WholeOrNothing(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)) }
        val height = placeables.sumOf { it.height }
        if (height > constraints.maxHeight) {
            layout(0, 0) {}
        } else {
            layout(constraints.constrainWidth(placeables.maxOfOrNull { it.width } ?: 0), height) {
                var y = 0
                placeables.forEach {
                    it.place(0, y)
                    y += it.height
                }
            }
        }
    }
}

/**
 * Save (or Add) and Test, both testing first: the pressed one says what it does in its own width, kept from the start
 * for each of its words, the other disabled meanwhile.
 */
@Composable
private fun FolderActions(vm: AddFolderViewModel, adding: Boolean, onAdd: () -> Unit, onTest: () -> Unit) {
    val checking = stringResource(R.string.checking)
    val saving = stringResource(R.string.saving)
    val busy = vm.busy
    Row(Modifier.padding(top = HlSpace.xs), horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
        HlButton(
            stringResource(if (vm.edits) R.string.save else R.string.add),
            onAdd,
            icon = if (vm.edits) HlIcons.Check else HlIcons.Add,
            enabled = vm.ready && !vm.gone && (adding || !busy),
            busy = busy && adding,
            busyLabel = if (vm.saving) saving else checking,
            // It tests, then saves: one width for both.
            reserveFor = if (vm.saving) checking else saving,
        )
        HlButton(
            stringResource(R.string.test_connection),
            onTest,
            icon = HlIcons.Test,
            enabled = !vm.gone && (!adding || !busy),
            busy = busy && !adding,
            busyLabel = checking,
        )
    }
}

/** Where focus can start in the form, and where a failure sends it ([of]). */
private class FormFocus {
    val first = FocusRequester()
    val host = FocusRequester()
    val share = FocusRequester()
    val subfolder = FocusRequester()
    val user = FocusRequester()
    val password = FocusRequester()
    val uid = FocusRequester()
    val gid = FocusRequester()
    val identity = FocusRequester()

    /** Focuses [field], else the first control; a password with no [user] yet (a refused guest) focuses the user. */
    fun request(field: FolderField, user: String) {
        val wanted = when (field) {
            FolderField.FIRST -> first
            FolderField.HOST -> host
            FolderField.PATH -> share
            FolderField.PASSWORD -> if (user.isBlank()) this.user else password
        }
        if (!wanted.tryRequestFocus()) first.tryRequestFocus()
    }

    /** The field at [spot]; null for the panel, which leaves focus on the button pressed. */
    fun of(spot: FaultSpot): FocusRequester? = when (spot) {
        FaultSpot.HOST -> host
        FaultSpot.SHARE -> share
        FaultSpot.SUBFOLDER -> subfolder
        FaultSpot.USER -> user
        FaultSpot.PASSWORD -> password
        FaultSpot.UID -> uid
        FaultSpot.GID -> gid
        FaultSpot.PANEL -> null
    }
}

/** What went wrong in [check], worded to say what to do; null when nothing did. */
@Composable
private fun faultText(check: FolderCheck): String? = when (check) {
    is FolderCheck.Reachable -> null
    is FolderCheck.Incomplete -> stringResource(check.message)
    is FolderCheck.Failed -> troubleText(check.trouble, check.host)
    is FolderCheck.Overlaps -> stringResource(
        when {
            check.same -> R.string.folder_already_added
            check.holds -> R.string.folder_holds
            else -> R.string.folder_inside
        },
        check.existing.name.replaceFirstChar { it.uppercase() },
    )
    FolderCheck.Empty -> stringResource(R.string.folder_no_content)
    // The first folder is added from Welcome, before Settings is reachable: the message never points there.
    is FolderCheck.SaveFailed -> stringResource(welcomeProblem(check.error))
}

/** What [trouble] asks to change, on the NAS at [host]. */
@Composable
private fun troubleText(trouble: FolderTrouble, host: String): String = when (trouble) {
    FolderTrouble.Unreachable -> stringResource(R.string.error_nas_unreachable, host)
    is FolderTrouble.ShareMissing -> stringResource(R.string.error_share_not_found, host, trouble.share)
    is FolderTrouble.ExportMissing -> stringResource(R.string.error_export_not_found, trouble.export)
    is FolderTrouble.SubfolderMissing -> stringResource(
        if (trouble.nfs) R.string.error_subfolder_not_found else R.string.error_subfolder_not_found_share,
        trouble.within,
        trouble.name,
    )
    FolderTrouble.Missing -> stringResource(R.string.error_not_found)
    is FolderTrouble.PlayerRefused, is FolderTrouble.ReadRefused -> refusalText(trouble)
    is FolderTrouble.AccountDenied -> stringResource(R.string.error_smb_denied, trouble.user, trouble.share)
    FolderTrouble.GuestRefused -> stringResource(R.string.error_guest_refused)
    FolderTrouble.LoginFailed -> stringResource(R.string.error_login_failed)
    FolderTrouble.Other -> stringResource(R.string.error_scan_other)
}

/** An NFS refusal: what to allow, where and to whom ([FolderTrouble.PlayerRefused]), or whose account to change. */
@Composable
private fun refusalText(trouble: FolderTrouble): String = when {
    trouble is FolderTrouble.PlayerRefused && trouble.player != null ->
        stringResource(R.string.error_nfs_refused, trouble.player, trouble.export)
    trouble is FolderTrouble.PlayerRefused -> stringResource(R.string.error_nfs_refused_no_address, trouble.export)
    trouble is FolderTrouble.ReadRefused && trouble.uid != null ->
        stringResource(R.string.error_nfs_account_refused, trouble.uid.toString(), trouble.folder)
    trouble is FolderTrouble.ReadRefused -> stringResource(R.string.error_nfs_root_refused, trouble.folder)
    else -> stringResource(R.string.error_scan_other)
}

/** What a test found at the folder's first level: "412 dossiers et 3 vidéos", "412 dossiers", "3 vidéos". */
@Composable
private fun contentText(found: FolderCheck.Reachable): String {
    val folders = pluralStringResource(R.plurals.folder_count, found.folders, found.folders)
    val videos = pluralStringResource(R.plurals.video_count, found.videos, found.videos)
    return when {
        found.folders > 0 && found.videos > 0 -> stringResource(R.string.folder_content, folders, videos)
        found.folders > 0 -> folders
        else -> videos
    }
}

private fun shareExample(protocol: Protocol): Int =
    if (protocol == Protocol.NFS) R.string.placeholder_export else R.string.placeholder_share

private fun pathExample(kind: SourceKind): Int =
    if (kind == SourceKind.SHOWS) R.string.placeholder_path_shows else R.string.placeholder_path_movies

/**
 * The folder's connection status and its text; an edited folder may be [gone]. A failure is said by the ring and its
 * words, its mark being its message's, beside its cause.
 */
internal fun connectionState(check: FolderCheck?, testing: Boolean, gone: Boolean): Pair<StatusState, Int> = when {
    gone -> StatusState.Off to R.string.folder_gone_state
    testing -> StatusState.Pending to R.string.connection_testing
    check is FolderCheck.Reachable -> StatusState.Done to R.string.connection_ok
    check is FolderCheck.Failed -> StatusState.Off to R.string.connection_failed
    check is FolderCheck.Overlaps -> StatusState.Off to if (check.same) R.string.folder_overlaps_state else R.string.folder_nested_state
    check is FolderCheck.Empty -> StatusState.Off to R.string.folder_empty_state
    check is FolderCheck.SaveFailed -> StatusState.Off to R.string.folder_not_saved_state
    else -> StatusState.Off to R.string.connection_untested
}
