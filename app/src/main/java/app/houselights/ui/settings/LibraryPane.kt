package app.houselights.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.houselights.BuildConfig
import app.houselights.R
import app.houselights.core.SourceConfig
import app.houselights.providers.jellyfin.JellyfinClient
import app.houselights.providers.local.LocalProvider
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.TmdbClient.Companion.ImageType
import app.houselights.providers.local.address
import app.houselights.settings.AppSettings
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.ArtworkImage
import app.houselights.ui.design.components.Badge
import app.houselights.ui.design.components.ChoiceCheck
import app.houselights.ui.design.components.ChoiceOption
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.FactRow
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlChoiceSheet
import app.houselights.ui.design.components.HlDivider
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.PosterThumb
import app.houselights.ui.design.components.HlPanelButton
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.ProgressLine
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.util.scanErrorText
import kotlinx.coroutines.launch
import app.houselights.core.CollectionThreshold

/**
 * The library: its folders or its server, then what can be done with it. [onUseFolders] makes the
 * folders the source (or adds the first one), [onSignIn] opens the Jellyfin sign-in. The rows that
 * open another screen are keyed in [rows]; "Add a folder" is also where focus goes when the last
 * folder is removed.
 */
internal fun LazyListScope.libraryPane(
    s: AppSettings,
    onAddFolder: () -> Unit,
    onUseFolders: () -> Unit,
    onSignIn: () -> Unit,
    rows: PaneRows,
) {
    when (val source = s.source) {
        SourceConfig.Local -> {
            item { Section(stringResource(R.string.section_source)) { Sources(rows.requester(ADD_FOLDER)) } }
            item { LibraryActions(onAddFolder, onUseFolders, onSignIn, rows) }
        }
        is SourceConfig.Jellyfin -> {
            item {
                Section(stringResource(R.string.source_jellyfin)) {
                    HlSettingGroup(null) {
                        val server = source.serverName ?: stringResource(R.string.source_jellyfin)
                        HlSettingRow(stringResource(R.string.label_server), value = server)
                        HlSettingRow(stringResource(R.string.label_address), value = source.serverUrl)
                        val encrypted = JellyfinClient.isEncrypted(source.serverUrl)
                        HlSettingRow(
                            stringResource(R.string.label_connection),
                            value = stringResource(if (encrypted) R.string.connection_encrypted else R.string.connection_plain),
                        )
                        source.userName?.let { HlSettingRow(stringResource(R.string.label_user), value = it) }
                    }
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
 * One card per watched folder. The card of a folder removed takes focus with it: focus moves on to
 * the next card, else to [addFolder].
 */
@Composable
private fun Sources(addFolder: FocusRequester) {
    val vm = settingsViewModel()
    val scope = rememberCoroutineScope()
    val sources by vm.library.folders.collectAsStateWithLifecycle(emptyList())
    val scan by vm.library.scan.collectAsStateWithLifecycle()
    var actionsFor by remember { mutableStateOf<SourceEntity?>(null) }
    val cards = remember { mutableMapOf<Long, FocusRequester>() }
    // The folder being removed, and where focus goes once its card has gone.
    var removing by remember { mutableStateOf<Pair<Long, FocusRequester>?>(null) }
    LaunchedEffect(sources) {
        val (id, next) = removing ?: return@LaunchedEffect
        if (sources.none { it.id == id }) {
            removing = null
            runCatching { next.requestFocus() }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
        sources.forEach { source ->
            key(source.id) {
                SourceCard(
                    source,
                    scanning = scan.running && scan.sourceName == source.name,
                    progress = if (scan.total > 0) scan.processed.toFloat() / scan.total else 0f,
                    current = scan.current,
                    error = scan.errors[source.name],
                    onClick = { actionsFor = source },
                    modifier = Modifier.focusRequester(cards.getOrPut(source.id) { FocusRequester() }),
                )
            }
        }
    }
    actionsFor?.let { source ->
        SourceActions(
            source,
            onDismiss = { actionsFor = null },
            // Launched from here: the sheet has left the composition by the time it runs.
            onRemove = {
                val next = sources.getOrNull(sources.indexOfFirst { it.id == source.id } + 1)
                removing = source.id to (next?.let { cards[it.id] } ?: addFolder)
                scope.launch { vm.library.removeFolder(source) }
            },
        )
    }
}

/**
 * What can be done with a watched folder, beside the list: scan it now, or remove it, which asks
 * first, in the sheet itself, with focus on Cancel.
 */
@Composable
private fun SourceActions(source: SourceEntity, onDismiss: () -> Unit, onRemove: () -> Unit) {
    val vm = settingsViewModel()
    val posters by remember(source.id) { vm.library.latestPosters(source, HEADER_POSTERS) }.collectAsStateWithLifecycle(emptyList())
    val kind = stringResource(if (source.kind == SourceKind.MOVIES) R.string.library_movies else R.string.library_shows)
    var confirming by remember { mutableStateOf(false) }
    var backFromConfirm by remember { mutableStateOf(false) }
    val scan = remember { FocusRequester() }
    val remove = remember { FocusRequester() }
    HlSideSheet(
        title = sourceName(source),
        onDismiss = onDismiss,
        facts = listOf(kind.takeIf { !source.name.equals(it, ignoreCase = true) }, source.protocol.name),
        description = source.address(withScheme = false),
        artwork = if (posters.isEmpty()) null else ({ PosterFan(posters) }),
        initialFocus = scan,
    ) { sheet ->
        if (!confirming) {
            LaunchedEffect(backFromConfirm) { if (backFromConfirm) runCatching { remove.requestFocus() } }
            HlSettingRow(
                stringResource(R.string.scan_now),
                onClick = { sheet.dismiss { vm.library.scan() } },
                chevron = false,
                modifier = Modifier.focusRequester(scan),
            )
            Spacer(Modifier.height(HlSpace.s))
            HlDivider()
            Spacer(Modifier.height(HlSpace.s))
            HlSettingRow(
                stringResource(R.string.remove_folder),
                description = stringResource(R.string.remove_folder_desc),
                onClick = { confirming = true },
                chevron = false,
                modifier = Modifier.focusRequester(remove),
            )
        } else {
            Confirm(
                question = stringResource(R.string.remove_folder_confirm, sourceName(source)),
                consequence = stringResource(R.string.remove_folder_desc),
                action = stringResource(R.string.remove),
                onCancel = {
                    confirming = false
                    backFromConfirm = true
                },
                onConfirm = { sheet.dismiss(onRemove) },
            )
        }
    }
}

/** The latest posters of a folder, side by side, at the head of its sheet. */
@Composable
private fun PosterFan(paths: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xxs)) {
        paths.take(HEADER_POSTERS).forEach { path ->
            PosterThumb(LocalProvider.image(path, ImageType.POSTER), width = HlSize.rowPreviewPoster)
        }
    }
}

@Composable
private fun sourceName(source: SourceEntity) = source.name.replaceFirstChar { it.uppercase() }

/**
 * A watched folder, headed by its latest posters as a cover from edge to edge; then its name and
 * kind, where it lives, and how its last scan went (or the scan going on). OK opens its actions.
 */
@Composable
private fun SourceCard(
    source: SourceEntity,
    scanning: Boolean,
    progress: Float,
    current: String?,
    error: ScanError?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Hl.colors
    val library = settingsViewModel().library
    val count by remember(source.id) { library.titleCount(source) }.collectAsStateWithLifecycle(0)
    val ultraHd by remember(source.id) { library.ultraHdCount(source) }.collectAsStateWithLifecycle(0)
    val posters by remember(source.id) { library.latestPosters(source, STRIP) }.collectAsStateWithLifecycle(emptyList())
    val kind = stringResource(if (source.kind == SourceKind.MOVIES) R.string.library_movies else R.string.library_shows)

    HlPanelButton(onClick, modifier.fillMaxWidth()) {
        Column {
            PosterCover(posters)
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
                    scanning -> Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressLine(progress.coerceAtLeast(PROGRESS_MIN), Modifier.width(HlSize.progressShort))
                        Spacer(Modifier.width(HlSpace.s))
                        Text(
                            current ?: "",
                            style = Hl.type.detail,
                            color = c.contentSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    error != null -> Text(scanErrorText(error, source.host), style = Hl.type.detail, color = c.tungsten)
                    else -> FactRow(
                        listOf(
                            pluralStringResource(R.plurals.title_count, count, count),
                            if (ultraHd > 0) pluralStringResource(R.plurals.uhd_count, ultraHd, ultraHd) else null,
                            stringResource(R.string.last_scan, relativeTime(source.lastScanAt)),
                        ),
                    )
                }
            }
        }
    }
}

/**
 * The latest posters of a folder, side by side from edge to edge at the head of its card, parted
 * by hairlines; the slots still free keep their frame, so every cover has the same rhythm.
 */
@Composable
private fun PosterCover(paths: List<String>) {
    val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { HlSize.poster.roundToPx() }
    Row(Modifier.fillMaxWidth().background(Hl.colors.background), horizontalArrangement = Arrangement.spacedBy(HlSize.hairline)) {
        repeat(STRIP) { i ->
            Box(Modifier.weight(1f).aspectRatio(2f / 3f).background(Hl.colors.surfaceRaised)) {
                paths.getOrNull(i)?.let { path ->
                    ArtworkImage(LocalProvider.image(path, ImageType.POSTER).url(widthPx), null, Modifier.fillMaxSize())
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
            value = if (scan.running) stringResource(R.string.scan_running, scan.processed, scan.total) else relativeTime(lastScan),
            valueLit = scan.running,
            onClick = { vm.library.scan() },
            chevron = false,
        )
        HlSettingRow(
            stringResource(R.string.refresh_metadata),
            description = stringResource(R.string.refresh_metadata_desc),
            onClick = { vm.library.scan(refreshMetadata = true) },
            chevron = false,
        )
        HlSettingRow(
            stringResource(R.string.tmdb_key),
            value = stringResource(
                when {
                    s.tmdbApiKey != null -> R.string.tmdb_personal
                    hasBuiltIn -> R.string.tmdb_builtin_short
                    else -> R.string.tmdb_missing_short
                }
            ),
            valueLit = s.tmdbApiKey != null || !hasBuiltIn,
            onClick = { editKey = true },
        )
        CollectionsRow()
        SourceRow(onUseFolders, onSignIn, rows.focus(SOURCE))
    }
    if (editKey) TmdbKeyDialog(onDismiss = { editKey = false })
}

/** When a saga shows as a collection: from two films (default), always, or never. */
@Composable
private fun CollectionsRow() {
    val vm = settingsViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    var choose by remember { mutableStateOf(false) }
    @Composable
    fun option(label: Int, description: Int) = ChoiceOption(stringResource(label), stringResource(description))
    val options = listOf(
        CollectionThreshold.TWO_FILMS to option(R.string.collections_two, R.string.collections_two_desc),
        CollectionThreshold.ANY to option(R.string.collections_all, R.string.collections_all_desc),
        CollectionThreshold.HIDDEN to option(R.string.collections_off, R.string.collections_off_desc),
    )
    val current = options.indexOfFirst { it.first == s.collectionMinFilms }.coerceAtLeast(0)
    HlSettingRow(
        stringResource(R.string.collections_setting),
        value = options[current].second.label,
        description = stringResource(R.string.collections_setting_desc),
        onClick = { choose = true },
    )
    if (choose) {
        HlChoiceSheet(
            title = stringResource(R.string.collections_setting),
            options = options.map { it.second },
            selected = current,
            onSelect = { i -> vm.setCollectionMinFilms(options[i].first) },
            onDismiss = { choose = false },
            description = stringResource(R.string.collections_setting_desc),
        )
    }
}

/** The source in use, and what changing it keeps; OK opens [SourceSheet]. */
@Composable
private fun SourceRow(onUseFolders: () -> Unit, onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    val vm = settingsViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    var choose by remember { mutableStateOf(false) }
    val server = s.source is SourceConfig.Jellyfin
    HlSettingRow(
        stringResource(R.string.change_source_row),
        modifier,
        value = stringResource(if (server) R.string.source_jellyfin else R.string.source_local),
        description = stringResource(sourceKeeps(s.source)),
        onClick = { choose = true },
    )
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
    var confirming by remember { mutableStateOf(false) }
    var backFromConfirm by remember { mutableStateOf(false) }
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
        if (confirming) {
            Confirm(
                question = stringResource(R.string.sign_out_confirm, serverName),
                consequence = stringResource(if (folders.isEmpty()) R.string.sign_out_first_folder else R.string.sign_out_desc),
                action = stringResource(R.string.sign_out),
                onCancel = {
                    confirming = false
                    backFromConfirm = true
                },
                onConfirm = onUseFolders,
            )
            return@HlSideSheet
        }
        LaunchedEffect(backFromConfirm) { if (backFromConfirm) runCatching { local.requestFocus() } }
        HlSettingRow(
            stringResource(R.string.source_local),
            description = foldersLine(folders.size),
            icon = HlIcons.Folder,
            onClick = {
                when {
                    source is SourceConfig.Jellyfin -> confirming = true
                    source == SourceConfig.Local && folders.isNotEmpty() -> sheet.dismiss()
                    else -> onUseFolders()
                }
            },
            chevron = false,
            trailing = if (source == SourceConfig.Local) ({ ChoiceCheck() }) else null,
            modifier = Modifier.focusRequester(local),
        )
        HlSettingRow(
            stringResource(R.string.source_jellyfin),
            description = stringResource(if (server != null) R.string.source_jellyfin_other else R.string.source_jellyfin_desc),
            icon = HlIcons.Server,
            onClick = onSignIn,
            chevron = false,
            trailing = if (server != null) ({ ChoiceCheck() }) else null,
            modifier = Modifier.focusRequester(jellyfin),
        )
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
    androidx.tv.material3.Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.width(HlSize.icon))
}

@Composable
private fun TmdbKeyDialog(onDismiss: () -> Unit) {
    val vm = settingsViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf(s.tmdbApiKey ?: "") }
    val field = remember { FocusRequester() }
    val hasBuiltIn = BuildConfig.TMDB_API_KEY.isNotBlank()
    HlSideSheet(
        stringResource(R.string.tmdb_key),
        onDismiss,
        description = stringResource(R.string.tmdb_desc),
        initialFocus = field,
    ) { sheet ->
        HlTextField("", key, { key = it }, password = true, modifier = Modifier.fillMaxWidth().focusRequester(field))
        Spacer(Modifier.height(HlSpace.s))
        Text(
            stringResource(if (hasBuiltIn) R.string.tmdb_key_builtin else R.string.tmdb_key_missing),
            style = Hl.type.detail,
            color = if (hasBuiltIn) Hl.colors.contentSecondary else Hl.colors.tungsten,
        )
        Spacer(Modifier.height(HlSpace.l))
        HlButton(
            stringResource(R.string.save),
            { vm.setTmdbApiKey(key) { sheet.dismiss() } },
            icon = Icons.Filled.Check,
            emphasis = Emphasis.Primary,
        )
    }
}
/** Posters in a source card's strip. */
private const val STRIP = 6

/** Posters at the head of a folder's sheet. */
private const val HEADER_POSTERS = 3

/** A scan that has just started still shows a sliver of progress. */
private const val PROGRESS_MIN = 0.02f

private const val ADD_FOLDER = "add_folder"
private const val SOURCE = "source"
