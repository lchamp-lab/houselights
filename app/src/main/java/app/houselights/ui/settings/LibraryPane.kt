package app.houselights.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import app.houselights.providers.local.TmdbClient
import app.houselights.providers.local.TmdbClient.Companion.ImageType
import app.houselights.providers.local.TmdbKeyCheck
import app.houselights.providers.local.address
import app.houselights.settings.AppSettings
import app.houselights.settings.SecretSetting
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.ChoiceCheck
import app.houselights.ui.design.components.ChoiceOption
import app.houselights.ui.design.components.FactPlate
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlChoiceSetting
import app.houselights.ui.design.components.HlChoiceSheet
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.PosterThumb
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.PlateFact
import app.houselights.ui.design.components.SHOWCASE_SLOTS
import app.houselights.ui.design.components.Showcase
import app.houselights.ui.design.components.ShowcaseRow
import app.houselights.ui.design.components.SheetConfirm
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.components.ScanProgress
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.onboarding.FolderField
import app.houselights.ui.util.UiError
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.formatCount
import app.houselights.ui.util.scanErrorText
import app.houselights.ui.util.sourceKindName
import app.houselights.ui.util.toUiError
import app.houselights.core.CollectionThreshold
import java.text.Collator
import java.util.Locale

/** The library pane: its folders' showcases or its server, then actions; rows that open a screen are keyed in [rows]. */
internal fun LazyListScope.libraryPane(
    s: AppSettings,
    onFolderForm: (folderId: Long?, focus: FolderField) -> Unit,
    onUseFolders: () -> Unit,
    onSignIn: () -> Unit,
    rows: PaneRows,
) {
    when (val source = s.source) {
        SourceConfig.Local -> {
            item { Section(stringResource(R.string.section_source), aside = librarySummary()) { Showcases(rows, onEdit = onFolderForm) } }
            item { LibraryActions({ onFolderForm(null, FolderField.FIRST) }, rows) }
            item { FilmData() }
            item { HlSettingGroup(null, Modifier.revealOnFocus()) { SourceRow(onUseFolders, onSignIn, rows.focus(SOURCE)) } }
        }
        is SourceConfig.Jellyfin -> {
            item {
                Section(stringResource(R.string.section_source)) {
                    // States, not settings: a case's back, the address under its facts.
                    val encrypted = JellyfinClient.isEncrypted(source.serverUrl)
                    FactPlate(note = source.serverUrl) {
                        PlateFact(
                            stringResource(R.string.label_server),
                            source.serverName ?: stringResource(R.string.source_jellyfin),
                            Modifier.weight(1f, fill = false),
                        )
                        source.userName?.let { PlateFact(stringResource(R.string.label_user), it, Modifier.weight(1f, fill = false)) }
                        PlateFact(
                            stringResource(R.string.label_connection),
                            stringResource(if (encrypted) R.string.connection_encrypted else R.string.connection_plain),
                        )
                    }
                }
            }
            item {
                HlSettingGroup(null, Modifier.revealOnFocus()) {
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

/** "12 titres dans 2 dossiers", beside the pane's title; null without folders. */
@Composable
private fun librarySummary(): String? {
    val library = settingsViewModel().library
    val folders by library.folders.collectAsStateWithLifecycle(emptyList())
    val titles by library.titleCount.collectAsStateWithLifecycle(0)
    if (folders.isEmpty()) return null
    return stringResource(
        R.string.library_in_folders,
        pluralStringResource(R.plurals.title_count, titles, titles),
        pluralStringResource(R.plurals.folder_count, folders.size, folders.size),
    )
}

/** One showcase per folder, keyed in [rows]; when a folder is removed, focus moves to the next one, else to Add. */
@Composable
private fun Showcases(rows: PaneRows, onEdit: (folderId: Long, focus: FolderField) -> Unit) {
    val vm = settingsViewModel()
    val sources by vm.library.folders.collectAsStateWithLifecycle(emptyList())
    val scan by vm.library.scan.collectAsStateWithLifecycle()
    // Collected once for all showcases: each scan write re-runs it.
    val counts by vm.library.folderCounts.collectAsStateWithLifecycle(emptyMap())
    var actionsFor by remember { mutableStateOf<SourceEntity?>(null) }
    // The folder being removed, and where focus goes once its showcase has gone.
    var removing by remember { mutableStateOf<Pair<Long, FocusRequester>?>(null) }
    LaunchedEffect(sources) {
        val (id, next) = removing ?: return@LaunchedEffect
        if (sources.none { it.id == id }) {
            removing = null
            next.tryRequestFocus()
        }
    }

    if (sources.isNotEmpty()) {
        ShowcaseRow {
            items(sources, key = { it.id }) { source ->
                FolderShowcase(
                    source,
                    counts = counts[source.id],
                    scan = scan.takeIf { it.running && it.sourceId == source.id },
                    error = scan.errors[source.id],
                    removeError = vm.errorOf(SettingAction.REMOVE_FOLDER, source.id),
                    // Removal waits for any file a scan is reading in the folder: say so rather than look stuck.
                    removing = source.id in vm.removing,
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
            // Runs in the app's scope, so leaving the pane never cancels it.
            onRemove = {
                val next = sources.getOrNull(sources.indexOfFirst { it.id == source.id } + 1)
                removing = source.id to rows.requester(next?.let { folderRow(it.id) } ?: ADD_FOLDER)
                vm.removeFolder(source)
            },
        )
    }
}

/** A folder's sheet: scan, edit or remove (confirmed in place); Edit comes first after a failure it can fix. */
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
    val kind = sourceKindName(source.kind)
    val scan = remember { FocusRequester() }
    val edit = remember { FocusRequester() }
    // Read once: a scan ending while the sheet is open does not move its rows under focus.
    val fix = remember { editFixes(error) }
    HlSideSheet(
        title = sourceName(source),
        onDismiss = onDismiss,
        facts = listOf(kind.takeIf { !source.name.equals(it, ignoreCase = true) }),
        badges = listOf(source.protocol.name),
        description = source.address(withScheme = false),
        artwork = if (posters.isEmpty()) {
            null
        } else {
            {
                // Its latest posters, as tall as the title block; empty frames for the slots left.
                val images = remember(posters) { posters.map { LocalProvider.image(it, ImageType.POSTER) } }
                Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xxs)) {
                    repeat(HEADER_POSTERS) { PosterThumb(images.getOrNull(it), width = null) }
                }
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
            HlSettingGroup(null) {
                if (fix != null) {
                    EditRow()
                    ScanRow()
                } else {
                    ScanRow()
                    EditRow()
                }
                HlSettingRow(
                    stringResource(R.string.remove_folder),
                    origin,
                    description = stringResource(R.string.remove_folder_row_desc),
                    onClick = ask,
                )
            }
        }
    }
}

/** The scan error editing likely fixes (silent NAS, missing share or path, refused sign-in); null otherwise. */
internal fun editFixes(error: ScanError?): ScanErrorKind? = error?.kind?.takeIf {
    it == ScanErrorKind.UNREACHABLE || it == ScanErrorKind.NOT_FOUND || it == ScanErrorKind.LOGIN_FAILED
}

@Composable
private fun sourceName(source: SourceEntity) = source.name.replaceFirstChar { it.uppercase() }

/** A folder's [Showcase]: its [counts] (null until read), its last scan, or what it is doing ([scan], [removing]). */
@Composable
private fun FolderShowcase(
    source: SourceEntity,
    counts: FolderCounts?,
    scan: ScanState?,
    error: ScanError?,
    removeError: UiError?,
    removing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val library = settingsViewModel().library
    val posters by remember(source.id) { library.latestPosters(source, SHOWCASE_SLOTS) }.collectAsStateWithLifecycle(emptyList())
    val kind = sourceKindName(source.kind)
    val notRemoved = removeError?.let { stringResource(R.string.folder_not_removed, stringResource(it.message)) }
    val failed = error?.let { scanErrorText(it, source.host) }
    val removingText = stringResource(R.string.folder_removing, sourceName(source))
    Showcase(
        name = sourceName(source),
        mark = source.protocol.name,
        posters = remember(posters) { posters.map { LocalProvider.image(it, ImageType.POSTER) } },
        onClick = onClick,
        modifier = modifier,
        // Never read is no state done: a plain fact, first as the state would be; then its figures, the first in
        // white, and what it holds after them.
        facts = listOf(lastScanned(source.lastScanAt).takeIf { source.lastScanAt <= 0 }) + folderFacts(counts) +
            kind.takeIf { !source.name.equals(it, ignoreCase = true) },
        state = lastScanned(source.lastScanAt).takeIf { source.lastScanAt > 0 },
        status = when {
            removing -> ({ HlStatus(StatusState.Pending, removingText) })
            scan != null -> ({ ScanProgress(scan.processed, scan.total, current = scan.current) })
            // On the facts' one line: the folder's sheet says it in full.
            notRemoved != null -> ({ AttentionNote(notRemoved, maxLines = 1) })
            failed != null -> ({ AttentionNote(failed, maxLines = 1) })
            else -> null
        },
    )
}

/** What a folder holds: its shows and their episodes when it holds shows alone, else its titles and how many in Ultra HD. */
@Composable
private fun folderFacts(counts: FolderCounts?): List<String?> {
    val titles = counts?.titles ?: 0
    val shows = counts?.shows ?: 0
    if (shows > 0 && shows == titles) {
        val episodes = counts?.episodes ?: 0
        return listOf(
            pluralStringResource(R.plurals.numbers_shows, shows, formatCount(shows.toLong())),
            pluralStringResource(R.plurals.numbers_episodes, episodes, formatCount(episodes.toLong())).takeIf { episodes > 0 },
        )
    }
    val ultraHd = counts?.ultraHd ?: 0
    return listOf(
        pluralStringResource(R.plurals.title_count, titles, titles),
        if (ultraHd > 0) pluralStringResource(R.plurals.uhd_count, ultraHd, ultraHd) else null,
    )
}

/** Scan, add a folder, read every title again: the library's three actions, on one plate. */
@Composable
private fun LibraryActions(onAddFolder: () -> Unit, rows: PaneRows) {
    val vm = settingsViewModel()
    val scan by vm.library.scan.collectAsStateWithLifecycle()
    HlSettingGroup(null, Modifier.revealOnFocus()) {
        HlSettingRow(
            stringResource(R.string.scan_now),
            description = stringResource(R.string.scan_now_desc),
            // The counter's notation, as on the scanning folder's showcase.
            value = if (scan.running) stringResource(R.string.scan_progress, scan.processed, scan.total) else null,
            valueStyle = Hl.type.jaquette,
            onClick = { vm.library.scan() },
        )
        HlSettingRow(
            stringResource(R.string.add_folder),
            rows.focus(ADD_FOLDER),
            description = stringResource(R.string.add_folder_row_desc),
            onClick = onAddFolder,
        )
        HlSettingRow(
            stringResource(R.string.refresh_metadata),
            description = stringResource(R.string.refresh_metadata_desc),
            onClick = { vm.library.scan(refreshMetadata = true) },
        )
    }
}

/** The films' details: the TMDB key, their language, and when sagas show. */
@Composable
private fun FilmData() {
    val vm = settingsViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    val scan by vm.library.scan.collectAsStateWithLifecycle()
    var editKey by remember { mutableStateOf(false) }
    val hasBuiltIn = BuildConfig.TMDB_API_KEY.isNotBlank()
    HlSettingGroup(stringResource(R.string.credit_data), Modifier.revealOnFocus()) {
        // TMDB failed during the last scan: report it on the key row, where it may be fixed.
        val issue = scan.tmdbIssue?.toUiError()
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
            // A personal key is lit; no key, or one TMDB refused, needs attention.
            valueLit = s.tmdbApiKey != null && issue == null,
            valueAttention = (s.tmdbApiKey == null && !hasBuiltIn) || issue != null,
            onClick = { editKey = true },
        )
        MetadataLanguageRow()
        CollectionsRow()
    }
    if (editKey) TmdbKeySheet(issue = scan.tmdbIssue?.toUiError(), onDismiss = { editKey = false })
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

/** TMDB's language: the device's, or one of [TmdbClient.LANGUAGES]; a new one reads every title again. */
@Composable
private fun MetadataLanguageRow() {
    val vm = settingsViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    val device by appContainer().deviceLocale.collectAsStateWithLifecycle()
    val tags = remember { TmdbClient.LANGUAGES.sortedWith(compareBy(Collator.getInstance(Locale.ROOT), ::metadataLanguageName)) }
    var choosing by remember { mutableStateOf(false) }
    val failure = rowFailure(vm.errorOf(SettingAction.METADATA_LANGUAGE))
    val title = stringResource(R.string.metadata_language)
    HlSettingRow(
        title,
        value = failure?.value ?: metadataLanguageName(s.metadataLanguage ?: device.toLanguageTag()),
        description = stringResource(R.string.metadata_language_desc),
        valueAttention = failure != null,
        onClick = { choosing = true },
    )
    RowFailureNote(failure)
    if (choosing) {
        // Each language under its own name, then in the app's when that differs.
        val deviceOption = ChoiceOption(stringResource(R.string.device_language), metadataLanguageName(device.toLanguageTag()))
        val options = listOf(deviceOption) +
            tags.map { tag ->
                val own = metadataLanguageName(tag)
                ChoiceOption(own, metadataLanguageName(tag, Locale.getDefault()).takeUnless { it.equals(own, ignoreCase = true) })
            }
        HlChoiceSheet(
            title,
            options,
            selected = s.metadataLanguage?.let { tags.indexOf(it) + 1 } ?: 0,
            onSelect = { i -> vm.setMetadataLanguage(tags.getOrNull(i - 1)) },
            onDismiss = { choosing = false },
            description = stringResource(R.string.metadata_language_sheet),
        )
    }
}

/** [tag] named in [locale], by default its own ("Deutsch"), with its region where several are offered ("Français (Canada)"). */
private fun metadataLanguageName(tag: String, locale: Locale = Locale.forLanguageTag(tag)): String {
    val language = Locale.forLanguageTag(tag)
    val regions = TmdbClient.LANGUAGES.count { Locale.forLanguageTag(it).language == language.language } > 1
    val name = if (regions) language.getDisplayName(locale) else language.getDisplayLanguage(locale)
    return name.replaceFirstChar { it.titlecase(locale) }
}

/** The current source and what changing it keeps; OK opens [SourceSheet]. */
@Composable
private fun SourceRow(onUseFolders: () -> Unit, onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    val vm = settingsViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    var choose by remember { mutableStateOf(false) }
    val server = s.source is SourceConfig.Jellyfin
    // Switching to the folders failed (storage): shown here, where focus returns.
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

/** What changing from [source] keeps, under the row and atop its sheet. */
private fun sourceKeeps(source: SourceConfig?): Int =
    if (source is SourceConfig.Jellyfin) R.string.change_source_jellyfin_desc else R.string.change_source_local_desc

/** Folders or a Jellyfin server, the current one checked; leaving a server for the folders asks first. */
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
        facts = listOf(if (server != null) serverName else stringResource(R.string.source_local)) + sourceFacts(server),
        description = stringResource(sourceKeeps(source)),
        initialFocus = if (server != null) jellyfin else local,
    ) { sheet ->
        SheetConfirm(
            question = stringResource(R.string.sign_out_confirm, serverName),
            consequence = stringResource(if (folders.isEmpty()) R.string.sign_out_first_folder else R.string.sign_out_desc),
            confirm = stringResource(R.string.sign_out),
            onConfirm = onUseFolders,
        ) { ask, origin ->
            HlSettingGroup(null) {
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
                    trailing = if (source == SourceConfig.Local) ({ ChoiceCheck() }) else null,
                )
                HlSettingRow(
                    stringResource(R.string.source_jellyfin),
                    Modifier.focusRequester(jellyfin),
                    description = stringResource(if (server != null) R.string.source_jellyfin_other else R.string.source_jellyfin_desc),
                    icon = HlIcons.Server,
                    onClick = onSignIn,
                    trailing = if (server != null) ({ ChoiceCheck() }) else null,
                )
            }
        }
    }
}

/** Facts under the current source's name: the server's kind and user, or the folders' title count. */
@Composable
private fun sourceFacts(server: SourceConfig.Jellyfin?): List<String?> {
    val titles by settingsViewModel().library.titleCount.collectAsStateWithLifecycle(0)
    return if (server != null) {
        listOf(stringResource(R.string.source_jellyfin).takeIf { server.serverName != null }, server.userName)
    } else {
        listOf(pluralStringResource(R.plurals.title_count, titles, titles))
    }
}

/** The folders option's line: the folders added, or the first one to add. */
@Composable
private fun foldersLine(count: Int): String =
    if (count == 0) stringResource(R.string.source_local_first) else pluralStringResource(R.plurals.source_local_folders, count, count)

/** The TMDB key sheet; the outcome lives in the ViewModel, as the sheet may close during the check. */
@Composable
private fun TmdbKeySheet(issue: UiError?, onDismiss: () -> Unit) {
    val vm = settingsViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    val attempt = vm.tmdbKey
    val outcome = attempt?.outcome
    // The last submitted key while checking or if not accepted, else the saved one.
    var key by remember { mutableStateOf((attempt?.takeIf { outcome != ACCEPTED }?.key ?: s.tmdbApiKey).orEmpty()) }
    // The attempt this sheet awaits: its own, or one running when it opened; an earlier result never closes it.
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
        // Accepted closes the sheet; refused refocuses the key; unchecked or unsaved stays to say so.
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
        // The sheet already names the key: the field's label is for accessibility only.
        HlTextField(
            stringResource(R.string.tmdb_key_personal),
            key,
            {
                key = it
                vm.editTmdbKey()
            },
            Modifier.fillMaxWidth().focusRequester(field),
            placeholder = stringResource(R.string.placeholder_tmdb_key),
            showLabel = false,
            supporting = stringResource(if (hasBuiltIn) R.string.tmdb_key_builtin else R.string.tmdb_key_free),
            error = keyError(refused, notSaved),
            password = true,
            imeAction = ImeAction.Done,
            onImeAction = { save(key) },
        )
        // Its parts the sheet's own gap apart.
        HlButton(
            stringResource(R.string.save),
            onClick = { save(key) },
            icon = HlIcons.Check,
            busy = checking,
            busyLabel = stringResource(R.string.checking),
        )
        if (personal && hasBuiltIn) {
            HlSettingGroup(null) {
                HlSettingRow(
                    stringResource(R.string.tmdb_key_revert),
                    description = stringResource(R.string.tmdb_key_revert_desc),
                    // Reverting is never refused or unchecked, so it also closes the sheet.
                    onClick = { save("") },
                )
            }
        }
    }
}

/** The error under the key field: TMDB refused it, or it could not be saved. */
@Composable
private fun keyError(refused: Boolean, notSaved: UiError?): String? = when {
    refused -> stringResource(R.string.error_tmdb_key_refused_title)
    notSaved != null -> stringResource(notSaved.message)
    else -> null
}

/** Outcomes the TMDB sheet reacts to. */
private val ACCEPTED = TmdbKeySave.Checked(TmdbKeyCheck.ACCEPTED)
private val REFUSED = TmdbKeySave.Checked(TmdbKeyCheck.REFUSED)
private val UNCHECKED = TmdbKeySave.Checked(TmdbKeyCheck.UNCHECKED)

/** The key in use: a lost saved key ([lost]) first, then personal, built-in or none, with the scan's [issue]. */
@Composable
private fun TmdbKeyStatus(personal: Boolean, hasBuiltIn: Boolean, unchecked: Boolean, lost: Boolean, issue: UiError?) {
    when {
        lost && !personal -> HlStatus(
            StatusState.Attention,
            stringResource(R.string.tmdb_key_lost),
            detailStyle = Hl.type.meta,
            detail = stringResource(if (hasBuiltIn) R.string.tmdb_key_lost_builtin else R.string.tmdb_key_lost_none),
        )
        issue != null && (personal || hasBuiltIn) -> HlStatus(
            StatusState.Attention,
            stringResource(if (personal) R.string.tmdb_key_personal else R.string.tmdb_key_builtin_state),
            detailStyle = Hl.type.meta,
            detail = stringResource(if (issue == UiError.TMDB_KEY_REFUSED) R.string.tmdb_scan_key_refused else R.string.tmdb_scan_offline),
        )
        personal -> HlStatus(
            StatusState.On,
            stringResource(R.string.tmdb_key_personal),
            detailStyle = Hl.type.meta,
            detail = if (unchecked) stringResource(R.string.tmdb_key_unchecked) else null,
        )
        hasBuiltIn -> HlStatus(StatusState.On, stringResource(R.string.tmdb_key_builtin_state))
        else -> HlStatus(
            StatusState.Attention,
            stringResource(R.string.tmdb_key_none),
            detailStyle = Hl.type.meta,
            detail = stringResource(R.string.tmdb_key_none_desc),
        )
    }
}

/** Posters at the head of a folder's sheet. */
private const val HEADER_POSTERS = 3

private const val ADD_FOLDER = "add_folder"
private const val SOURCE = "source"

/** A folder's showcase's row key. */
private fun folderRow(id: Long) = "folder-$id"
