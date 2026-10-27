package app.houselights.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.houselights.R
import app.houselights.core.ImageRef
import app.houselights.providers.local.FolderLevel
import app.houselights.providers.local.FoundNas
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.Standing
import app.houselights.providers.local.UsedNas
import app.houselights.providers.local.Volume
import app.houselights.ui.design.BackToStart
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.GroupTitle
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlEmptyState
import app.houselights.ui.design.components.HlFormScaffold
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.PlateSlice
import app.houselights.ui.design.components.StatusPanel
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.onboarding.FlowKeys.CHANGE_ACCOUNT
import app.houselights.ui.onboarding.FlowKeys.CHOOSE
import app.houselights.ui.onboarding.FlowKeys.CONTINUE
import app.houselights.ui.onboarding.FlowKeys.FIELD_GID
import app.houselights.ui.onboarding.FlowKeys.FIELD_HOST
import app.houselights.ui.onboarding.FlowKeys.FIELD_PASSWORD
import app.houselights.ui.onboarding.FlowKeys.FIELD_PATH
import app.houselights.ui.onboarding.FlowKeys.FIELD_UID
import app.houselights.ui.onboarding.FlowKeys.FIELD_USER
import app.houselights.ui.onboarding.FlowKeys.GUEST
import app.houselights.ui.onboarding.FlowKeys.NET_FIRST
import app.houselights.ui.onboarding.FlowKeys.NET_SLOT
import app.houselights.ui.onboarding.FlowKeys.NFS_ACCOUNT
import app.houselights.ui.onboarding.FlowKeys.SEARCH
import app.houselights.ui.onboarding.FlowKeys.SIGN_IN
import app.houselights.ui.onboarding.FlowKeys.SUGGESTION
import app.houselights.ui.onboarding.FlowKeys.TYPE_ADDRESS
import app.houselights.ui.onboarding.FlowKeys.TYPE_PATH
import app.houselights.ui.onboarding.FlowKeys.diskKey
import app.houselights.ui.onboarding.FlowKeys.folderKey
import app.houselights.ui.onboarding.FlowKeys.kindKey
import app.houselights.ui.onboarding.FlowKeys.nasKey
import app.houselights.ui.onboarding.FlowKeys.protocolKey
import app.houselights.ui.onboarding.FlowKeys.shareKey
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.roomPosters
import app.houselights.ui.util.sourceKindName
import app.houselights.ui.util.wholePath

/**
 * « Ajouter un dossier » (DESIGN.md, Secondary screens): the guided flow of [FolderFlow], on the room's posters; Back
 * from its first step leaves it ([onLeave]); [onDone] once the folder is added. A disk of this player chosen asks
 * Android to read it first ([rememberDiskAsk]). With [editing], « Modifier le dossier » on that folder of the player's
 * disks, from its disk's folders; Back from the disk's root leaves it, saying the folder edited once the library gave it.
 */
@Composable
fun AddFolderFlowScreen(onLeave: (edited: Long?) -> Unit, onDone: (FlowDone) -> Unit, editing: Long? = null) {
    val app = appContainer()
    val context = LocalContext.current
    val flow: FolderFlow = viewModel(factory = FolderFlow.factory(app, context, editing))
    AddFolderFlow(flow, roomPosters(), onLeave, onDone, rememberDiskAsk(app.diskPermission))
}

/**
 * The flow as drawn: the task's title, the step's sentence and the panel filling in on the left, the step's rows on the
 * right, in a lazy list that composes only the rows shown; focus where the flow aims it, a row brought into view first.
 * Back goes up a level or a step, first to the list's start when it is scrolled, and leaves ([onLeave]) with the folder
 * edited, when the library gave it (its sheet opens again), else null. [askDisk] asks Android to read this player's disks.
 */
@Composable
internal fun AddFolderFlow(
    flow: FolderFlow,
    room: List<ImageRef> = emptyList(),
    onLeave: (edited: Long?) -> Unit = {},
    onDone: (FlowDone) -> Unit = {},
    askDisk: DiskAsk = DiskAsk.NONE,
) {
    val focus = remember { FlowFocus() }
    // Each step and level starts at its top.
    val list = remember(flow.view) { LazyListState() }
    val parts = if (flow.ready) stepParts(flow, focus, onDone, askDisk) else emptyList()
    val shown by rememberUpdatedState(parts)
    val shownList by rememberUpdatedState(list)
    LaunchedEffect(flow.aim) {
        val aim = flow.aim ?: return@LaunchedEffect
        val target = focus[aim.key]
        if (target.tryRequestFocus()) return@LaunchedEffect
        // Composed once laid out; one the list has not composed yet, as a level's subfolder far down, scrolled to first.
        withFrameNanos { }
        if (target.tryRequestFocus()) return@LaunchedEffect
        val index = shown.indexOfFirst { aim.key in it.keys }
        if (index < 0) return@LaunchedEffect
        shownList.scrollToItem((index - SHOWN_ABOVE).coerceAtLeast(0))
        withFrameNanos { }
        target.tryRequestFocus()
    }
    BackHandler { flow.back { onLeave(flow.edited?.id) } }
    val away by remember(list) { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 } }
    // After the flow's own Back, so that it comes first: from deep in a step's list, Back brings focus to its first row.
    BackToStart(away) {
        list.scrollToItem(0)
        withFrameNanos { }
        focus[flow.startKey()].tryRequestFocus()
    }
    HlFormScaffold(
        title = stringResource(if (flow.editing != null) R.string.edit_folder else R.string.add_folder),
        description = stepSentence(flow),
        aside = { FlowStatus(flow) },
        list = list,
        room = room,
    ) {
        parts.forEachIndexed { i, part ->
            item(key = part.key) {
                Column {
                    if (i > 0 && part.gap > HlSpace.none) Spacer(Modifier.height(part.gap))
                    part.content()
                }
            }
        }
    }
}

/** Rows shown above one focused far down a list, scrolled to: what comes before it. */
private const val SHOWN_ABOVE = 2

/** The focus requesters of the flow's rows and fields, by key; the [network] group's row that has focus, by its place. */
private class FlowFocus {
    private val requesters = mutableMapOf<String, FocusRequester>()
    var network: String? = null

    operator fun get(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }
}

/**
 * One item of a step's lazy list, [key] its own, holding the rows or fields [keys] names: a group, fields, a button,
 * or one row of a long group ([PlateSlice]), [gap] under what is above it.
 */
private class FlowPart(val key: String, val keys: Set<String>, val gap: Dp = HlSpace.l, val content: @Composable () -> Unit)

@Composable
private fun stepParts(flow: FolderFlow, focus: FlowFocus, onDone: (FlowDone) -> Unit, askDisk: DiskAsk): List<FlowPart> = when (flow.step) {
    FlowStep.WHERE -> if (flow.editing != null) editedParts(flow, focus, askDisk) else whereParts(flow, focus, askDisk)
    FlowStep.ADDRESS -> addressParts(flow, focus)
    FlowStep.PROTOCOLS -> protocolParts(flow, focus)
    FlowStep.ACCOUNT -> accountParts(flow, focus)
    FlowStep.SHARES -> shareParts(flow, focus)
    FlowStep.TYPE_PATH -> pathParts(flow, focus)
    FlowStep.NFS_ACCOUNT -> identityParts(flow, focus)
    FlowStep.BROWSE -> browseParts(flow, focus, onDone)
    FlowStep.KIND -> kindParts(flow, focus, onDone)
}

/** The step's sentence; a folder edited's, at every step, says what it keeps. */
@Composable
private fun stepSentence(flow: FolderFlow): String = if (flow.editing != null) {
    flow.edited?.let { stringResource(R.string.flow_edit_desc, capitalised(it.name)) }.orEmpty()
} else {
    addSentence(flow)
}

@Composable
private fun addSentence(flow: FolderFlow): String = when (flow.step) {
    FlowStep.WHERE -> stringResource(R.string.flow_where_desc)
    FlowStep.ADDRESS -> stringResource(R.string.type_address_flow_desc)
    FlowStep.PROTOCOLS -> stringResource(R.string.flow_protocol_desc)
    FlowStep.ACCOUNT -> stringResource(R.string.flow_account_desc)
    FlowStep.SHARES -> stringResource(R.string.flow_share_desc)
    FlowStep.TYPE_PATH -> stringResource(if (flow.noList) R.string.flow_path_no_list_desc else R.string.flow_path_desc)
    FlowStep.NFS_ACCOUNT -> stringResource(R.string.flow_nfs_identity_desc)
    FlowStep.BROWSE -> stringResource(R.string.flow_browse_desc)
    FlowStep.KIND -> stringResource(R.string.flow_kind_desc, flow.folderName())
}

/**
 * Where the flow stands: the connection by its light, the NAS by its name or « Adresse » over its address (a disk of
 * this player « Sur ce lecteur » over its name), the path chosen so far, its protocol printed, a folder edited's kind,
 * the SMB account or the NFS user and group; then the last failure's sentence, the only attention mark: the NAS that
 * answered and refused is « Connexion réussie », the sentence saying why.
 */
@Composable
private fun FlowStatus(flow: FolderFlow) {
    val nas = flow.nas
    val (state, words) = connectionOf(flow)
    val protocol = flow.protocol
    val disk = protocol == Protocol.LOCAL
    val share = flow.share
    val detail = share?.let { opened ->
        "/" + (opened.path.trim('/').split('/') + flow.path).filter { it.isNotEmpty() }.joinToString("/")
    }
    val failed = flow.notSaved
    StatusPanel(
        state = state,
        stateText = stringResource(words),
        label = stringResource(if (disk) R.string.on_this_player else R.string.label_address),
        value = if (disk) diskName(nas) else nas?.host ?: stringResource(R.string.placeholder_pending),
        name = nas?.name.takeIf { !disk },
        detail = detail,
        badges = listOfNotNull(protocol?.name?.takeIf { !disk }),
        facts = listOf(flow.edited?.let { sourceKindName(it.kind) }, accountText(flow), identityText(flow)),
        result = if (failed != null) stringResource(welcomeProblem(failed)) else flow.message?.let { flowText(it) },
        resultIsError = true,
    )
}

/** The disk [nas] opened, as its row names it: « Disque interne », else its own name. */
@Composable
private fun diskName(nas: FlowNas?): String = when {
    nas == null || nas.host == Volume.INTERNAL -> stringResource(R.string.disk_internal)
    else -> nas.name ?: stringResource(R.string.disk_usb)
}

/** The connection's state and its words: under way, not saved, silent, reached, or not tried yet. */
private fun connectionOf(flow: FolderFlow): Pair<StatusState, Int> {
    val pressed = flow.pressed
    return when {
        pressed != null && !pressed.writes -> StatusState.Pending to R.string.connection_testing
        flow.notSaved != null -> StatusState.Off to R.string.folder_not_saved_state
        flow.silent -> StatusState.Off to R.string.connection_failed
        flow.reached -> StatusState.Done to R.string.connection_ok
        else -> StatusState.Off to R.string.connection_untested
    }
}

/** Who the SMB shares were listed as, once they were: the account, or « Invité »; not one being typed to change it. */
@Composable
private fun accountText(flow: FolderFlow): String? = when {
    flow.protocol != Protocol.SMB || !flow.signedIn -> null
    flow.listedAsGuest -> stringResource(R.string.guest_fact)
    else -> flow.listedUser.trim()
}

/** The NFS user and group the flow presents, "UID 1026 · GID 100", both typed; null for root, or over SMB. */
@Composable
private fun identityText(flow: FolderFlow): String? = if (flow.protocol == Protocol.NFS && flow.uid.isNotEmpty() && flow.gid.isNotEmpty()) {
    stringResource(R.string.nfs_identity, flow.uid, flow.gid)
} else {
    null
}

/** A row's value: [text] in `meta`, or in `jaquette` when [technical], after a state's [light]. */
@Immutable
private data class RowValue(val text: String, val light: StatusState? = null, val technical: Boolean = false)

/**
 * A row of the flow, focused by [key] (and [also], its place in the network's group): while it is under way, the quiet dot and
 * its words in place of its value ([rest]); after a failure, the ring and its two words. With [searching], its quiet
 * dot alone at its end.
 */
@Composable
private fun FlowRow(
    flow: FolderFlow,
    focus: FlowFocus,
    key: String,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    rest: RowValue? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    valueBreaks: Boolean = false,
    searching: Boolean = false,
    also: String? = null,
) {
    val pressed = flow.pressed
    val failure = flow.rowFailures[key]
    val value = when {
        pressed != null && pressed.key == key -> RowValue(stringResource(pressed.words), StatusState.Pending)
        failure != null -> RowValue(stringResource(failure.words), StatusState.Off)
        else -> rest
    }
    val focused = modifier.focusRequester(focus[key]).let { if (also != null) it.focusRequester(focus[also]) else it }
    HlSettingRow(
        title,
        focused,
        value = value?.text,
        description = description,
        icon = icon,
        valueStyle = if (value?.technical == true) Hl.type.jaquette else Hl.type.meta,
        onClick = onClick,
        enabled = enabled,
        valueBreaks = valueBreaks,
        // Searching, its quiet dot alone at its end.
        valueLight = value?.light ?: StatusState.Pending.takeIf { searching },
    )
}

// Step 1 · where the videos are ------------------------------------------------------------------------------------

@Composable
private fun whereParts(flow: FolderFlow, focus: FlowFocus, askDisk: DiskAsk): List<FlowPart> {
    val used = flow.used
    val others = flow.others
    val searching = flow.searching
    // The network's rows keep their element by their place: the search row, where focus may rest, becomes the next NAS
    // found, a new search row coming under it, so no row arrives above focus. Without a library, the first holds the
    // first focus.
    val tail = searching || others.isEmpty()
    val count = others.size + if (tail) 1 else 0
    val slots = (0 until count).map { i -> if (used.isEmpty() && i == 0) NET_FIRST else "$NET_SLOT$i" }
    // The search row going with focus on it, its search over: focus goes to the row above it, the last NAS found.
    val gone = remember(slots) { focus.network?.let { it !in slots } == true }
    LaunchedEffect(gone, slots) {
        if (!gone) return@LaunchedEffect
        withFrameNanos { }
        focus[slots.last()].tryRequestFocus()
    }
    // Another step: its row is no longer where focus is.
    DisposableEffect(Unit) { onDispose { focus.network = null } }
    val parts = mutableListOf<FlowPart>()
    if (used.isNotEmpty()) {
        parts += FlowPart("used", used.map { nasKey(it.host) }.toSet()) {
            HlSettingGroup(stringResource(R.string.nas_used)) {
                used.forEach { nas -> key(nas.host) { UsedRow(flow, focus, nas) } }
            }
        }
    }
    parts += FlowPart("found", slots.toSet() + others.map { nasKey(it.address) } + SEARCH) {
        HlSettingGroup(stringResource(R.string.nas_found)) {
            slots.forEachIndexed { i, slot -> key(slot) { NetworkRow(flow, focus, others.getOrNull(i), searching, slot) } }
        }
    }
    val disks = flow.disks
    if (disks.isNotEmpty()) {
        parts += FlowPart("disks", disks.map { diskKey(it.id) }.toSet()) {
            HlSettingGroup(stringResource(R.string.on_this_player)) {
                disks.forEach { disk -> key(disk.id) { DiskRow(flow, focus, disk.id, disk.internal, disk.name, askDisk) } }
            }
        }
    }
    parts += FlowPart(TYPE_ADDRESS, setOf(TYPE_ADDRESS)) {
        HlSettingGroup(null) {
            FlowRow(
                flow, focus, TYPE_ADDRESS, stringResource(R.string.type_address), flow::typeAddress,
                description = stringResource(R.string.type_address_desc),
                icon = HlIcons.Edit,
            )
        }
    }
    return parts
}

/** A NAS of the library: its name on the network when it announces one, its folders, the protocol they use. */
@Composable
private fun UsedRow(flow: FolderFlow, focus: FlowFocus, nas: UsedNas) {
    val name = flow.nameOf(nas)
    val folders = nas.folders.joinToString(", ") { folder -> folder.replaceFirstChar { it.uppercase() } }
    val silent = flow.answers[nas.host] == NasAnswer.Silent
    FlowRow(
        flow, focus, nasKey(nas.host), name ?: nas.host, { flow.chooseUsed(nas) },
        description = if (name != null) nas.host + FACT_GAP + folders else folders,
        rest = if (silent) {
            RowValue(stringResource(R.string.server_silent), StatusState.Off)
        } else {
            RowValue(nas.protocols.first().name, technical = true)
        },
    )
}

/**
 * Disk [id] of this player: « Disque interne », its storage ([internal]), else a USB disk by the name Android gives it
 * ([label]); OK opens its root, so named.
 */
@Composable
private fun DiskRow(flow: FolderFlow, focus: FlowFocus, id: String, internal: Boolean, label: String, ask: DiskAsk) {
    val usb = stringResource(R.string.disk_usb)
    val name = if (internal) stringResource(R.string.disk_internal) else label.ifBlank { usb }
    FlowRow(
        flow, focus, diskKey(id), name, { flow.chooseDisk(id, name, ask) },
        description = if (internal) stringResource(R.string.disk_internal_desc) else usb,
    )
}

/**
 * A folder edited whose disk did not open: that disk's row alone, saying why, OK opening it where the folder is; one
 * the flow could not open, why in the rows' place ([lostPart]).
 */
@Composable
private fun editedParts(flow: FolderFlow, focus: FlowFocus, askDisk: DiskAsk): List<FlowPart> {
    flow.lost?.let { return listOf(lostPart(flow, it)) }
    val disk = flow.nas?.takeIf { flow.edited != null } ?: return emptyList()
    return listOf(
        FlowPart("disks", setOf(diskKey(disk.host))) {
            HlSettingGroup(stringResource(R.string.on_this_player)) {
                DiskRow(flow, focus, disk.host, disk.host == Volume.INTERNAL, disk.name.orEmpty(), askDisk)
            }
        },
    )
}

/**
 * The folder edited, [lost]: unread, said as the folder's form says it, Réessayer focused; no longer in the library,
 * nothing fixes it here, Back the way out.
 */
private fun lostPart(flow: FolderFlow, lost: EditLost) = FlowPart("lost", emptySet()) {
    when (lost) {
        // The library is read from the player's storage, as the settings are.
        EditLost.UNREAD -> HlEmptyState(
            stringResource(R.string.folder_unread),
            message = stringResource(R.string.error_settings),
            attention = true,
            action = stringResource(R.string.retry),
            actionIcon = HlIcons.Refresh,
            onAction = flow::readAgain,
            busy = stringResource(R.string.retrying).takeIf { flow.rereading },
        )
        EditLost.GONE -> HlEmptyState(
            stringResource(R.string.folder_gone_state),
            message = stringResource(R.string.folder_gone),
            attention = true,
            backHint = true,
        )
    }
}

/**
 * A row of the network's group, [found] when a NAS takes it, else the search under way ([searching]) or, once over,
 * the search again; the same row in [slot] whichever, so focus stays on it as its words change, the slot that has
 * focus kept in [focus].
 */
@Composable
private fun NetworkRow(flow: FolderFlow, focus: FlowFocus, found: FoundNas?, searching: Boolean, slot: String) {
    // One call whatever the row says, so that it stays the element focus is on.
    FlowRow(
        flow, focus,
        key = found?.let { nasKey(it.address) } ?: SEARCH,
        title = found?.let { it.name.ifBlank { it.address } }
            ?: stringResource(if (searching) R.string.nas_searching else R.string.nas_search_again),
        onClick = { if (found != null) flow.chooseFound(found) else if (!searching) flow.searchAgain() },
        description = if (found != null) found.address.takeIf { found.name.isNotBlank() } else searchWords(searching),
        rest = found?.let { foundValue(it) },
        searching = found == null && searching,
        also = slot,
        modifier = Modifier.onFocusChanged {
            if (it.hasFocus) focus.network = slot else if (focus.network == slot) focus.network = null
        },
    )
}

/** What the search row says under its name: nothing while it searches, then that no NAS answered. */
@Composable
private fun searchWords(searching: Boolean): String? = if (searching) null else stringResource(R.string.nas_none_found)

/** A NAS found by what its ports answered: « Vérification… » meanwhile, then its protocols, or « Ne répond pas ». */
@Composable
private fun foundValue(found: FoundNas): RowValue {
    val protocols = found.protocols
    return when {
        protocols == null -> RowValue(stringResource(R.string.checking), StatusState.Pending)
        protocols.isEmpty() -> RowValue(stringResource(R.string.server_silent), StatusState.Off)
        else -> RowValue(PROTOCOLS.filter { it in protocols }.joinToString(" · ") { it.name }, technical = true)
    }
}

/** As a NAS's protocols are printed: SMB first, as the step asking for one puts it. */
private val PROTOCOLS = listOf(Protocol.SMB, Protocol.NFS)

@Composable
private fun addressParts(flow: FolderFlow, focus: FlowFocus): List<FlowPart> = listOf(
    FlowPart(FIELD_HOST, setOf(FIELD_HOST)) {
        HlTextField(
            stringResource(R.string.field_host), flow.typedHost, { flow.type(FIELD_HOST, it) },
            Modifier.focusRequester(focus[FIELD_HOST]),
            placeholder = stringResource(R.string.placeholder_host),
            supporting = stringResource(R.string.host_supporting),
            error = fieldError(flow, FIELD_HOST),
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
            onImeAction = flow::continueAddress,
        )
    },
    continuePart(flow, focus, flow::continueAddress),
)

/** « Continuer », its width kept while it says « Connexion… ». */
private fun continuePart(flow: FolderFlow, focus: FlowFocus, onClick: () -> Unit) = FlowPart(CONTINUE, setOf(CONTINUE)) {
    HlButton(
        stringResource(R.string.continue_),
        onClick,
        Modifier.padding(top = HlSpace.xs).focusRequester(focus[CONTINUE]),
        icon = HlIcons.Next,
        busy = flow.pressed?.key == CONTINUE,
        busyLabel = stringResource(R.string.signing_in),
    )
}

// Steps 2 and 3 · protocol, account --------------------------------------------------------------------------------

@Composable
private fun protocolParts(flow: FolderFlow, focus: FlowFocus): List<FlowPart> = listOf(
    FlowPart("protocols", PROTOCOLS.map { protocolKey(it) }.toSet()) {
        HlSettingGroup(null) {
            FlowRow(
                flow, focus, protocolKey(Protocol.SMB), Protocol.SMB.name, { flow.chooseProtocol(Protocol.SMB) },
                description = stringResource(R.string.protocol_smb_desc),
            )
            FlowRow(
                flow, focus, protocolKey(Protocol.NFS), Protocol.NFS.name, { flow.chooseProtocol(Protocol.NFS) },
                description = stringResource(R.string.protocol_nfs_desc),
            )
        }
    },
)

@Composable
private fun accountParts(flow: FolderFlow, focus: FlowFocus): List<FlowPart> = listOf(
    FlowPart("account", setOf(FIELD_USER, FIELD_PASSWORD)) {
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
            HlTextField(
                stringResource(R.string.field_user), flow.user, { flow.type(FIELD_USER, it) },
                Modifier.weight(1f).focusRequester(focus[FIELD_USER]),
                error = fieldError(flow, FIELD_USER),
            )
            HlTextField(
                stringResource(R.string.field_password), flow.password, { flow.type(FIELD_PASSWORD, it) },
                Modifier.weight(1f).focusRequester(focus[FIELD_PASSWORD]),
                error = fieldError(flow, FIELD_PASSWORD),
                password = true,
                imeAction = ImeAction.Done,
                onImeAction = { flow.signIn() },
            )
        }
    },
    FlowPart(SIGN_IN, setOf(SIGN_IN)) {
        HlButton(
            stringResource(R.string.sign_in),
            { flow.signIn() },
            Modifier.padding(top = HlSpace.xs).focusRequester(focus[SIGN_IN]),
            busy = flow.pressed?.key == SIGN_IN,
            busyLabel = stringResource(R.string.signing_in),
        )
    },
    FlowPart(GUEST, setOf(GUEST)) {
        HlSettingGroup(null) {
            FlowRow(
                flow, focus, GUEST, stringResource(R.string.guest), { flow.signIn(asGuest = true) },
                description = stringResource(R.string.guest_desc),
            )
        }
    },
)

// Step 4 · the shared folder ---------------------------------------------------------------------------------------

@Composable
private fun shareParts(flow: FolderFlow, focus: FlowFocus): List<FlowPart> {
    val shares = flow.shares
    val parts = mutableListOf<FlowPart>()
    if (shares.isNotEmpty()) {
        parts += FlowPart("shares", shares.map { shareKey(it) }.toSet()) {
            HlSettingGroup(null) { shares.forEach { share -> key(share.path) { ShareRow(flow, focus, share) } } }
        }
    }
    parts += FlowPart("typing", setOf(TYPE_PATH, CHANGE_ACCOUNT, NFS_ACCOUNT)) {
        HlSettingGroup(null) {
            FlowRow(
                flow, focus, TYPE_PATH, stringResource(R.string.type_path), flow::typePath,
                description = stringResource(R.string.type_path_desc),
                icon = HlIcons.Edit,
            )
            if (flow.protocol == Protocol.NFS) NfsAccountRow(flow, focus) else if (flow.signedIn) ChangeAccountRow(flow, focus)
        }
    }
    return parts
}

/**
 * A shared folder: an export by its last name over its path, a share by its name over its remark; how it stands to
 * the library after them, a share added whole disabled; an export whose clients leave this player out « Non autorisé ».
 */
@Composable
private fun ShareRow(flow: FolderFlow, focus: FlowFocus, share: FlowShare) {
    val standing = flow.standingOf(share)
    val about = if (flow.protocol == Protocol.NFS) share.path else share.remark.ifBlank { null }
    FlowRow(
        flow, focus, shareKey(share), share.name, { flow.openShare(share) },
        description = listOfNotNull(about, standingText(standing)).joinToString(FACT_GAP).ifEmpty { null },
        rest = if (share.refused) RowValue(stringResource(R.string.not_allowed), StatusState.Off) else null,
        enabled = standing !is Standing.Added,
    )
}

/** How a row stands to the library, on its line: « Déjà dans la bibliothèque », « Contient « Films » »; null apart. */
@Composable
private fun standingText(standing: Standing): String? = when (standing) {
    is Standing.Added -> stringResource(R.string.folder_overlaps_state)
    is Standing.Holds -> holdsText(standing.folders.map { it.name })
    is Standing.Inside, Standing.Apart -> null
}

/** « Contient « Films » », or how many folders of the library a folder holds. */
@Composable
private fun holdsText(names: List<String>): String = if (names.size == 1) {
    stringResource(R.string.share_holds_one, capitalised(names.single()))
} else {
    pluralStringResource(R.plurals.share_holds_many, names.size, names.size)
}

private fun capitalised(name: String) = name.replaceFirstChar { it.uppercase() }

/** « Compte NFS »: the user and group the flow presents, root by default, opened to type them. */
@Composable
private fun NfsAccountRow(flow: FolderFlow, focus: FlowFocus) {
    val identity = identityText(flow)
    FlowRow(
        flow, focus, NFS_ACCOUNT, stringResource(R.string.nfs_identity_title), flow::openNfsAccount,
        description = stringResource(R.string.nfs_identity_desc),
        rest = RowValue(identity ?: stringResource(R.string.nfs_identity_root), technical = identity != null),
        icon = HlIcons.Edit,
        valueBreaks = true,
    )
}

/** « Changer de compte », saying who the shared folders were listed as: an account, or a guest. */
@Composable
private fun ChangeAccountRow(flow: FolderFlow, focus: FlowFocus) {
    FlowRow(
        flow, focus, CHANGE_ACCOUNT, stringResource(R.string.change_account), flow::changeAccount,
        description = if (flow.listedAsGuest) {
            stringResource(R.string.change_account_guest_desc)
        } else {
            stringResource(R.string.change_account_desc, flow.listedUser.trim())
        },
        icon = HlIcons.Edit,
    )
}

@Composable
private fun pathParts(flow: FolderFlow, focus: FlowFocus): List<FlowPart> {
    val nfs = flow.protocol == Protocol.NFS
    val parts = mutableListOf(
        FlowPart(FIELD_PATH, setOf(FIELD_PATH)) {
            HlTextField(
                stringResource(R.string.field_full_path), flow.typedPath, { flow.type(FIELD_PATH, it) },
                Modifier.focusRequester(focus[FIELD_PATH]),
                placeholder = stringResource(if (nfs) R.string.placeholder_full_path_nfs else R.string.placeholder_full_path_smb),
                supporting = stringResource(if (nfs) R.string.full_path_supporting_nfs else R.string.full_path_supporting_smb),
                error = fieldError(flow, FIELD_PATH),
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                onImeAction = flow::continuePath,
            )
        },
        continuePart(flow, focus, flow::continuePath),
    )
    val proposed = flow.suggestion
    if (proposed != null) {
        parts += FlowPart(SUGGESTION, setOf(SUGGESTION)) {
            HlSettingGroup(null) {
                FlowRow(
                    flow, focus, SUGGESTION, stringResource(R.string.use_suggestion, proposed), flow::useSuggestion,
                    description = stringResource(R.string.suggested_by_nas),
                )
            }
        }
    }
    // Without the list of exports, « Compte NFS » has no step of its own: it is here.
    if (nfs && flow.noList) parts += FlowPart(NFS_ACCOUNT, setOf(NFS_ACCOUNT)) { HlSettingGroup(null) { NfsAccountRow(flow, focus) } }
    return parts
}

@Composable
private fun identityParts(flow: FolderFlow, focus: FlowFocus): List<FlowPart> = listOf(
    FlowPart("identity", setOf(FIELD_UID, FIELD_GID)) {
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
            HlTextField(
                stringResource(R.string.field_uid), flow.uid, { flow.type(FIELD_UID, it) },
                Modifier.weight(1f).focusRequester(focus[FIELD_UID]),
                placeholder = stringResource(R.string.placeholder_uid),
                error = fieldError(flow, FIELD_UID),
                keyboardType = KeyboardType.Number,
            )
            HlTextField(
                stringResource(R.string.field_gid), flow.gid, { flow.type(FIELD_GID, it) },
                Modifier.weight(1f).focusRequester(focus[FIELD_GID]),
                placeholder = stringResource(R.string.placeholder_gid),
                error = fieldError(flow, FIELD_GID),
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
                onImeAction = flow::continueIdentity,
            )
        }
    },
    continuePart(flow, focus, flow::continueIdentity),
)

// Step 5 · browse to the folder ------------------------------------------------------------------------------------

/**
 * A level: « Choisir ce dossier » saying what it holds, or why it cannot be chosen, a folder edited saved there at once
 * ([onDone]); then its subfolders, one item each, so a level of hundreds composes only what shows.
 */
@Composable
private fun browseParts(flow: FolderFlow, focus: FlowFocus, onDone: (FlowDone) -> Unit): List<FlowPart> {
    val level = flow.level ?: return emptyList()
    val share = flow.share
    val path = flow.path
    val (description, enabled) = chooseText(flow.standingOf(share, path), level)
    // A disk's root chosen is named as its row.
    val named = if (flow.protocol == Protocol.LOCAL) diskName(flow.nas) else ""
    val parts = mutableListOf(
        FlowPart(CHOOSE, setOf(CHOOSE)) {
            HlSettingGroup(null) {
                FlowRow(
                    flow, focus, CHOOSE, stringResource(R.string.choose_folder), { flow.chooseFolder(named, onDone) },
                    description = description,
                    icon = HlIcons.Check,
                    enabled = enabled,
                )
            }
        },
    )
    val folders = level.folders
    if (folders.isEmpty()) return parts
    parts += FlowPart("subfolders", emptySet()) { GroupTitle(stringResource(R.string.subfolders)) }
    folders.forEachIndexed { i, name ->
        parts += FlowPart(folderKey(name), setOf(folderKey(name)), gap = HlSpace.none) {
            PlateSlice(first = i == 0, last = i == folders.lastIndex) {
                val standing = flow.standingOf(share, path + name)
                FlowRow(
                    flow, focus, folderKey(name), name, { flow.openFolder(name) },
                    description = standingText(standing),
                    icon = HlIcons.Folder,
                    enabled = standing !is Standing.Added,
                )
            }
        }
    }
    return parts
}

/** What « Choisir ce dossier » says of the level, and whether it can be chosen: not when added, overlapping or empty. */
@Composable
private fun chooseText(standing: Standing, level: FolderLevel): Pair<String, Boolean> = when {
    standing is Standing.Added -> stringResource(R.string.folder_overlaps_state) to false
    standing is Standing.Holds -> stringResource(R.string.folder_holds_added, capitalised(standing.folders.first().name)) to false
    standing is Standing.Inside -> stringResource(R.string.folder_inside_added, capitalised(standing.folder.name)) to false
    level.isEmpty -> stringResource(R.string.folder_empty_short) to false
    holdsBoth(level.folders) -> stringResource(R.string.folder_holds_both) to true
    else -> contentText(level) to true
}

/** A level's own content: « 38 dossiers et 2 vidéos », « 412 dossiers », « 3 vidéos ». */
@Composable
private fun contentText(level: FolderLevel): String {
    val count = level.folders.size
    val folders = pluralStringResource(R.plurals.folder_count, count, count)
    val videos = pluralStringResource(R.plurals.video_count, level.videos, level.videos)
    return when {
        count > 0 && level.videos > 0 -> stringResource(R.string.folder_content, folders, videos)
        count > 0 -> folders
        else -> videos
    }
}

// Step 6 · what it holds -------------------------------------------------------------------------------------------

@Composable
private fun kindParts(flow: FolderFlow, focus: FlowFocus, onDone: (FlowDone) -> Unit): List<FlowPart> = listOf(
    FlowPart("kinds", SourceKind.entries.map { kindKey(it) }.toSet()) {
        HlSettingGroup(null) {
            FlowRow(
                flow, focus, kindKey(SourceKind.MOVIES), stringResource(R.string.library_movies), { flow.add(SourceKind.MOVIES, onDone) },
                description = stringResource(R.string.kind_movies_desc),
            )
            FlowRow(
                flow, focus, kindKey(SourceKind.SHOWS), stringResource(R.string.library_shows), { flow.add(SourceKind.SHOWS, onDone) },
                description = stringResource(R.string.kind_shows_desc),
            )
        }
    },
)

// Words ------------------------------------------------------------------------------------------------------------

@Composable
private fun fieldError(flow: FolderFlow, field: String): String? =
    flow.fieldError?.takeIf { it.field == field }?.let { flowText(it.message) }

/** A failure's sentence, its paths kept whole ([wholePath]). */
@Composable
private fun flowText(message: FlowMessage): String = when (message) {
    is FlowMessage.Silent -> stringResource(R.string.error_nas_unreachable, message.host)
    FlowMessage.LoginRefused -> stringResource(R.string.error_login_failed)
    FlowMessage.GuestRefused -> stringResource(R.string.error_guest_refused)
    is FlowMessage.NoShare -> stringResource(R.string.error_share_not_found, message.host, message.share)
    is FlowMessage.NoExport -> stringResource(R.string.error_export_not_found, wholePath(message.typed))
    is FlowMessage.NoFolder -> if (message.smb) {
        stringResource(R.string.error_subfolder_not_found_share, message.at, message.name)
    } else {
        stringResource(R.string.error_subfolder_not_found, wholePath(message.at), message.name)
    }
    is FlowMessage.Overlaps -> stringResource(overlapWords(message.standing), message.existing)
    is FlowMessage.Missing -> stringResource(message.words)
    FlowMessage.Gone -> stringResource(R.string.folder_gone)
    else -> refusalText(message)
}

/** The folder's sentence when the library has it, or holds it, or one inside it, as the folder's form says it. */
private fun overlapWords(standing: Standing): Int = when (standing) {
    is Standing.Holds -> R.string.folder_holds
    is Standing.Inside -> R.string.folder_inside
    is Standing.Added, Standing.Apart -> R.string.folder_already_added
}

/**
 * What the NAS refused, and what to do: this player, root or the NFS user presented, an account or a guest; a disk of
 * this player what Android must allow, or that it is no longer plugged in.
 */
@Composable
private fun refusalText(message: FlowMessage): String = when (message) {
    is FlowMessage.NotAllowed -> if (message.player != null) {
        stringResource(R.string.error_nfs_refused, message.player, wholePath(message.export))
    } else {
        stringResource(R.string.error_nfs_refused_no_address, wholePath(message.export))
    }
    is FlowMessage.RootRefused -> stringResource(R.string.error_nfs_root_refused, wholePath(message.path))
    is FlowMessage.AccountRefused -> stringResource(R.string.error_nfs_account_refused, message.uid, wholePath(message.path))
    is FlowMessage.Denied -> stringResource(R.string.error_smb_denied, message.user, message.share)
    is FlowMessage.GuestShare -> stringResource(R.string.error_smb_guest_share, message.share)
    is FlowMessage.DiskRefused -> stringResource(if (message.settings) R.string.disk_access_settings else R.string.disk_access_refused)
    is FlowMessage.DiskAbsent -> diskAbsent(message.disk)
    else -> stringResource(R.string.error_scan_other)
}

/**
 * A disk no longer plugged in: by its own name, else as « Le disque USB », Android naming no disk it does not have;
 * the player's storage, never unplugged, as not answering.
 */
@Composable
private fun diskAbsent(disk: FlowNas): String {
    val usb = stringResource(R.string.disk_usb)
    val named = disk.name?.takeIf { it.isNotBlank() && it != usb }
    return when {
        disk.host == Volume.INTERNAL -> stringResource(R.string.error_settings)
        named != null -> stringResource(R.string.disk_absent, named)
        else -> stringResource(R.string.disk_absent_usb)
    }
}
