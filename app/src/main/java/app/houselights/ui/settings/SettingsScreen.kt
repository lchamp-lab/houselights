package app.houselights.ui.settings

import android.text.format.DateUtils
import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.houselights.BuildConfig
import app.houselights.R
import app.houselights.core.PathMapping
import app.houselights.core.SourceConfig
import app.houselights.player.PathResolver
import app.houselights.player.PlaybackManager
import app.houselights.player.ZidooStatus
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.SourceEntity
import app.houselights.ui.util.scanErrorText
import app.houselights.providers.local.SourceKind
import app.houselights.settings.AppSettings
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.Backdrop
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.Badge
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlIconButton
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.ProgressLine
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.components.Wordmark
import app.houselights.ui.util.appContainer
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Pane(val label: Int, val icon: ImageVector) {
    Library(R.string.section_source, HlIcons.Folder),
    Playback(R.string.section_playback, HlIcons.Playback),
    Display(R.string.section_display, HlIcons.Display),
    About(R.string.section_about, HlIcons.Lamp),
}

/** Two panes: categories on the left, the selected one on the right. Focus selects. */
@Composable
fun SettingsScreen(onAddFolder: () -> Unit, onChangeSource: () -> Unit) {
    val app = appContainer()
    val s by app.settings.state.collectAsStateWithLifecycle()
    val panes = if (s.source is SourceConfig.Jellyfin) Pane.entries else Pane.entries - Pane.Playback
    var pane by rememberSaveable { mutableStateOf(Pane.Library) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        Backdrop(null, BackdropStyle.Quiet)
        Row(Modifier.fillMaxSize().padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.safeV)) {
            Column(Modifier.width(260.dp).fillMaxHeight()) {
                ScreenTitle(stringResource(R.string.settings_title))
                Spacer(Modifier.height(HlSpace.xl))
                panes.forEachIndexed { i, p ->
                    NavItem(
                        p,
                        selected = p == pane,
                        onFocused = { pane = p },
                        modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                    Spacer(Modifier.height(HlSpace.xxs))
                }
                Spacer(Modifier.weight(1f))
                Wordmark(Modifier.padding(start = HlSpace.m, bottom = HlSpace.l))
            }
            Spacer(Modifier.width(HlSpace.l))
            Box(Modifier.width(HlSize.hairline).fillMaxHeight().padding(vertical = HlSpace.xl).background(Hl.colors.outline.copy(alpha = 0.5f)))
            Spacer(Modifier.width(HlSpace.xl))
            PaneContent(pane, s, onAddFolder, onChangeSource, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NavItem(pane: Pane, selected: Boolean, onFocused: () -> Unit, modifier: Modifier = Modifier) {
    val c = Hl.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onFocused,
        modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(HlShapes.control),
        scale = ClickableSurfaceDefaults.scale(focusedScale = HlFocus.CONTROL_SCALE),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) c.content.copy(alpha = 0.08f) else Color.Transparent,
            contentColor = if (selected) c.content else c.contentSecondary,
            focusedContainerColor = c.focus,
            focusedContentColor = c.onFocus,
        ),
        interactionSource = interaction,
    ) {
        Row(Modifier.padding(horizontal = HlSpace.m, vertical = HlSpace.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(pane.icon, contentDescription = null, tint = if (selected && !focused) c.tungsten else androidx.tv.material3.LocalContentColor.current, modifier = Modifier.size(HlSize.icon))
            Spacer(Modifier.width(HlSpace.s))
            Text(stringResource(pane.label), style = Hl.type.label)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PaneContent(pane: Pane, s: AppSettings, onAddFolder: () -> Unit, onChangeSource: () -> Unit, modifier: Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxHeight().focusRestorer(),
        contentPadding = PaddingValues(top = 72.dp, bottom = HlSpace.xxl, end = HlSpace.m),
        verticalArrangement = Arrangement.spacedBy(HlSpace.l),
    ) {
        when (pane) {
            Pane.Library -> libraryPane(s, onAddFolder, onChangeSource)
            Pane.Playback -> playbackPane(s)
            Pane.Display -> displayPane(s)
            Pane.About -> aboutPane(s)
        }
    }
}

// --- Library -------------------------------------------------------------------------------

private fun LazyListScope.libraryPane(s: AppSettings, onAddFolder: () -> Unit, onChangeSource: () -> Unit) {
    when (val source = s.source) {
        SourceConfig.Local -> {
            item { PaneHeader(stringResource(R.string.section_source), stringResource(R.string.library_desc)) }
            item { Folders(onAddFolder) }
            item { TmdbKey() }
        }
        is SourceConfig.Jellyfin -> {
            item { PaneHeader(stringResource(R.string.source_jellyfin), null) }
            item {
                InfoList(
                    stringResource(R.string.label_server) to (source.serverName ?: "Jellyfin"),
                    stringResource(R.string.label_address) to source.serverUrl,
                    stringResource(R.string.label_user) to (source.userName ?: "?"),
                )
            }
        }
        null -> {}
    }
    item { ChangeSource(onChangeSource) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Folders(onAddFolder: () -> Unit) {
    val app = appContainer()
    val scope = rememberCoroutineScope()
    val sources by app.database.dao().observeSources().collectAsStateWithLifecycle(emptyList())
    val scan by app.scanner.state.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
        sources.forEach { source ->
            FolderCard(
                source,
                scanning = scan.running && scan.sourceName == source.name,
                progress = if (scan.total > 0) scan.processed.toFloat() / scan.total else 0f,
                current = scan.current,
                error = scan.errors[source.name],
                onRemove = {
                    scope.launch {
                        app.database.dao().deleteItemsOfSource(source.id)
                        app.database.dao().deleteSource(source.id)
                        app.scanner.scanAll()
                    }
                },
            )
        }
        Spacer(Modifier.height(HlSpace.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(HlSpace.s), verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
            HlButton(stringResource(R.string.add_folder), onAddFolder, icon = Icons.Filled.Add, emphasis = Emphasis.Primary)
            HlButton(stringResource(R.string.scan_now), { app.scanner.scanAll() }, icon = Icons.Filled.Refresh)
            HlButton(stringResource(R.string.refresh_metadata), { app.scanner.scanAll(refreshMetadata = true) })
        }
    }
}

/** One watched folder: what it holds, where it lives, and how its last scan went. */
@Composable
private fun FolderCard(
    source: SourceEntity,
    scanning: Boolean,
    progress: Float,
    current: String?,
    error: ScanError?,
    onRemove: () -> Unit,
) {
    val c = Hl.colors
    val app = appContainer()
    val count by remember(source.id) { app.database.dao().observeTitleCount(source.id) }.collectAsStateWithLifecycle(0)
    val kind = stringResource(if (source.kind == SourceKind.MOVIES) R.string.library_movies else R.string.library_shows)
    val now = System.currentTimeMillis()
    val lastScan = when {
        source.lastScanAt <= 0 -> stringResource(R.string.never)
        now - source.lastScanAt < DateUtils.MINUTE_IN_MILLIS -> stringResource(R.string.just_now)
        else -> DateUtils.getRelativeTimeSpanString(source.lastScanAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(HlShapes.panel)
            .background(c.surface.copy(alpha = 0.8f))
            .border(HlSize.hairline, c.outline.copy(alpha = 0.6f), HlShapes.panel)
            .padding(HlSpace.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(HlShapes.avatar).background(c.tungsten.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(HlIcons.Folder, contentDescription = null, tint = c.tungsten, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(HlSpace.l))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                Text(source.name.replaceFirstChar { it.uppercase() }, style = Hl.type.title, color = c.content)
                if (!source.name.equals(kind, ignoreCase = true)) Badge(kind)
                Badge(source.protocol.name)
            }
            Spacer(Modifier.height(HlSpace.xxs))
            Text(
                "${source.protocol.name.lowercase()}://${source.host}/${source.share}/${source.path}".trimEnd('/'),
                style = Hl.type.caption,
                color = c.contentTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(HlSpace.s))
            if (scanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressLine(progress.coerceAtLeast(0.02f), Modifier.width(160.dp))
                    Spacer(Modifier.width(HlSpace.s))
                    Text(current ?: "", style = Hl.type.caption, color = c.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l)) {
                    Text(pluralStringResource(R.plurals.title_count, count, count), style = Hl.type.meta, color = c.content)
                    Text(stringResource(R.string.last_scan, lastScan), style = Hl.type.meta, color = c.contentSecondary)
                }
            }
            if (error != null) {
                Spacer(Modifier.height(HlSpace.xs))
                Text(scanErrorText(error, source.host), style = Hl.type.caption, color = c.tungsten)
            }
        }
        Spacer(Modifier.width(HlSpace.l))
        HlIconButton(Icons.Filled.Close, stringResource(R.string.remove), onClick = onRemove)
    }
}

@Composable
private fun TmdbKey() {
    val app = appContainer()
    val scope = rememberCoroutineScope()
    val s by app.settings.state.collectAsStateWithLifecycle()
    var key by remember(s.tmdbApiKey) { mutableStateOf(s.tmdbApiKey ?: "") }
    var saved by remember { mutableStateOf(false) }
    val hasBuiltIn = BuildConfig.TMDB_API_KEY.isNotBlank()

    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
        SettingTitle(stringResource(R.string.tmdb_key), stringResource(R.string.tmdb_desc))
        Spacer(Modifier.height(HlSpace.xxs))
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s), verticalAlignment = Alignment.CenterVertically) {
            HlTextField(
                "", key, { key = it; saved = false },
                password = true, modifier = Modifier.width(340.dp),
            )
            HlButton(
                stringResource(if (saved) R.string.saved else R.string.save),
                { scope.launch { app.settings.setTmdbApiKey(key); saved = true } },
                icon = Icons.Filled.Check,
            )
        }
        when {
            hasBuiltIn -> Hint(stringResource(R.string.tmdb_key_builtin))
            s.tmdbApiKey == null -> Hint(stringResource(R.string.tmdb_key_missing), Hl.colors.tungsten)
        }
    }
}

@Composable
private fun ChangeSource(onChangeSource: () -> Unit) {
    val app = appContainer()
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
        Spacer(Modifier.height(HlSpace.xs))
        SettingTitle(stringResource(R.string.change_source), stringResource(R.string.change_source_desc))
        HlButton(stringResource(R.string.change_source), { scope.launch { app.settings.setSource(null); onChangeSource() } })
    }
}

// --- Playback (Jellyfin) ---------------------------------------------------------------------

private fun LazyListScope.playbackPane(s: AppSettings) {
    item { PaneHeader(stringResource(R.string.section_playback), stringResource(R.string.playback_desc)) }
    item { PlaybackSource(s) }
    item { PathMappings(s) }
}

@Composable
private fun PlaybackSource(s: AppSettings) {
    val app = appContainer()
    val scope = rememberCoroutineScope()
    Choice(
        title = stringResource(R.string.source_label),
        description = null,
        options = listOf(stringResource(R.string.network_path), stringResource(R.string.http_stream)),
        selected = if (s.forceHttp) 1 else 0,
        onSelect = { scope.launch { app.settings.setForceHttp(it == 1) } },
    )
}

@Composable
private fun PathMappings(s: AppSettings) {
    val app = appContainer()
    val scope = rememberCoroutineScope()
    val mappings = remember { mutableStateListOf<PathMapping>().apply { addAll(s.pathMappings) } }
    var saved by remember { mutableStateOf(false) }
    var samplePath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { samplePath = runCatching { app.provider.samplePath() }.getOrNull() }

    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
        Hint(stringResource(R.string.path_mapping_help))
        samplePath?.let { Hint(stringResource(R.string.sample_path, it), Hl.colors.contentTertiary) }
        mappings.forEachIndexed { index, m ->
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s), verticalAlignment = Alignment.Bottom) {
                HlTextField(
                    stringResource(R.string.server_path), m.from, { mappings[index] = m.copy(from = it); saved = false },
                    placeholder = "/media/movies", modifier = Modifier.width(260.dp),
                )
                HlTextField(
                    stringResource(R.string.network_path), m.to, { mappings[index] = m.copy(to = it); saved = false },
                    placeholder = "nfs://192.168.1.10/volume1/movies", modifier = Modifier.width(340.dp),
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
    item { PaneHeader(stringResource(R.string.section_display), stringResource(R.string.display_desc)) }
    item {
        val app = appContainer()
        val scope = rememberCoroutineScope()
        Choice(
            title = stringResource(R.string.ui_label),
            description = null,
            options = listOf(stringResource(R.string.ui_4k), stringResource(R.string.ui_system)),
            selected = if (s.ui4k) 0 else 1,
            onSelect = { scope.launch { app.settings.setUi4k(it == 0) } },
        )
    }
    item { DisplayInfo() }
}

@Composable
private fun DisplayInfo() {
    val view = LocalView.current
    val display: Display = view.display ?: return
    val mode = display.mode
    val modes = display.supportedModes
        .sortedWith(compareByDescending<Display.Mode> { it.physicalWidth }.thenByDescending { it.refreshRate })
        .joinToString("   ") { "${it.physicalHeight}p${fmtHz(it.refreshRate).removeSuffix(" Hz")}" }
    InfoList(
        stringResource(R.string.label_hdmi) to "${mode.physicalWidth}×${mode.physicalHeight}   ${fmtHz(mode.refreshRate)}",
        stringResource(R.string.label_render) to "${view.rootView.width}×${view.rootView.height} px   ${view.resources.displayMetrics.densityDpi} dpi",
        stringResource(R.string.label_modes) to modes,
    )
}

// --- About -----------------------------------------------------------------------------------

private fun LazyListScope.aboutPane(s: AppSettings) {
    item { PaneHeader(stringResource(R.string.section_about), null) }
    item { AboutInfo(s) }
    item { Hint(stringResource(R.string.tmdb_attribution), Hl.colors.contentTertiary) }
}

@Composable
private fun AboutInfo(s: AppSettings) {
    val context = LocalContext.current
    var api by remember { mutableStateOf<Boolean?>(null) }
    val player = remember {
        runCatching { context.packageManager.getPackageInfo(PlaybackManager.ZIDOO_PACKAGE, 0) }.isSuccess
    }
    LaunchedEffect(Unit) { api = ZidooStatus.isAvailable() }
    InfoList(
        stringResource(R.string.label_version) to BuildConfig.VERSION_NAME,
        stringResource(R.string.source_label) to when (s.source) {
            SourceConfig.Local -> stringResource(R.string.source_local)
            is SourceConfig.Jellyfin -> stringResource(R.string.source_jellyfin)
            null -> "–"
        },
        stringResource(R.string.label_player) to stringResource(if (player) R.string.present else R.string.absent),
        stringResource(R.string.label_api) to stringResource(
            when (api) {
                null -> R.string.testing
                true -> R.string.reachable
                false -> R.string.unreachable
            }
        ),
        stringResource(R.string.label_license) to "GPL-3.0",
    )
}

// --- Building blocks -------------------------------------------------------------------------

@Composable
private fun PaneHeader(title: String, description: String?) {
    Column(Modifier.widthIn(max = 640.dp)) {
        Text(title, style = Hl.type.title, color = Hl.colors.content)
        if (description != null) {
            Spacer(Modifier.height(HlSpace.xs))
            Text(description, style = Hl.type.bodySmall, color = Hl.colors.contentSecondary)
        }
    }
}

@Composable
private fun SettingTitle(title: String, description: String?) {
    Column {
        Text(title, style = Hl.type.label, color = Hl.colors.content)
        if (description != null) Text(description, style = Hl.type.caption, color = Hl.colors.contentTertiary)
    }
}

/** A setting with a few exclusive options, shown as a segmented row of tabs. */
@Composable
private fun Choice(title: String, description: String?, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
        SettingTitle(title, description)
        HlSegmented(options, selected, onSelect)
    }
}

/** Label and value pairs, aligned in two columns. */
@Composable
private fun InfoList(vararg rows: Pair<String, String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(HlShapes.panel)
            .background(Hl.colors.surface.copy(alpha = 0.6f))
            .padding(horizontal = HlSpace.l, vertical = HlSpace.m),
        verticalArrangement = Arrangement.spacedBy(HlSpace.s),
    ) {
        rows.forEach { (label, value) ->
            Row {
                Text(label, style = Hl.type.bodySmall, color = Hl.colors.contentTertiary, modifier = Modifier.width(220.dp))
                Text(value, style = Hl.type.bodySmall, color = Hl.colors.content)
            }
        }
    }
}

@Composable
private fun Hint(text: String, color: Color = Hl.colors.contentSecondary) {
    Text(text, style = Hl.type.bodySmall, color = color, modifier = Modifier.widthIn(max = 680.dp))
}

private fun fmtHz(hz: Float): String =
    if ((hz * 100).roundToInt() % 100 == 0) "${hz.roundToInt()} Hz" else "%.2f Hz".format(hz)
