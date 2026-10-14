package app.houselights.ui.settings

import android.text.format.DateUtils
import android.view.Display
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.houselights.BuildConfig
import app.houselights.R
import app.houselights.core.Definition
import app.houselights.core.PathMapping
import app.houselights.core.SourceConfig
import app.houselights.player.PathResolver
import app.houselights.player.PlaybackManager
import app.houselights.player.ZidooStatus
import app.houselights.providers.local.LocalProvider
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.TmdbClient.Companion.ImageType
import app.houselights.settings.AppSettings
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.keepVerticalFocus
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.design.components.Badge
import app.houselights.ui.design.components.ChoiceOption
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.FactRow
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlChoiceSheet
import app.houselights.ui.design.components.HlIconButton
import app.houselights.ui.design.components.HlMeter
import app.houselights.ui.design.components.HlModal
import app.houselights.ui.design.components.HlNavItem
import app.houselights.ui.design.components.HlPanelButton
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.HlTwoPane
import app.houselights.ui.design.components.MeterLegend
import app.houselights.ui.design.components.MeterSegment
import app.houselights.ui.design.components.ProgressLine
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.components.Wordmark
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.formatBytes
import app.houselights.ui.util.formatDimensions
import app.houselights.ui.util.scanErrorText
import coil.compose.AsyncImage
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class Pane(val label: Int, val icon: ImageVector) {
    Library(R.string.section_source, HlIcons.Folder),
    Playback(R.string.section_playback, HlIcons.Playback),
    Display(R.string.section_display, HlIcons.Display),
    Storage(R.string.section_storage, HlIcons.Storage),
    About(R.string.section_about, HlIcons.Lamp),
}

/**
 * Settings in two panes: the categories on the left, each with what it is set to now; on the
 * right, the selected one as rows of settings with their value. Focus selects a category.
 */
@Composable
fun SettingsScreen(onAddFolder: () -> Unit, onChangeSource: () -> Unit) {
    val app = appContainer()
    val s by app.settings.state.collectAsStateWithLifecycle()
    val panes = if (s.source is SourceConfig.Jellyfin) Pane.entries else Pane.entries - Pane.Playback
    var pane by rememberSaveable { mutableStateOf(Pane.Library) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    val storage = rememberStorage()
    val titles by remember { app.database.dao().observeAllTitleCount() }.collectAsStateWithLifecycle(0)

    HlTwoPane(
        side = {
            ScreenTitle(stringResource(R.string.settings_title))
            Spacer(Modifier.height(HlSpace.xl))
            Column(Modifier.keepVerticalFocus(), verticalArrangement = Arrangement.spacedBy(HlSpace.xxs)) {
                panes.forEachIndexed { i, p ->
                    HlNavItem(
                        icon = p.icon,
                        title = stringResource(p.label),
                        summary = summary(p, s, titles, storage.stats),
                        selected = p == pane,
                        onFocused = { pane = p },
                        modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // The About pane shows the wordmark itself.
            if (pane != Pane.About) Wordmark(Modifier.padding(start = HlSpace.m, bottom = HlSpace.l))
        },
    ) {
        PaneContent(pane, s, storage, onAddFolder, onChangeSource)
    }
}

/** What a category is set to, under its name. */
@Composable
private fun summary(pane: Pane, s: AppSettings, titles: Int, stats: StorageStats?): String? = when (pane) {
    Pane.Library -> when (val source = s.source) {
        is SourceConfig.Jellyfin -> source.serverName ?: stringResource(R.string.source_jellyfin)
        else -> pluralStringResource(R.plurals.title_count, titles, titles)
    }
    Pane.Playback -> stringResource(if (s.forceHttp) R.string.http_stream else R.string.network_path)
    Pane.Display -> stringResource(if (s.ui4k) R.string.ui_4k else R.string.ui_system)
    Pane.Storage -> stats?.let { formatBytes(it.total) }
    Pane.About -> stringResource(R.string.nav_version, BuildConfig.VERSION_NAME)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PaneContent(pane: Pane, s: AppSettings, storage: StorageState, onAddFolder: () -> Unit, onChangeSource: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().keepVerticalFocus().focusRestorer(),
        contentPadding = PaddingValues(bottom = HlSpace.xxl, end = HlSpace.m),
        verticalArrangement = Arrangement.spacedBy(HlSpace.l),
    ) {
        when (pane) {
            Pane.Library -> libraryPane(s, onAddFolder, onChangeSource)
            Pane.Playback -> playbackPane(s)
            Pane.Display -> displayPane(s)
            Pane.Storage -> storagePane(storage)
            Pane.About -> aboutPane()
        }
    }
}

// --- Library -------------------------------------------------------------------------------

private fun LazyListScope.libraryPane(s: AppSettings, onAddFolder: () -> Unit, onChangeSource: () -> Unit) {
    when (val source = s.source) {
        SourceConfig.Local -> {
            item { Section(stringResource(R.string.section_source)) { Sources() } }
            item { LibraryActions(onAddFolder, onChangeSource) }
        }
        is SourceConfig.Jellyfin -> {
            item {
                Section(stringResource(R.string.source_jellyfin)) {
                    HlSettingGroup(null) {
                        HlSettingRow(stringResource(R.string.label_server), value = source.serverName ?: stringResource(R.string.source_jellyfin))
                        HlSettingRow(stringResource(R.string.label_address), value = source.serverUrl)
                        source.userName?.let { HlSettingRow(stringResource(R.string.label_user), value = it) }
                    }
                }
            }
            item { HlSettingGroup(null) { SourceRow(onChangeSource) } }
        }
        null -> {}
    }
}

/** One card per watched folder. */
@Composable
private fun Sources() {
    val app = appContainer()
    val scope = rememberCoroutineScope()
    val sources by app.database.dao().observeSources().collectAsStateWithLifecycle(emptyList())
    val scan by app.scanner.state.collectAsStateWithLifecycle()
    var actionsFor by remember { mutableStateOf<SourceEntity?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
        sources.forEach { source ->
            SourceCard(
                source,
                scanning = scan.running && scan.sourceName == source.name,
                progress = if (scan.total > 0) scan.processed.toFloat() / scan.total else 0f,
                current = scan.current,
                error = scan.errors[source.name],
                onClick = { actionsFor = source },
            )
        }
    }
    actionsFor?.let { source ->
        HlModal(sourceName(source), onDismiss = { actionsFor = null }, width = HlLayout.dialogNarrow) {
            HlSettingGroup(null) {
                HlSettingRow(
                    stringResource(R.string.scan_now),
                    onClick = { app.scanner.scanAll(); actionsFor = null },
                    chevron = false,
                )
                HlSettingRow(
                    stringResource(R.string.remove_folder),
                    description = stringResource(R.string.remove_folder_desc),
                    onClick = {
                        actionsFor = null
                        scope.launch {
                            app.database.dao().deleteItemsOfSource(source.id)
                            app.database.dao().deleteSource(source.id)
                            app.scanner.scanAll()
                        }
                    },
                    chevron = false,
                )
            }
        }
    }
}

@Composable
private fun sourceName(source: SourceEntity) = source.name.replaceFirstChar { it.uppercase() }

/**
 * A watched folder: a strip of its latest posters, its name and where it lives, how many titles
 * it holds and how its last scan went. OK opens its actions.
 */
@Composable
private fun SourceCard(
    source: SourceEntity,
    scanning: Boolean,
    progress: Float,
    current: String?,
    error: ScanError?,
    onClick: () -> Unit,
) {
    val c = Hl.colors
    val app = appContainer()
    val dao = app.database.dao()
    val count by remember(source.id) { dao.observeTitleCount(source.id) }.collectAsStateWithLifecycle(0)
    val ultraHd by remember(source.id) { dao.observeDefinitionCount(source.id, Definition.ULTRA_HD.token) }.collectAsStateWithLifecycle(0)
    val posters by remember(source.id) { dao.observePosters(source.id, STRIP) }.collectAsStateWithLifecycle(emptyList())
    val kind = stringResource(if (source.kind == SourceKind.MOVIES) R.string.library_movies else R.string.library_shows)

    HlPanelButton(onClick, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(HlSpace.l)) {
            PosterStrip(posters)
            Spacer(Modifier.height(HlSpace.l))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                Text(sourceName(source), style = Hl.type.lead, color = c.content)
                if (!source.name.equals(kind, ignoreCase = true)) Badge(kind)
                Badge(source.protocol.name)
            }
            Spacer(Modifier.height(HlSpace.xxs))
            Text(
                listOf(source.host, source.share, source.path).filter { it.isNotBlank() }.joinToString("/"),
                style = Hl.type.caption,
                color = c.contentSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(HlSpace.s))
            if (scanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressLine(progress.coerceAtLeast(PROGRESS_MIN), Modifier.width(HlSize.progressShort))
                    Spacer(Modifier.width(HlSpace.s))
                    Text(current ?: "", style = Hl.type.caption, color = c.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                FactRow(
                    listOf(
                        pluralStringResource(R.plurals.title_count, count, count),
                        if (ultraHd > 0) pluralStringResource(R.plurals.uhd_count, ultraHd, ultraHd) else null,
                        stringResource(R.string.last_scan, relativeTime(source.lastScanAt)),
                    ),
                )
            }
            if (error != null) {
                Spacer(Modifier.height(HlSpace.xs))
                Text(scanErrorText(error, source.host), style = Hl.type.caption, color = c.tungsten)
            }
        }
    }
}

/** The latest posters of a folder, side by side across the card; empty frames make up the count. */
@Composable
private fun PosterStrip(paths: List<String>) {
    val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { HlSize.poster.roundToPx() }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
        repeat(STRIP) { i ->
            Box(Modifier.weight(1f).aspectRatio(2f / 3f).clip(HlShapes.artwork).background(Hl.colors.surfaceRaised)) {
                paths.getOrNull(i)?.let { path ->
                    AsyncImage(
                        model = LocalProvider.image(path, ImageType.POSTER).url(widthPx),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryActions(onAddFolder: () -> Unit, onChangeSource: () -> Unit) {
    val app = appContainer()
    val s by app.settings.state.collectAsStateWithLifecycle()
    val scan by app.scanner.state.collectAsStateWithLifecycle()
    val sources by app.database.dao().observeSources().collectAsStateWithLifecycle(emptyList())
    var editKey by remember { mutableStateOf(false) }
    val lastScan = sources.maxOfOrNull { it.lastScanAt } ?: 0L
    val hasBuiltIn = BuildConfig.TMDB_API_KEY.isNotBlank()

    HlSettingGroup(null, Modifier.revealOnFocus()) {
        HlSettingRow(stringResource(R.string.add_folder), onClick = onAddFolder, chevron = false, trailing = { PlusIcon() })
        HlSettingRow(
            stringResource(R.string.scan_now),
            value = if (scan.running) stringResource(R.string.scan_running, scan.processed, scan.total) else relativeTime(lastScan),
            valueLit = scan.running,
            onClick = { app.scanner.scanAll() },
            chevron = false,
        )
        HlSettingRow(
            stringResource(R.string.refresh_metadata),
            description = stringResource(R.string.refresh_metadata_desc),
            onClick = { app.scanner.scanAll(refreshMetadata = true) },
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
        SourceRow(onChangeSource)
    }
    if (editKey) TmdbKeyDialog(onDismiss = { editKey = false })
}

@Composable
private fun SourceRow(onChangeSource: () -> Unit) {
    val app = appContainer()
    val s by app.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    HlSettingRow(
        stringResource(R.string.change_source_row),
        value = stringResource(if (s.source is SourceConfig.Jellyfin) R.string.source_jellyfin else R.string.source_local),
        description = stringResource(R.string.change_source_desc),
        onClick = { scope.launch { app.settings.setSource(null); onChangeSource() } },
    )
}

@Composable
private fun PlusIcon() {
    androidx.tv.material3.Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.width(HlSize.icon))
}

@Composable
private fun TmdbKeyDialog(onDismiss: () -> Unit) {
    val app = appContainer()
    val scope = rememberCoroutineScope()
    val s by app.settings.state.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf(s.tmdbApiKey ?: "") }
    val field = remember { FocusRequester() }
    val hasBuiltIn = BuildConfig.TMDB_API_KEY.isNotBlank()
    HlModal(stringResource(R.string.tmdb_key), onDismiss, width = HlLayout.dialogNarrow, initialFocus = field) {
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.m)) {
            Text(stringResource(R.string.tmdb_desc), style = Hl.type.body, color = Hl.colors.contentSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s), verticalAlignment = Alignment.CenterVertically) {
                HlTextField("", key, { key = it }, password = true, modifier = Modifier.weight(1f).focusRequester(field))
                HlButton(
                    stringResource(R.string.save),
                    { scope.launch { app.settings.setTmdbApiKey(key); onDismiss() } },
                    icon = Icons.Filled.Check,
                    emphasis = Emphasis.Primary,
                )
            }
            Text(
                stringResource(if (hasBuiltIn) R.string.tmdb_key_builtin else R.string.tmdb_key_missing),
                style = Hl.type.caption,
                color = if (hasBuiltIn) Hl.colors.contentSecondary else Hl.colors.tungsten,
            )
        }
    }
}

// --- Playback (Jellyfin) ---------------------------------------------------------------------

private fun LazyListScope.playbackPane(s: AppSettings) {
    item {
        val app = appContainer()
        val scope = rememberCoroutineScope()
        var choose by remember { mutableStateOf(false) }
        Section(stringResource(R.string.section_playback)) {
            HlSettingGroup(null) {
                HlSettingRow(
                    stringResource(R.string.file_access),
                    value = stringResource(if (s.forceHttp) R.string.http_stream else R.string.network_path),
                    description = stringResource(R.string.playback_desc),
                    onClick = { choose = true },
                )
            }
        }
        if (choose) {
            HlChoiceSheet(
                title = stringResource(R.string.file_access),
                options = listOf(
                    ChoiceOption(stringResource(R.string.network_path), stringResource(R.string.network_path_desc)),
                    ChoiceOption(stringResource(R.string.http_stream), stringResource(R.string.http_stream_desc)),
                ),
                selected = if (s.forceHttp) 1 else 0,
                onSelect = { scope.launch { app.settings.setForceHttp(it == 1) } },
                onDismiss = { choose = false },
            )
        }
    }
    item { HlSettingGroup(stringResource(R.string.path_mappings), Modifier.revealOnFocus()) { PathMappings(s) } }
}

@Composable
private fun PathMappings(s: AppSettings) {
    val app = appContainer()
    val scope = rememberCoroutineScope()
    val mappings = remember { mutableStateListOf<PathMapping>().apply { addAll(s.pathMappings) } }
    var saved by remember { mutableStateOf(false) }
    var samplePath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { samplePath = runCatching { app.provider.samplePath() }.getOrNull() }

    Column(Modifier.padding(horizontal = HlSpace.l), verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
        Hint(stringResource(R.string.path_mapping_help))
        samplePath?.let { Hint(stringResource(R.string.sample_path, it)) }
        mappings.forEachIndexed { index, m ->
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s), verticalAlignment = Alignment.Bottom) {
                HlTextField(
                    stringResource(R.string.server_path), m.from, { mappings[index] = m.copy(from = it); saved = false },
                    placeholder = "/media/movies", modifier = Modifier.weight(1f),
                )
                HlTextField(
                    stringResource(R.string.network_path), m.to, { mappings[index] = m.copy(to = it); saved = false },
                    placeholder = "nfs://192.168.1.10/volume1/movies", modifier = Modifier.weight(1.3f),
                )
                HlIconButton(Icons.Filled.Close, stringResource(R.string.remove), onClick = { mappings.removeAt(index); saved = false })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
            HlButton(stringResource(R.string.add_mapping), onClick = { mappings.add(PathMapping("", "")) }, icon = Icons.Filled.Add)
            HlButton(
                stringResource(if (saved) R.string.saved else R.string.save),
                onClick = {
                    scope.launch {
                        app.settings.setPathMappings(mappings.filter { it.from.isNotBlank() && it.to.isNotBlank() })
                        saved = true
                    }
                },
                icon = Icons.Filled.Check,
                emphasis = if (saved) Emphasis.Secondary else Emphasis.Primary,
            )
        }
        samplePath?.let { path ->
            val resolved = PathResolver.resolve(path, mappings.toList())
            Hint(stringResource(R.string.mapping_result, resolved ?: stringResource(R.string.mapping_none)))
        }
    }
}

// --- Display ---------------------------------------------------------------------------------

private fun LazyListScope.displayPane(s: AppSettings) {
    item {
        val app = appContainer()
        val scope = rememberCoroutineScope()
        var choose by remember { mutableStateOf(false) }
        Section(stringResource(R.string.section_display)) {
            HlSettingGroup(null) {
                HlSettingRow(
                    stringResource(R.string.ui_resolution),
                    value = stringResource(if (s.ui4k) R.string.ui_4k else R.string.ui_system),
                    description = stringResource(R.string.ui_resolution_desc),
                    onClick = { choose = true },
                )
            }
        }
        if (choose) {
            HlChoiceSheet(
                title = stringResource(R.string.ui_resolution),
                options = listOf(
                    ChoiceOption(stringResource(R.string.ui_4k), stringResource(R.string.ui_4k_desc)),
                    ChoiceOption(stringResource(R.string.ui_system), stringResource(R.string.ui_system_desc)),
                ),
                selected = if (s.ui4k) 0 else 1,
                onSelect = { scope.launch { app.settings.setUi4k(it == 0) } },
                onDismiss = { choose = false },
            )
        }
    }
    item { CurrentOutput() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrentOutput() {
    val view = LocalView.current
    val display: Display = view.display ?: return
    val mode = display.mode
    val modes = display.supportedModes
        .sortedWith(compareByDescending<Display.Mode> { it.physicalWidth }.thenByDescending { it.refreshRate })
        .map { "${it.physicalHeight}p${hz(it.refreshRate)}" }
        .distinct()
    HlSettingGroup(stringResource(R.string.current_output)) {
        HlSettingRow(stringResource(R.string.label_hdmi), value = "${mode.physicalHeight}p$FACT_GAP${hz(mode.refreshRate)}${Typography.nbsp}Hz")
        HlSettingRow(
            stringResource(R.string.label_render),
            trailing = {
                FactRow(
                    listOf(
                        formatDimensions(view.rootView.width, view.rootView.height),
                        "${view.resources.displayMetrics.densityDpi}${Typography.nbsp}dpi",
                    ),
                )
            },
        )
        Column(Modifier.padding(horizontal = HlSpace.l, vertical = HlSpace.s)) {
            Text(stringResource(R.string.label_modes), style = Hl.type.label, color = Hl.colors.content)
            Spacer(Modifier.height(HlSpace.s))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(HlSpace.xs), verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                modes.forEach { Badge(it) }
            }
        }
    }
}

private fun hz(hz: Float): String =
    if ((hz * 100).roundToInt() % 100 == 0) "${hz.roundToInt()}" else "%.2f".format(hz)

// --- Storage ---------------------------------------------------------------------------------

private data class StorageStats(
    val artworkFiles: Int,
    val artworkBytes: Long,
    val imageCacheBytes: Long,
    val imageCacheMax: Long,
    val databaseBytes: Long,
) {
    val total get() = artworkBytes + imageCacheBytes + databaseBytes
}

private class StorageState(val stats: StorageStats?, val refresh: suspend () -> Unit)

/** What Houselights keeps on the device, read once and again after a cleanup. */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
@Composable
private fun rememberStorage(): StorageState {
    val app = appContainer()
    val context = LocalContext.current
    var stats by remember { mutableStateOf<StorageStats?>(null) }
    val refresh: suspend () -> Unit = {
        stats = withContext(Dispatchers.IO) {
            val usage = app.artwork.usage()
            val disk = context.imageLoader.diskCache
            val db = context.getDatabasePath("library.db")
            StorageStats(
                artworkFiles = usage.files,
                artworkBytes = usage.bytes,
                imageCacheBytes = disk?.size ?: 0L,
                imageCacheMax = disk?.maxSize ?: 0L,
                // Room keeps recent writes in a write-ahead log next to the file.
                databaseBytes = listOf(db, java.io.File(db.path + "-wal")).sumOf { if (it.exists()) it.length() else 0L },
            )
        }
    }
    LaunchedEffect(Unit) { refresh() }
    return StorageState(stats, refresh)
}

private fun LazyListScope.storagePane(storage: StorageState) {
    item { Section(stringResource(R.string.section_storage)) { StorageSummary(storage.stats) } }
    item { StorageActions(storage) }
}

/** How much is used, and by what: a figure, a gauge against the image cache, a legend. */
@Composable
private fun StorageSummary(st: StorageStats?) {
    val c = Hl.colors
    val dash = "–"
    Column(Modifier.widthIn(max = HlLayout.readable)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(st?.let { formatBytes(it.total) } ?: dash, style = Hl.type.figure, color = c.content)
            Spacer(Modifier.width(HlSpace.s))
            Text(stringResource(R.string.storage_used), style = Hl.type.caption, color = c.contentSecondary, modifier = Modifier.padding(bottom = HlSpace.xxs))
        }
        Spacer(Modifier.height(HlSpace.m))
        val capacity = st?.let { maxOf(it.imageCacheMax, it.total, 1L).toFloat() } ?: 1f
        HlMeter(
            listOfNotNull(
                st?.let { MeterSegment(it.imageCacheBytes / capacity, c.content) },
                st?.let { MeterSegment(it.artworkBytes / capacity, c.tungsten) },
                st?.let { MeterSegment(it.databaseBytes / capacity, c.contentSecondary) },
            ),
        )
        Spacer(Modifier.height(HlSpace.m))
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
            MeterLegend(stringResource(R.string.storage_image_cache), st?.let { formatBytes(it.imageCacheBytes) } ?: dash, c.content)
            MeterLegend(
                stringResource(R.string.storage_artwork),
                st?.let { if (it.artworkFiles == 0) stringResource(R.string.storage_none) else pluralStringResource(R.plurals.file_count, it.artworkFiles, it.artworkFiles) + FACT_GAP + formatBytes(it.artworkBytes) } ?: dash,
                c.tungsten,
            )
            MeterLegend(stringResource(R.string.storage_database), st?.let { formatBytes(it.databaseBytes) } ?: dash, c.contentSecondary)
        }
        st?.let {
            Spacer(Modifier.height(HlSpace.m))
            Hint(stringResource(R.string.storage_cache_limit, formatBytes(it.imageCacheMax)))
        }
    }
}

@Composable
private fun StorageActions(storage: StorageState) {
    val app = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cleaned by remember { mutableStateOf<String?>(null) }
    var cleared by remember { mutableStateOf(false) }
    HlSettingGroup(null, Modifier.revealOnFocus()) {
        HlSettingRow(
            stringResource(R.string.cleanup_now),
            description = stringResource(R.string.cleanup_desc),
            value = cleaned,
            onClick = {
                scope.launch {
                    val result = app.scanner.cleanup()
                    cleaned = if (result.deletedFiles == 0) context.getString(R.string.cleanup_nothing)
                    else context.getString(
                        R.string.cleanup_done,
                        context.resources.getQuantityString(R.plurals.file_count, result.deletedFiles, result.deletedFiles),
                        formatBytes(context, result.freedBytes),
                    )
                    storage.refresh()
                }
            },
            chevron = false,
        )
        @OptIn(coil.annotation.ExperimentalCoilApi::class)
        HlSettingRow(
            stringResource(R.string.clear_image_cache),
            description = stringResource(R.string.clear_cache_desc),
            value = if (cleared) stringResource(R.string.image_cache_cleared) else storage.stats?.let { formatBytes(it.imageCacheBytes) },
            onClick = {
                scope.launch {
                    context.imageLoader.memoryCache?.clear()
                    withContext(Dispatchers.IO) { context.imageLoader.diskCache?.clear() }
                    cleared = true
                    storage.refresh()
                }
            },
            chevron = false,
        )
    }
}

// --- About -----------------------------------------------------------------------------------

private fun LazyListScope.aboutPane() {
    item { AboutBrand() }
    item { AboutStatus() }
    item { AboutCredits() }
}

/** The house light and its name, with what this copy is. */
@Composable
private fun AboutBrand() {
    Column {
        Wordmark(style = Hl.type.wordmarkHero)
        Spacer(Modifier.height(HlSpace.xs))
        FactRow(
            listOf(
                stringResource(R.string.nav_version, BuildConfig.VERSION_NAME),
                stringResource(R.string.about_free_software, LICENSE),
            ),
        )
    }
}

@Composable
private fun AboutStatus() {
    val context = LocalContext.current
    var api by remember { mutableStateOf<Boolean?>(null) }
    val player = remember {
        runCatching { context.packageManager.getPackageInfo(PlaybackManager.ZIDOO_PACKAGE, 0) }.isSuccess
    }
    LaunchedEffect(Unit) { api = ZidooStatus.isAvailable() }
    HlSettingGroup(stringResource(R.string.about_status)) {
        HlSettingRow(
            stringResource(R.string.label_player),
            trailing = {
                HlStatus(
                    if (player) StatusState.On else StatusState.Off,
                    stringResource(if (player) R.string.detected else R.string.not_detected),
                )
            },
        )
        HlSettingRow(
            stringResource(R.string.label_api),
            trailing = {
                HlStatus(
                    when (api) {
                        null -> StatusState.Pending
                        true -> StatusState.On
                        false -> StatusState.Off
                    },
                    stringResource(
                        when (api) {
                            null -> R.string.testing
                            true -> R.string.online
                            false -> R.string.offline
                        }
                    ),
                )
            },
        )
    }
}

@Composable
private fun AboutCredits() {
    HlSettingGroup(stringResource(R.string.about_credits)) {
        HlSettingRow(
            stringResource(R.string.credit_data),
            trailing = {
                Image(
                    painterResource(R.drawable.tmdb_logo),
                    contentDescription = "TMDB",
                    modifier = Modifier.height(HlSize.inlineIcon),
                )
            },
        )
        Hint(stringResource(R.string.tmdb_attribution), Modifier.padding(horizontal = HlSpace.l))
        HlSettingRow(stringResource(R.string.credit_font), value = stringResource(R.string.credit_font_value))
    }
}

// --- Building blocks -------------------------------------------------------------------------

/**
 * First group of a pane: the space aligning it with the navigation, its title and first
 * settings, revealed together so going back up always shows the title.
 */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.revealOnFocus()) {
        Spacer(Modifier.height(HlLayout.paneTop))
        Text(title, style = Hl.type.title, color = Hl.colors.content)
        Spacer(Modifier.height(HlSpace.l))
        content()
    }
}

/** A line of explanation under a group, in the reading measure. */
@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Hl.type.caption, color = Hl.colors.contentSecondary, modifier = modifier.widthIn(max = HlLayout.readable))
}

/** "il y a 2 minutes", "à l'instant", "jamais". */
@Composable
private fun relativeTime(at: Long): String {
    val now = System.currentTimeMillis()
    return when {
        at <= 0 -> stringResource(R.string.never)
        now - at < DateUtils.MINUTE_IN_MILLIS -> stringResource(R.string.just_now)
        else -> DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
}

/** Posters in a source card's strip. */
private const val STRIP = 6

/** A scan that has just started still shows a sliver of progress. */
private const val PROGRESS_MIN = 0.02f

private const val LICENSE = "GPL-3.0"
