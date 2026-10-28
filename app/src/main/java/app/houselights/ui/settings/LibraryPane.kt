package app.houselights.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.houselights.BuildConfig
import app.houselights.R
import app.houselights.core.CollectionThreshold
import app.houselights.core.SourceConfig
import app.houselights.providers.jellyfin.JellyfinClient
import app.houselights.providers.local.Folder
import app.houselights.providers.local.FolderCounts
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.ScanState
import app.houselights.providers.local.TmdbClient
import app.houselights.providers.local.TmdbKeyCheck
import app.houselights.providers.local.Volume
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
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.PlateFact
import app.houselights.ui.design.components.PosterThumb
import app.houselights.ui.design.components.SHOWCASE_SLOTS
import app.houselights.ui.design.components.ScanProgress
import app.houselights.ui.design.components.SheetConfirm
import app.houselights.ui.design.components.Showcase
import app.houselights.ui.design.components.ShowcaseRow
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.onboarding.FolderField
import app.houselights.ui.onboarding.LoginFill
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.UiError
import app.houselights.ui.util.formatCount
import app.houselights.ui.util.scanErrorText
import app.houselights.ui.util.sourceKindName
import app.houselights.ui.util.titleText
import app.houselights.ui.util.toUiError
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

/**
 * The library pane: its folders' showcases or its server, then actions; rows that open a screen are keyed in [rows]. A
 * folder just [added] takes focus on its showcase. [onFolderForm] adds a folder, or edits one, a folder of the player's
 * disks ([Protocol.LOCAL]) through « Ajouter un dossier »'s steps.
 */
internal fun LazyListScope.libraryPane(
    s: AppSettings,
    onFolderForm: (folderId: Long?, focus: FolderField, onDisk: Boolean) -> Unit,
    sources: SourceDoors,
    rows: PaneRows,
    added: NewFolder? = null,
) {
    when (val source = s.source) {
        SourceConfig.Local -> {
            item {
                Section(stringResource(R.string.section_source), aside = librarySummary()) {
                    Showcases(rows, onEdit = onFolderForm, onSourceResume = sources.resumed, added = added)
                }
            }
            item { LibraryActions({ onFolderForm(null, FolderField.FIRST, false) }, rows, sources.toIdentify) }
            item { FilmData() }
            item { HlSettingGroup(null, Modifier.revealOnFocus()) { SourceRow(sources, rows.focus(SOURCE)) } }
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
                        onClick = { sources.signIn(LoginFill.Current) },
                    )
                    SourceRow(sources, rows.focus(SOURCE))
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

/**
 * A folder « Ajouter un dossier » just added or edited ([id]), whose showcase takes focus once it shows, then [shown];
 * with [sheet], « Modifier le dossier » left by Back, its sheet opens again over it.
 */
internal class NewFolder(val id: Long, val sheet: Boolean = false, val shown: () -> Unit)

/** Focus on [added]'s showcase once [sources] hold it, the [row] of showcases scrolled to it first; its sheet then [opens]. */
@Composable
private fun FocusOnAdded(added: NewFolder, sources: List<Folder>, row: LazyListState, rows: PaneRows, opens: (Folder) -> Unit) {
    val currentOpens by rememberUpdatedState(opens)
    LaunchedEffect(added.id, sources) {
        val index = sources.indexOfFirst { it.id == added.id }
        if (index < 0) return@LaunchedEffect
        row.scrollToItem(index)
        // Once laid out, and once the screen has put focus back where it was left (RestoreFocus).
        withFrameNanos { }
        if (!rows.requester(folderRow(added.id)).tryRequestFocus()) return@LaunchedEffect
        if (added.sheet) currentOpens(sources[index])
        added.shown()
    }
}

/**
 * One showcase per folder, keyed in [rows]; when a folder is removed, focus moves to the next one, else to Add. The last
 * one removed gives way to the kept server, its home then shown ([onSourceResume]), else to the welcome screen. A folder just
 * [added] takes focus on its showcase as soon as it shows, its scan running there, the row scrolled to it; one whose
 * edit was left, its sheet opening again, focus on « Modifier le dossier ».
 */
@Composable
private fun Showcases(
    rows: PaneRows,
    onEdit: (folderId: Long, focus: FolderField, onDisk: Boolean) -> Unit,
    onSourceResume: () -> Unit,
    added: NewFolder? = null,
) {
    val vm = settingsViewModel()
    val sources by vm.library.folders.collectAsStateWithLifecycle(emptyList())
    val kept = vm.settings.collectAsStateWithLifecycle().value.keptServer
    val scan by vm.library.scan.collectAsStateWithLifecycle()
    // Collected once for all showcases: each scan write re-runs it.
    val counts by vm.library.folderCounts.collectAsStateWithLifecycle(emptyMap())
    // Titles held while their files are missing, answered for or not: counted on their folder, its sheet opening theirs.
    val heldRows by vm.library.held.collectAsStateWithLifecycle(emptyList())
    val held = remember(heldRows, sources) { heldByFolder(heldRows, sources).associateBy { it.folder.id } }
    var actionsFor by remember { mutableStateOf<Folder?>(null) }
    // The sheet opened again as its edit was left: focus on « Modifier le dossier », as Back brings focus to the row chosen.
    var backFromEdit by remember { mutableStateOf(false) }
    var heldFor by remember { mutableStateOf<Long?>(null) }
    // The folder being removed, and where focus goes once its showcase has gone.
    var removing by remember { mutableStateOf<Pair<Long, FocusRequester>?>(null) }
    LaunchedEffect(sources) {
        val (id, next) = removing ?: return@LaunchedEffect
        if (sources.none { it.id == id }) {
            removing = null
            next.tryRequestFocus()
        }
    }

    val row = rememberLazyListState()
    if (added != null) {
        FocusOnAdded(added, sources, row, rows) {
            actionsFor = it
            backFromEdit = true
        }
    }
    if (sources.isNotEmpty()) {
        ShowcaseRow(state = row) {
            items(sources, key = { it.id }) { source ->
                FolderShowcase(
                    source,
                    counts = counts[source.id],
                    held = held[source.id]?.rows?.size ?: 0,
                    scan = scan.takeIf { it.running && it.sourceId == source.id },
                    error = scan.errors[source.id],
                    removeError = vm.errorOf(SettingAction.REMOVE_FOLDER, source.id),
                    heldError = vm.errorOf(SettingAction.HELD, source.id),
                    // Removal waits for any file a scan is reading in the folder: say so rather than look stuck.
                    removing = source.id in vm.removing,
                    onClick = {
                        actionsFor = source
                        backFromEdit = false
                    },
                    modifier = rows.focus(folderRow(source.id)),
                )
            }
        }
    }
    actionsFor?.let { source ->
        val last = vm.isLast(source, sources)
        SourceActions(
            source,
            consequence = when {
                !last -> stringResource(R.string.remove_folder_desc)
                kept != null -> stringResource(R.string.remove_last_folder_kept_desc, serverName(kept))
                else -> stringResource(R.string.remove_last_folder_desc)
            },
            error = scan.errors[source.id],
            held = held[source.id]?.rows?.size ?: 0,
            onOpenHeldTitles = { heldFor = source.id },
            onDismiss = { actionsFor = null },
            onEdit = { focus -> onEdit(source.id, focus, source.protocol == Protocol.LOCAL) },
            editFirst = backFromEdit,
            // Runs in the app's scope, so leaving the pane never cancels it.
            onRemove = {
                val next = sources.getOrNull(sources.indexOfFirst { it.id == source.id } + 1)
                removing = source.id to rows.requester(next?.let { folderRow(it.id) } ?: ADD_FOLDER)
                vm.removeFolder(source, last, onSourceResume)
            },
        )
    }
    HeldSheetFor(heldFor, held, vm.heldAnswers) { heldFor = null }
}

/**
 * The sheet of the titles held in folder [folder] (none while null), « Retirer » there at any time, its [answers]
 * saved in Settings' work; closed ([onClose]) once they are back.
 */
@Composable
private fun HeldSheetFor(folder: Long?, held: Map<Long, HeldTitles>, answers: HeldAnswers, onClose: () -> Unit) {
    if (folder == null) return
    val titles = held[folder]
    if (titles == null) {
        val currentOnClose by rememberUpdatedState(onClose)
        LaunchedEffect(Unit) { currentOnClose() }
        return
    }
    HeldSheet(titles, onKeep = { answers.keep(titles) }, onRemove = { answers.remove(titles) }, onDismiss = onClose)
}

/**
 * Where [folder] is: its NAS's address, or the [disk] of this player it is on, then its path from the root, apart
 * ([FACT_GAP]); the address or the disk alone at a root.
 */
internal fun placeOf(folder: Folder, disk: String? = null): String {
    val path = folder.address.removePrefix(folder.host).trim('/')
    val at = disk ?: folder.host
    return if (path.isEmpty()) at else at + FACT_GAP + "/" + path
}

/**
 * The disk [source] is on, a folder of this player's, as « Ajouter un dossier » names it: « Disque interne », a USB
 * disk by its name while it is plugged in, else « Disque USB »; null for a NAS's folder.
 */
@Composable
private fun diskOf(source: Folder): String? {
    if (source.protocol != Protocol.LOCAL) return null
    if (source.host == Volume.INTERNAL) return stringResource(R.string.disk_internal)
    val volumes = settingsViewModel().volumes
    val name = remember(source.host) { volumes.mounted().firstOrNull { it.id.equals(source.host, ignoreCase = true) }?.name }
    return name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.disk_usb)
}

/**
 * A folder's sheet: scan, edit or remove (confirmed in place, saying its [consequence]); Edit comes first after a failure
 * it can fix, and takes focus with [editFirst], its edit left. While titles of it are [held] ([onOpenHeldTitles] opens their
 * sheet), they come first, focused. A folder of this player's disks says its disk as « Ajouter un dossier » does, no
 * protocol, and Edit chooses another folder of the disk through the flow's steps, first once a scan no longer finds it.
 */
@Composable
private fun SourceActions(
    source: Folder,
    consequence: String,
    error: ScanError?,
    onDismiss: () -> Unit,
    onEdit: (focus: FolderField) -> Unit,
    onRemove: () -> Unit,
    held: Int = 0,
    onOpenHeldTitles: () -> Unit = {},
    editFirst: Boolean = false,
) {
    val vm = settingsViewModel()
    val posters by remember(source.id) { vm.library.latestPosters(source, HEADER_POSTERS) }.collectAsStateWithLifecycle(emptyList())
    val kind = sourceKindName(source.kind)
    val scan = remember { FocusRequester() }
    val edit = remember { FocusRequester() }
    val heldRow = remember { FocusRequester() }
    val disk = diskOf(source)
    // Read once: a scan ending while the sheet is open does not move its rows under focus. A disk's folder mends only
    // its folder gone: its disk is plugged in, not typed.
    val fix = remember { editFixes(error).takeIf { disk == null || it == ScanErrorKind.NOT_FOUND } }
    val holds = remember { held > 0 }
    HlSideSheet(
        title = sourceName(source),
        onDismiss = onDismiss,
        facts = listOf(kind.takeIf { !source.name.equals(it, ignoreCase = true) }),
        badges = listOfNotNull(source.protocol.name.takeIf { disk == null }),
        // Its address and its path apart, the path as the folder's form composes it: "192.168.1.20   /volume1/video/Films".
        description = placeOf(source, disk),
        artwork = if (posters.isEmpty()) {
            null
        } else {
            {
                // Its latest posters, as tall as the title block; empty frames for the slots left.
                Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xxs)) {
                    repeat(HEADER_POSTERS) { PosterThumb(posters.getOrNull(it), width = null) }
                }
            }
        },
        initialFocus = when {
            editFirst -> edit
            holds -> heldRow
            fix != null -> edit
            else -> scan
        },
    ) { sheet ->
        SheetConfirm(
            question = stringResource(R.string.remove_folder_confirm, sourceName(source)),
            consequence = consequence,
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
                description = stringResource(if (disk != null) R.string.edit_folder_disk_desc else editWords(fix)),
                onClick = { sheet.dismiss { onEdit(FolderField.after(fix)) } },
            )
            HlSettingGroup(null) {
                // Its titles held while their files are missing: their sheet, « Retirer » there at any time.
                if (held > 0) {
                    HlSettingRow(
                        pluralStringResource(R.plurals.held_titles, held, held),
                        Modifier.focusRequester(heldRow),
                        description = stringResource(if (held == 1) R.string.held_row_desc_one else R.string.held_row_desc),
                        onClick = { sheet.dismiss(onOpenHeldTitles) },
                    )
                }
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

/** What « Modifier le dossier » says it does: mend the scan error it can fix ([fix]), else edit the folder. */
private fun editWords(fix: ScanErrorKind?): Int = when (fix) {
    ScanErrorKind.LOGIN_FAILED -> R.string.edit_folder_login_desc
    ScanErrorKind.UNREACHABLE -> R.string.edit_folder_unreachable_desc
    ScanErrorKind.NOT_FOUND -> R.string.edit_folder_not_found_desc
    else -> R.string.edit_folder_row_desc
}

/** The scan error editing likely fixes (silent NAS, missing share or path, refused sign-in); null otherwise. */
internal fun editFixes(error: ScanError?): ScanErrorKind? = error?.kind?.takeIf {
    it == ScanErrorKind.UNREACHABLE || it == ScanErrorKind.NOT_FOUND || it == ScanErrorKind.LOGIN_FAILED
}

@Composable
private fun sourceName(source: Folder) = source.name.replaceFirstChar { it.uppercase() }

/** A folder's [Showcase]: its [counts] (null until read), its last scan, or what it is doing ([scan], [removing]). */
@Composable
private fun FolderShowcase(
    source: Folder,
    counts: FolderCounts?,
    held: Int,
    scan: ScanState?,
    error: ScanError?,
    removeError: UiError?,
    heldError: UiError?,
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
        // A folder of this player's disks has no protocol to print.
        mark = source.protocol.name.takeIf { source.protocol != Protocol.LOCAL },
        posters = posters,
        onClick = onClick,
        modifier = modifier,
        // Never read is no state done: a plain fact, first as the state would be; then its figures, the first in
        // white, and what it holds after them.
        facts = listOf(lastScanned(source.lastScanAt).takeIf { source.lastScanAt <= 0 }) + folderFacts(counts) +
            kind.takeIf { !source.name.equals(it, ignoreCase = true) },
        // Titles held while their files are missing take the state's place, by the ring: it may be no failure.
        state = if (held > 0) {
            pluralStringResource(R.plurals.held_count, held, held)
        } else {
            lastScanned(source.lastScanAt).takeIf { source.lastScanAt > 0 }
        },
        stateLight = if (held > 0) StatusState.Off else StatusState.Done,
        status = when {
            removing -> ({ HlStatus(StatusState.Pending, removingText) })
            scan != null -> ({ ScanProgress(scan.processed, scan.total, current = scan.current) })
            // On the facts' one line: the folder's sheet says it in full.
            notRemoved != null -> ({ AttentionNote(notRemoved, maxLines = 1) })
            // An answer for its titles held that did not save: they stay as they were.
            heldError != null -> ({ AttentionNote(heldError.titleText(), maxLines = 1) })
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

/**
 * Scan, add a folder, read every title again: the library's three actions, on one plate; last, what a scan leaves to
 * do, never in ember, as nothing failed: while there are titles to identify, « À identifier » and their count
 * ([onOpenToIdentify]), then while the shows folders hold videos placed nowhere, how many ([IgnoredFilesRow]), OK
 * listing them. Either gone, from under focus or while its list was open, its place gives focus to « Analyser
 * maintenant »; the list open keeps the files it shows until it closes.
 */
@Composable
private fun LibraryActions(onAddFolder: () -> Unit, rows: PaneRows, onOpenToIdentify: () -> Unit) {
    val vm = settingsViewModel()
    val scan by vm.library.scan.collectAsStateWithLifecycle()
    val toIdentify by vm.toIdentify.collectAsStateWithLifecycle()
    val ignored by vm.ignored.collectAsStateWithLifecycle()
    val listed = (toIdentify ?: 0) > 0
    var listing by remember { mutableStateOf(false) }
    // Outlives the list, to give focus once it closed.
    val scope = rememberCoroutineScope()
    GoneGivesScanNow(rows, TO_IDENTIFY, listed)
    GoneGivesScanNow(rows, IGNORED, ignored.isNotEmpty())
    HlSettingGroup(null, Modifier.revealOnFocus().then(rows.group(ACTIONS))) {
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
        if (listed) {
            HlSettingRow(
                stringResource(R.string.to_identify),
                rows.focus(TO_IDENTIFY, ACTIONS),
                value = formatCount(toIdentify?.toLong() ?: 0),
                description = stringResource(R.string.to_identify_row_desc),
                valueStyle = Hl.type.jaquette,
                onClick = onOpenToIdentify,
            )
        }
        if (ignored.isNotEmpty()) IgnoredFilesRow(ignored, rows.focus(IGNORED, ACTIONS)) { listing = true }
    }
    if (listing) {
        IgnoredFilesSheet(lastListed(ignored)) {
            listing = false
            // Its row gone while it was open, Back finds « Analyser maintenant ».
            if (ignored.isEmpty()) {
                scope.launch {
                    withFrameNanos { }
                    rows.focusGroup(ACTIONS)
                }
            }
        }
    }
}

/** Focuses « Analyser maintenant » once [row] of the actions goes ([shown] false) while it has focus. */
@Composable
private fun GoneGivesScanNow(rows: PaneRows, row: String, shown: Boolean) {
    // Read as the row leaves, while it still holds focus.
    val had = remember(shown) { rows.hasFocus(row, ACTIONS) }
    LaunchedEffect(shown) {
        if (had && !shown) {
            withFrameNanos { }
            rows.focusGroup(ACTIONS)
        }
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
                },
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
    val device by vm.deviceLocale.collectAsStateWithLifecycle()
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

/** The Source row as Settings has it, each choice of its sheet carried out. */
@Composable
private fun SourceRow(doors: SourceDoors, modifier: Modifier = Modifier) {
    val vm = settingsViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    val folders by vm.library.folders.collectAsStateWithLifecycle(emptyList())
    val titles by vm.library.titleCount.collectAsStateWithLifecycle(0)
    // Switching failed (storage), or the kept server could not be resumed or forgotten: shown here, where focus returns.
    val failure = rowFailure(vm.errorOf(SettingAction.SOURCE)) ?: rowFailure(vm.errorOf(SettingAction.KEPT_SERVER), notDone = true)
    SourceSetting(
        SourceSheetFacts(s.source, s.keptServer, folders.size, titles, trakt = s.trakt?.ended == false),
        failure,
        resuming = vm.resuming,
        modifier,
    ) { choice ->
        when (choice) {
            is SourceChoice.Folders -> doors.useFolders(true)
            // A token the server refuses: the sign-in opens filled in, on the password.
            is SourceChoice.Server -> vm.resumeServer(choice.server, doors.resumed) { refused ->
                doors.signIn(LoginFill.Server(refused.serverUrl, refused.userName))
            }
            is SourceChoice.SignIn -> doors.signIn(LoginFill.Blank)
            is SourceChoice.Leave -> if (choice.current) doors.useFolders(false) else vm.forgetServer()
        }
    }
}

/**
 * The current source and what changing it keeps; OK opens [SourceSheet], whose every choice closes it at once. While
 * the kept server is asked ([resuming]) the row says so and OK does nothing: its coming back would undo a choice made
 * meanwhile, or leave the sign-in being typed.
 */
@Composable
internal fun SourceSetting(
    facts: SourceSheetFacts,
    failure: RowFailure?,
    resuming: Boolean,
    modifier: Modifier = Modifier,
    onChoose: (SourceChoice) -> Unit,
) {
    var choose by remember { mutableStateOf(false) }
    HlSettingRow(
        stringResource(R.string.change_source_row),
        modifier,
        value = when {
            resuming -> stringResource(R.string.signing_in)
            failure != null -> failure.value
            else -> stringResource(if (facts.source is SourceConfig.Jellyfin) R.string.source_jellyfin else R.string.source_local)
        },
        description = stringResource(sourceKeeps(facts.source)),
        valueAttention = failure != null,
        onClick = { if (!resuming) choose = true },
    )
    RowFailureNote(failure)
    if (choose) {
        SourceSheet(facts, onDismiss = { choose = false }) { choice ->
            choose = false
            onChoose(choice)
        }
    }
}

/** What changing from [source] keeps, under the Source row. */
private fun sourceKeeps(source: SourceConfig?): Int =
    if (source is SourceConfig.Jellyfin) R.string.change_source_jellyfin_desc else R.string.change_source_local_desc

/** What the source sheet shows: the source, the server [kept] beside the folders, their count and titles, Trakt connected. */
internal data class SourceSheetFacts(
    val source: SourceConfig?,
    val kept: SourceConfig.Jellyfin?,
    val folders: Int,
    val titles: Int,
    val trakt: Boolean,
)

/** What a row of the source sheet does. */
internal sealed interface SourceChoice {
    /** The folders, checked while they are the source; Trakt resumes once a server is left for them ([traktResumes]). */
    data class Folders(val current: Boolean, val traktResumes: Boolean) : SourceChoice

    /** [server], checked while it is the source, else the one kept beside the folders, resumed without signing in. */
    data class Server(val server: SourceConfig.Jellyfin, val current: Boolean) : SourceChoice

    /** A Jellyfin server to sign in to; [another] beside one in use or kept. */
    data class SignIn(val another: Boolean) : SourceChoice

    /** Signs out of [server] while it is the source ([current]), else forgets the kept one; asked first. */
    data class Leave(val server: SourceConfig.Jellyfin, val current: Boolean) : SourceChoice
}

/** The sheet's rows for [source] and the server [kept] beside the folders, then the one apart that leaves a server, if any. */
internal fun sourceChoices(
    source: SourceConfig?,
    kept: SourceConfig.Jellyfin?,
    trakt: Boolean,
): Pair<List<SourceChoice>, SourceChoice.Leave?> {
    val inUse = source as? SourceConfig.Jellyfin
    val server = inUse ?: kept
    val rows = listOfNotNull(
        SourceChoice.Folders(current = source == SourceConfig.Local, traktResumes = inUse != null && trakt),
        server?.let { SourceChoice.Server(it, current = inUse != null) },
        SourceChoice.SignIn(another = server != null),
    )
    return rows to server?.let { SourceChoice.Leave(it, current = inUse != null) }
}

/**
 * Folders or a Jellyfin server, the current one checked, the kept one resumed in one OK, another server; apart,
 * forgetting the kept server or signing out of the current one, asked first. Focus starts on what changes the source.
 * A choice that does something goes to [onChoose].
 */
@Composable
internal fun SourceSheet(facts: SourceSheetFacts, onDismiss: () -> Unit, onChoose: (SourceChoice) -> Unit) {
    val (choices, leave) = sourceChoices(facts.source, facts.kept, facts.trakt)
    val server = facts.source as? SourceConfig.Jellyfin
    val first = remember { FocusRequester() }
    val firstChoice = choices.firstOrNull { it is SourceChoice.Server && !it.current } ?: choices.first()
    HlSideSheet(
        title = stringResource(R.string.change_source),
        onDismiss = onDismiss,
        facts = sourceFacts(server, facts.titles),
        // The head has room for the whole sentence, the row beside its value has not.
        description = stringResource(if (server != null) R.string.change_source_jellyfin_sheet else R.string.change_source_local_desc),
        initialFocus = first,
    ) { sheet ->
        LeaveConfirm(leave, facts.folders, onChoose) { ask, origin ->
            HlSettingGroup(null) {
                choices.forEach { choice ->
                    SourceChoiceRow(choice, facts.folders, if (choice == firstChoice) Modifier.focusRequester(first) else Modifier) {
                        if (isCurrent(choice, facts.folders)) sheet.dismiss() else onChoose(choice)
                    }
                }
            }
            leave?.let { HlSettingGroup(null) { SourceChoiceRow(it, facts.folders, origin, onClick = ask) } }
        }
    }
}

/** [choice] is the source already, with nothing to add: OK only closes the sheet. */
private fun isCurrent(choice: SourceChoice, folders: Int): Boolean = when (choice) {
    is SourceChoice.Server -> choice.current
    is SourceChoice.Folders -> choice.current && folders > 0
    else -> false
}

/** [rows], or in their place the question that signs out of the server or forgets the kept one ([leave]). */
@Composable
private fun LeaveConfirm(
    leave: SourceChoice.Leave?,
    folders: Int,
    onChoose: (SourceChoice) -> Unit,
    rows: @Composable (ask: () -> Unit, origin: Modifier) -> Unit,
) {
    val signsOut = leave?.current == true
    val name = leave?.let { serverName(it.server) }.orEmpty()
    SheetConfirm(
        question = stringResource(if (signsOut) R.string.sign_out_confirm else R.string.forget_server_confirm, name),
        consequence = stringResource(
            when {
                !signsOut -> R.string.forget_server_consequence
                folders == 0 -> R.string.sign_out_no_folder_desc
                else -> R.string.sign_out_desc
            },
        ),
        confirm = stringResource(if (signsOut) R.string.sign_out else R.string.forget),
        onConfirm = { leave?.let(onChoose) },
        rows = rows,
    )
}

/** [choice] as its row, saying in one line what it is, checked while it is the source. */
@Composable
private fun SourceChoiceRow(choice: SourceChoice, folders: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    when (choice) {
        is SourceChoice.Folders -> HlSettingRow(
            stringResource(R.string.source_local),
            modifier,
            // Trakt resumes with the folders: said in place of where they are, so that the line holds.
            description = if (choice.traktResumes && folders > 0) {
                pluralStringResource(R.plurals.folder_count, folders, folders) + DOT + stringResource(R.string.source_trakt_resumes)
            } else {
                foldersLine(folders)
            },
            icon = HlIcons.Folder,
            onClick = onClick,
            trailing = if (choice.current) ({ ChoiceCheck() }) else null,
        )
        is SourceChoice.Server -> HlSettingRow(
            serverName(choice.server),
            modifier,
            description = listOfNotNull(
                JELLYFIN,
                choice.server.userName,
                stringResource(R.string.source_kept_resume).takeUnless { choice.current },
            ).joinToString(DOT),
            icon = HlIcons.Server,
            onClick = onClick,
            trailing = if (choice.current) ({ ChoiceCheck() }) else null,
        )
        is SourceChoice.SignIn -> HlSettingRow(
            stringResource(if (choice.another) R.string.source_jellyfin_another else R.string.source_jellyfin),
            modifier,
            description = stringResource(if (choice.another) R.string.source_jellyfin_another_desc else R.string.source_jellyfin_desc),
            icon = if (choice.another) HlIcons.Add else HlIcons.Server,
            onClick = onClick,
        )
        is SourceChoice.Leave -> HlSettingRow(
            stringResource(if (choice.current) R.string.sign_out_server else R.string.forget_server, serverName(choice.server)),
            modifier,
            description = stringResource(if (choice.current) R.string.sign_out_server_desc else R.string.forget_server_desc),
            icon = HlIcons.SignOut,
            onClick = onClick,
        )
    }
}

/** A server by its own name, else as a Jellyfin server. */
@Composable
private fun serverName(server: SourceConfig.Jellyfin): String = server.serverName ?: stringResource(R.string.source_jellyfin)

/** The current source's name and its facts: the server's kind and user, or the folders' [titles]. */
@Composable
private fun sourceFacts(server: SourceConfig.Jellyfin?, titles: Int): List<String?> = if (server != null) {
    listOf(serverName(server), stringResource(R.string.source_jellyfin).takeIf { server.serverName != null }, server.userName)
} else {
    listOf(stringResource(R.string.source_local), pluralStringResource(R.plurals.title_count, titles, titles))
}

/** The folders option's line: the folders added, or the first one to add. */
@Composable
private fun foldersLine(count: Int): String =
    if (count == 0) stringResource(R.string.source_local_first) else pluralStringResource(R.plurals.source_local_folders, count, count)

/** The TMDB key sheet; the outcome lives in the ViewModel, as the sheet may close during the check. */
@Composable
internal fun TmdbKeySheet(issue: UiError?, onDismiss: () -> Unit) {
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

/** What TMDB's problem in the last scan means for the titles: a key refused, a certificate refused, or no answer. */
private fun scanIssueNote(issue: UiError): Int = when (issue) {
    UiError.TMDB_KEY_REFUSED -> R.string.tmdb_scan_key_refused
    UiError.CERTIFICATE -> R.string.tmdb_scan_certificate
    else -> R.string.tmdb_scan_offline
}

/** The key in use: a lost saved key ([lost]) first, then personal, built-in or none, with the scan's [issue]. */
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
            detail = stringResource(scanIssueNote(issue)),
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

/** Posters at the head of a folder's sheet. */
private const val HEADER_POSTERS = 3

private const val ADD_FOLDER = "add_folder"

/** The library's actions, on one plate, and those of them that open the titles to identify and the files left out. */
private const val ACTIONS = "actions"
private const val TO_IDENTIFY = "to_identify"
private const val IGNORED = "ignored_files"
private const val SOURCE = "source"

/** Between the parts of a source row's line ("Jellyfin · léa"). */
private const val DOT = " · "

/** The server's kind, by its own name in every language. */
private const val JELLYFIN = "Jellyfin"

/** A folder's showcase's row key. */
private fun folderRow(id: Long) = "folder-$id"
