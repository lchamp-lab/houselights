package app.houselights.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.houselights.BuildConfig
import app.houselights.R
import app.houselights.core.SourceConfig
import app.houselights.providers.jellyfin.JellyfinClient
import app.houselights.providers.local.FolderCounts
import app.houselights.providers.local.LocalProvider
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.ScanState
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.TmdbClient.Companion.ImageType
import app.houselights.providers.local.TmdbKeyCheck
import app.houselights.providers.local.address
import app.houselights.settings.AppSettings
import app.houselights.settings.SecretSetting
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.ArtworkImage
import app.houselights.ui.design.components.DefinitionList
import app.houselights.ui.design.components.Badge
import app.houselights.ui.design.components.ChoiceCheck
import app.houselights.ui.design.components.ChoiceOption
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.FactRow
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlChoiceSetting
import app.houselights.ui.design.components.HlDivider
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.PosterStrip
import app.houselights.ui.design.components.HlPanelButton
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.SheetConfirm
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.components.ScanProgress
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.onboarding.FolderField
import app.houselights.ui.util.UiError
import app.houselights.ui.util.scanErrorText
import app.houselights.core.CollectionThreshold

/**
 * The library: its folders or its server, then what can be done with it. [onFolderForm] opens the
 * form of a folder (a new one for null), focus on the field given; [onUseFolders] makes the folders
 * the source (or adds the first one), [onSignIn] opens the Jellyfin sign-in. The rows and cards that
 * open another screen are keyed in [rows]; "Add a folder" is also where focus goes when the last
 * folder is removed.
 */
internal fun LazyListScope.libraryPane(
    s: AppSettings,
    onFolderForm: (folderId: Long?, focus: FolderField) -> Unit,
    onUseFolders: () -> Unit,
    onSignIn: () -> Unit,
    rows: PaneRows,
) {
    when (val source = s.source) {
        SourceConfig.Local -> {
            item { Section(stringResource(R.string.section_source)) { Sources(rows, onEdit = onFolderForm) } }
            item { LibraryActions({ onFolderForm(null, FolderField.FIRST) }, onUseFolders, onSignIn, rows) }
        }
        is SourceConfig.Jellyfin -> {
            item {
                Section(stringResource(R.string.source_jellyfin)) {
                    // States, not settings: read as the back of a case, flush with the title.
                    val encrypted = JellyfinClient.isEncrypted(source.serverUrl)
                    DefinitionList(
                        listOfNotNull(
                            stringResource(R.string.label_server) to (source.serverName ?: stringResource(R.string.source_jellyfin)),
                            stringResource(R.string.label_address) to source.serverUrl,
                            stringResource(R.string.label_connection) to
                                stringResource(if (encrypted) R.string.connection_encrypted else R.string.connection_plain),
                            source.userName?.let { stringResource(R.string.label_user) to it },
                        ),
                    )
                }
            }
            item {
                HlSettingGroup(null) {
                    // A password changed or a device revoked: the sign-in opens filled in, on the password.
                    HlSettingRow(
                        stringResource(R.string.reconnect),
                        rows.focus("reconnect"),
                        description = source.userName,
                        onClick = onSignIn,
                    )
                    SourceRow(onUseFolders, onSignIn, rows.focus(SOURCE))
                }
            }
        }
        null -> {}
    }
}

/**
 * One card per watched folder, keyed in [rows]: coming back from editing a folder finds its card. The
 * card of a folder removed takes focus with it: focus moves on to the next card, else to "Add a folder".
 */
@Composable
private fun Sources(rows: PaneRows, onEdit: (folderId: Long, focus: FolderField) -> Unit) {
    val vm = settingsViewModel()
    val sources by vm.library.folders.collectAsStateWithLifecycle(emptyList())
    val scan by vm.library.scan.collectAsStateWithLifecycle()
    // Observed once for every card: each write of a scan runs it again.
    val counts by vm.library.folderCounts.collectAsStateWithLifecycle(emptyMap())
    var actionsFor by remember { mutableStateOf<SourceEntity?>(null) }
    // The folder being removed, and where focus goes once its card has gone.
    var removing by remember { mutableStateOf<Pair<Long, FocusRequester>?>(null) }
    LaunchedEffect(sources) {
        val (id, next) = removing ?: return@LaunchedEffect
        if (sources.none { it.id == id }) {
            removing = null
            next.tryRequestFocus()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
        // A folder being removed waits for the file a scan may be reading in it: said, not left to look kept.
        sources.filter { it.id in vm.removing }.forEach { source ->
            HlStatus(StatusState.Pending, stringResource(R.string.folder_removing, sourceName(source)))
        }
        sources.forEach { source ->
            key(source.id) {
                SourceCard(
                    source,
                    counts = counts[source.id],
                    scan = scan.takeIf { it.running && it.sourceId == source.id },
                    error = scan.errors[source.id],
                    removeError = vm.errorOf(SettingAction.REMOVE_FOLDER, source.id),
                    onClick = { actionsFor = source },
                    modifier = rows.focus(folderRow(source.id)),
                )
            }
        }
    }
    actionsFor?.let { source ->
        SourceActions(
            source,
            error = scan.errors[source.id],
            onDismiss = { actionsFor = null },
            onEdit = { focus -> onEdit(source.id, focus) },
            // In the app's scope: leaving the pane while it waits for a scan's file never cuts it off.
            onRemove = {
                val next = sources.getOrNull(sources.indexOfFirst { it.id == source.id } + 1)
                removing = source.id to rows.requester(next?.let { folderRow(it.id) } ?: ADD_FOLDER)
                vm.removeFolder(source)
            },
        )
    }
}

/**
 * What can be done with a watched folder, beside the list: scan it now, edit its address or sign-in,
 * or remove it, which asks first, in the sheet itself ([SheetConfirm]). When its last scan found the
 * NAS silent, the share gone or the sign-in refused ([error]), editing comes first, with focus: an
 * address, a share or a password has likely changed, and editing keeps what removing the folder
 * would lose. [onEdit] then opens the form on the field to change.
 */
@Composable
private fun SourceActions(
    source: SourceEntity,
    error: ScanError?,
    onDismiss: () -> Unit,
    onEdit: (focus: FolderField) -> Unit,
    onRemove: () -> Unit,
) {
    val vm = settingsViewModel()
    val posters by remember(source.id) { vm.library.latestPosters(source, HEADER_POSTERS) }.collectAsStateWithLifecycle(emptyList())
    val kind = stringResource(if (source.kind == SourceKind.MOVIES) R.string.library_movies else R.string.library_shows)
    val scan = remember { FocusRequester() }
    val edit = remember { FocusRequester() }
    // Read once: a scan ending while the sheet is open does not move its rows under focus.
    val fix = remember { editFixes(error) }
    HlSideSheet(
        title = sourceName(source),
        onDismiss = onDismiss,
        facts = listOf(kind.takeIf { !source.name.equals(it, ignoreCase = true) }, source.protocol.name),
        description = source.address(withScheme = false),
        artwork = if (posters.isEmpty()) {
            null
        } else {
            {
                PosterStrip(
                    remember(posters) { posters.map { LocalProvider.image(it, ImageType.POSTER) } },
                    HEADER_POSTERS,
                    slotWidth = HlSize.rowPreviewPoster,
                    gap = HlSpace.xxs,
                    shape = HlShapes.artwork,
                )
            }
        },
        initialFocus = if (fix != null) edit else scan,
    ) { sheet ->
        SheetConfirm(
            question = stringResource(R.string.remove_folder_confirm, sourceName(source)),
            consequence = stringResource(R.string.remove_folder_desc),
            confirm = stringResource(R.string.remove),
            onConfirm = { sheet.dismiss(onRemove) },
        ) { ask, origin ->
            // Every action says what it does, in one line: the rows keep one height.
            @Composable
            fun ScanRow() = HlSettingRow(
                stringResource(R.string.scan_now),
                Modifier.focusRequester(scan),
                description = stringResource(R.string.scan_now_desc),
                onClick = { sheet.dismiss { vm.library.scan() } },
                chevron = false,
            )

            @Composable
            fun EditRow() = HlSettingRow(
                stringResource(R.string.edit_folder),
                Modifier.focusRequester(edit),
                description = stringResource(
                    when (fix) {
                        ScanErrorKind.LOGIN_FAILED -> R.string.edit_folder_login_desc
                        ScanErrorKind.UNREACHABLE -> R.string.edit_folder_unreachable_desc
                        ScanErrorKind.NOT_FOUND -> R.string.edit_folder_not_found_desc
                        else -> R.string.edit_folder_row_desc
                    }
                ),
                onClick = { sheet.dismiss { onEdit(FolderField.after(fix)) } },
            )
            if (fix != null) {
                EditRow()
                ScanRow()
            } else {
                ScanRow()
                EditRow()
            }
            Spacer(Modifier.height(HlSpace.s))
            HlDivider()
            Spacer(Modifier.height(HlSpace.s))
            HlSettingRow(
                stringResource(R.string.remove_folder),
                origin,
                description = stringResource(R.string.remove_folder_row_desc),
                onClick = ask,
                chevron = false,
            )
        }
    }
}

/**
 * What editing a folder likely fixes, after its last scan's [error]: a NAS silent (its address
 * changed), a share or path gone (renamed), a sign-in refused (its password changed). Null for
 * anything else, which editing would not mend.
 */
internal fun editFixes(error: ScanError?): ScanErrorKind? = error?.kind?.takeIf {
    it == ScanErrorKind.UNREACHABLE || it == ScanErrorKind.NOT_FOUND || it == ScanErrorKind.LOGIN_FAILED
}

@Composable
private fun sourceName(source: SourceEntity) = source.name.replaceFirstChar { it.uppercase() }

/**
 * A watched folder, headed by its latest posters from edge to edge (its name, set like a one-sheet,
 * before it has any); then its name and kind, where it lives, what it holds ([counts], null until
 * read) and how its last scan went, or the [scan] going on through it. Removing it failed
 * ([removeError]): said there, where focus is back. OK opens its actions.
 */
@Composable
private fun SourceCard(
    source: SourceEntity,
    counts: FolderCounts?,
    scan: ScanState?,
    error: ScanError?,
    removeError: UiError?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Hl.colors
    val library = settingsViewModel().library
    val count = counts?.titles ?: 0
    val ultraHd = counts?.ultraHd ?: 0
    val posters by remember(source.id) { library.latestPosters(source, STRIP) }.collectAsStateWithLifecycle(emptyList())
    val kind = stringResource(if (source.kind == SourceKind.MOVIES) R.string.library_movies else R.string.library_shows)

    HlPanelButton(onClick, modifier.fillMaxWidth()) {
        Column {
            PosterStrip(remember(posters) { posters.map { LocalProvider.image(it, ImageType.POSTER) } }, STRIP, name = sourceName(source))
            Column(Modifier.padding(HlInset.card)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                    Text(sourceName(source), style = Hl.type.lead, color = c.content)
                    if (!source.name.equals(kind, ignoreCase = true)) Badge(kind)
                    Badge(source.protocol.name)
                }
                Spacer(Modifier.height(HlSpace.xxs))
                Text(
                    source.address(withScheme = false),
                    style = Hl.type.meta,
                    color = c.contentSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(HlSpace.m))
                when {
                    scan != null -> ScanProgress(scan.processed, scan.total, current = scan.current)
                    removeError != null -> AttentionNote(stringResource(R.string.folder_not_removed, stringResource(removeError.message)))
                    error != null -> AttentionNote(scanErrorText(error, source.host))
                    else -> FactRow(
                        listOf(
                            pluralStringResource(R.plurals.title_count, count, count),
                            if (ultraHd > 0) pluralStringResource(R.plurals.uhd_count, ultraHd, ultraHd) else null,
                            lastScanned(source.lastScanAt),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryActions(
    onAddFolder: () -> Unit,
    onUseFolders: () -> Unit,
    onSignIn: () -> Unit,
    rows: PaneRows,
) {
    val vm = settingsViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    val scan by vm.library.scan.collectAsStateWithLifecycle()
    val sources by vm.library.folders.collectAsStateWithLifecycle(emptyList())
    var editKey by remember { mutableStateOf(false) }
    val lastScan = sources.maxOfOrNull { it.lastScanAt } ?: 0L
    val hasBuiltIn = BuildConfig.TMDB_API_KEY.isNotBlank()

    HlSettingGroup(null, Modifier.revealOnFocus()) {
        HlSettingRow(
            stringResource(R.string.add_folder),
            onClick = onAddFolder,
            chevron = false,
            trailing = { PlusIcon() },
            modifier = rows.focus(ADD_FOLDER),
        )
        HlSettingRow(
            stringResource(R.string.scan_now),
            // The counter's notation, as on the card of the folder being read.
            value = if (scan.running) {
                stringResource(R.string.scan_progress, scan.processed, scan.total)
            } else {
                relativeTime(lastScan, alone = true)
            },
            onClick = { vm.library.scan() },
            chevron = false,
        )
        HlSettingRow(
            stringResource(R.string.refresh_metadata),
            description = stringResource(R.string.refresh_metadata_desc),
            onClick = { vm.library.scan(refreshMetadata = true) },
            chevron = false,
        )
        // The last scan could not ask TMDB: said where the key is changed, which may fix it.
        val issue = scan.tmdbIssue
        HlSettingRow(
            stringResource(R.string.tmdb_key),
            value = stringResource(
                when {
                    s.tmdbApiKey != null -> R.string.tmdb_personal
                    hasBuiltIn -> R.string.tmdb_builtin_short
                    else -> R.string.tmdb_missing_short
                }
            ),
            description = issue?.let { stringResource(it.title) },
            // Your own key is your choice; none at all, or one TMDB did not take, needs attention.
            valueLit = s.tmdbApiKey != null && issue == null,
            valueAttention = (s.tmdbApiKey == null && !hasBuiltIn) || issue != null,
            onClick = { editKey = true },
        )
        CollectionsRow()
        SourceRow(onUseFolders, onSignIn, rows.focus(SOURCE))
    }
    if (editKey) TmdbKeySheet(issue = scan.tmdbIssue, onDismiss = { editKey = false })
}

/** When a saga shows as a collection: from two films (default), always, or never. */
@Composable
private fun CollectionsRow() {
    val vm = settingsViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    @Composable
    fun option(label: Int, description: Int) = ChoiceOption(stringResource(label), stringResource(description))
    val options = listOf(
        CollectionThreshold.TWO_FILMS to option(R.string.collections_two, R.string.collections_two_desc),
        CollectionThreshold.ANY to option(R.string.collections_all, R.string.collections_all_desc),
        CollectionThreshold.HIDDEN to option(R.string.collections_off, R.string.collections_off_desc),
    )
    val failure = rowFailure(vm.errorOf(SettingAction.COLLECTIONS))
    HlChoiceSetting(
        stringResource(R.string.collections_setting),
        options = options.map { it.second },
        selected = options.indexOfFirst { it.first == s.collectionMinFilms }.coerceAtLeast(0),
        onSelect = { i -> vm.setCollectionMinFilms(options[i].first) },
        description = stringResource(R.string.collections_setting_desc),
        attention = failure?.value,
    )
    RowFailureNote(failure)
}

/** The source in use, and what changing it keeps; OK opens [SourceSheet]. */
@Composable
private fun SourceRow(onUseFolders: () -> Unit, onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    val vm = settingsViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    var choose by remember { mutableStateOf(false) }
    val server = s.source is SourceConfig.Jellyfin
    // Making the folders the source failed (the device's storage): said here, where focus comes back.
    val failure = rowFailure(vm.errorOf(SettingAction.SOURCE))
    HlSettingRow(
        stringResource(R.string.change_source_row),
        modifier,
        value = failure?.value ?: stringResource(if (server) R.string.source_jellyfin else R.string.source_local),
        description = stringResource(sourceKeeps(s.source)),
        valueAttention = failure != null,
        onClick = { choose = true },
    )
    RowFailureNote(failure)
    if (choose) {
        // Both choices open another screen: the sheet closes at once.
        SourceSheet(
            source = s.source,
            onDismiss = { choose = false },
            onUseFolders = {
                choose = false
                onUseFolders()
            },
            onSignIn = {
                choose = false
                onSignIn()
            },
        )
    }
}

/** What changing from [source] keeps, said under the row and at the head of its sheet. */
private fun sourceKeeps(source: SourceConfig?): Int =
    if (source is SourceConfig.Jellyfin) R.string.change_source_jellyfin_desc else R.string.change_source_local_desc

/**
 * Where the library comes from: your folders or a Jellyfin server, the current one checked. A
 * choice leads to what the new source needs (the folders kept, a first folder, signing in); the
 * current source stays until the new one is saved. Leaving a server for the folders asks first.
 */
@Composable
private fun SourceSheet(source: SourceConfig?, onDismiss: () -> Unit, onUseFolders: () -> Unit, onSignIn: () -> Unit) {
    val vm = settingsViewModel()
    val folders by vm.library.folders.collectAsStateWithLifecycle(emptyList())
    val server = source as? SourceConfig.Jellyfin
    val local = remember { FocusRequester() }
    val jellyfin = remember { FocusRequester() }
    val serverName = server?.serverName ?: stringResource(R.string.source_jellyfin)
    HlSideSheet(
        title = stringResource(R.string.change_source),
        onDismiss = onDismiss,
        subject = if (server != null) serverName else stringResource(R.string.source_local),
        facts = sourceFacts(server),
        description = stringResource(sourceKeeps(source)),
        initialFocus = if (server != null) jellyfin else local,
    ) { sheet ->
        SheetConfirm(
            question = stringResource(R.string.sign_out_confirm, serverName),
            consequence = stringResource(if (folders.isEmpty()) R.string.sign_out_first_folder else R.string.sign_out_desc),
            confirm = stringResource(R.string.sign_out),
            onConfirm = onUseFolders,
        ) { ask, origin ->
            HlSettingRow(
                stringResource(R.string.source_local),
                origin.focusRequester(local),
                description = foldersLine(folders.size),
                icon = HlIcons.Folder,
                onClick = {
                    when {
                        source is SourceConfig.Jellyfin -> ask()
                        source == SourceConfig.Local && folders.isNotEmpty() -> sheet.dismiss()
                        else -> onUseFolders()
                    }
                },
                chevron = false,
                trailing = if (source == SourceConfig.Local) ({ ChoiceCheck() }) else null,
            )
            HlSettingRow(
                stringResource(R.string.source_jellyfin),
                Modifier.focusRequester(jellyfin),
                description = stringResource(if (server != null) R.string.source_jellyfin_other else R.string.source_jellyfin_desc),
                icon = HlIcons.Server,
                onClick = onSignIn,
                chevron = false,
                trailing = if (server != null) ({ ChoiceCheck() }) else null,
            )
        }
    }
}

/** What the current source is, under its name: the server's kind and user, or the titles of the folders. */
@Composable
private fun sourceFacts(server: SourceConfig.Jellyfin?): List<String?> {
    val titles by settingsViewModel().library.titleCount.collectAsStateWithLifecycle(0)
    return if (server != null) {
        listOf(stringResource(R.string.source_jellyfin).takeIf { server.serverName != null }, server.userName)
    } else {
        listOf(pluralStringResource(R.plurals.title_count, titles, titles))
    }
}

/** What choosing the folders brings: those already added, or the first one to add. */
@Composable
private fun foldersLine(count: Int): String =
    if (count == 0) stringResource(R.string.source_local_first) else pluralStringResource(R.plurals.source_local_folders, count, count)

@Composable
private fun PlusIcon() {
    androidx.tv.material3.Icon(HlIcons.Add, contentDescription = null, modifier = Modifier.width(HlSize.icon))
}

/**
 * The TMDB key: the one in use, at the head, with what the last scan met ([issue]); a field for a
 * personal key, checked with TMDB when saved (a key TMDB refuses is not kept); once there is one,
 * the way back to the built-in key. How the last key given went is the ViewModel's: the sheet may
 * close while TMDB checks it, and open again on the work under way, then on its outcome.
 */
@Composable
private fun TmdbKeySheet(issue: UiError?, onDismiss: () -> Unit) {
    val vm = settingsViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    val attempt = vm.tmdbKey
    val outcome = attempt?.outcome
    // The key given last while it is checked or when it was not taken, else the one saved.
    var key by remember { mutableStateOf((attempt?.takeIf { outcome != ACCEPTED }?.key ?: s.tmdbApiKey).orEmpty()) }
    // The attempt this sheet waits on: its own, or the one under way as it opened. One that ended
    // before is no news: its key taken never closes this sheet.
    var waitingOn by remember { mutableStateOf(attempt?.takeIf { outcome == null }?.number) }
    val checking = vm.savingTmdbKey
    val refused = outcome == REFUSED
    val unchecked = outcome == UNCHECKED
    val notSaved = (outcome as? TmdbKeySave.Failed)?.error
    val field = remember { FocusRequester() }
    val hasBuiltIn = BuildConfig.TMDB_API_KEY.isNotBlank()
    val personal = s.tmdbApiKey != null
    HlSideSheet(
        stringResource(R.string.tmdb_key),
        onDismiss,
        description = stringResource(R.string.tmdb_desc),
        initialFocus = field,
        status = {
            TmdbKeyStatus(personal, hasBuiltIn, unchecked, lost = SecretSetting.TMDB_KEY in s.lostSecrets, issue.takeUnless { unchecked })
        },
    ) { sheet ->
        // Taken: the sheet closes. Refused: focus goes back to the key. Not checked, or not saved: the
        // sheet stays to say so.
        LaunchedEffect(attempt) {
            if (attempt == null || attempt.number != waitingOn) return@LaunchedEffect
            when (attempt.outcome) {
                ACCEPTED -> sheet.dismiss()
                REFUSED -> field.tryRequestFocus()
                else -> Unit
            }
        }

        fun save(typed: String) {
            vm.saveTmdbApiKey(typed)?.let { waitingOn = it }
        }
        // The sheet already names the key: the field's label is only read out.
        HlTextField(
            stringResource(R.string.tmdb_key_personal),
            key,
            {
                key = it
                vm.editTmdbKey()
            },
            Modifier.fillMaxWidth().focusRequester(field),
            placeholder = stringResource(R.string.tmdb_key_personal),
            showLabel = false,
            supporting = stringResource(if (hasBuiltIn) R.string.tmdb_key_builtin else R.string.tmdb_key_free),
            error = keyError(refused, notSaved),
            password = true,
            imeAction = ImeAction.Done,
            onImeAction = { save(key) },
        )
        Spacer(Modifier.height(HlSpace.l))
        HlButton(
            stringResource(R.string.save),
            onClick = { save(key) },
            icon = HlIcons.Check,
            emphasis = Emphasis.Primary,
            busy = checking,
            busyLabel = stringResource(R.string.checking),
        )
        if (personal && hasBuiltIn) {
            Spacer(Modifier.height(HlSpace.l))
            HlSettingRow(
                stringResource(R.string.tmdb_key_revert),
                description = stringResource(R.string.tmdb_key_revert_desc),
                // Never refused nor left unchecked: taken, it closes the sheet too.
                onClick = { save("") },
                chevron = false,
            )
        }
    }
}

/** What a key typed meets, under its field: TMDB refused it, or it could not be saved. */
@Composable
private fun keyError(refused: Boolean, notSaved: UiError?): String? = when {
    refused -> stringResource(R.string.error_tmdb_key_refused_title)
    notSaved != null -> stringResource(notSaved.message)
    else -> null
}

/** How saving a key went, as the TMDB sheet answers it. */
private val ACCEPTED = TmdbKeySave.Checked(TmdbKeyCheck.ACCEPTED)
private val REFUSED = TmdbKeySave.Checked(TmdbKeyCheck.REFUSED)
private val UNCHECKED = TmdbKeySave.Checked(TmdbKeyCheck.UNCHECKED)

/**
 * The key in use, at the head of its sheet: the user's own (said unchecked when TMDB could not be
 * asked), the built-in one, or none, which calls for attention. A key saved that could not be read
 * again ([lost]) is said first: it is to be typed again. Then what the last scan met ([issue]: the
 * key refused, TMDB out of reach).
 */
@Composable
private fun TmdbKeyStatus(personal: Boolean, hasBuiltIn: Boolean, unchecked: Boolean, lost: Boolean, issue: UiError?) {
    when {
        lost && !personal -> HlStatus(
            StatusState.Attention,
            stringResource(R.string.tmdb_key_lost),
            detail = stringResource(if (hasBuiltIn) R.string.tmdb_key_lost_builtin else R.string.tmdb_key_lost_none),
        )
        issue != null && (personal || hasBuiltIn) -> HlStatus(
            StatusState.Attention,
            stringResource(if (personal) R.string.tmdb_key_personal else R.string.tmdb_key_builtin_state),
            detail = stringResource(if (issue == UiError.TMDB_KEY_REFUSED) R.string.tmdb_scan_key_refused else R.string.tmdb_scan_offline),
        )
        personal -> HlStatus(
            StatusState.On,
            stringResource(R.string.tmdb_key_personal),
            detail = if (unchecked) stringResource(R.string.tmdb_key_unchecked) else null,
        )
        hasBuiltIn -> HlStatus(StatusState.On, stringResource(R.string.tmdb_key_builtin_state))
        else -> HlStatus(
            StatusState.Attention,
            stringResource(R.string.tmdb_key_none),
            detail = stringResource(R.string.tmdb_key_none_desc),
        )
    }
}

/** Posters in a source card's strip. */
private const val STRIP = 6

/** Posters at the head of a folder's sheet. */
private const val HEADER_POSTERS = 3

private const val ADD_FOLDER = "add_folder"
private const val SOURCE = "source"

/** A folder's card, among the pane's rows. */
private fun folderRow(id: Long) = "folder-$id"
