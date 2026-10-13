package app.posterwall.ui.settings

import android.text.format.DateUtils
import android.view.Display
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.posterwall.BuildConfig
import app.posterwall.R
import app.posterwall.core.PathMapping
import app.posterwall.core.SourceConfig
import app.posterwall.player.PathResolver
import app.posterwall.player.PlaybackManager
import app.posterwall.player.ZidooStatus
import app.posterwall.providers.local.SourceEntity
import app.posterwall.providers.local.SourceKind
import app.posterwall.settings.AppSettings
import app.posterwall.ui.components.ActionButton
import app.posterwall.ui.components.Chip
import app.posterwall.ui.components.ImmersiveBackdrop
import app.posterwall.ui.components.SafeH
import app.posterwall.ui.components.SafeV
import app.posterwall.ui.components.SectionTitle
import app.posterwall.ui.components.TvTextField
import app.posterwall.ui.components.appContainer
import app.posterwall.ui.theme.Palette
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(onAddFolder: () -> Unit, onChangeSource: () -> Unit) {
    val app = appContainer()
    val scope = rememberCoroutineScope()
    val s by app.settings.state.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        ImmersiveBackdrop(null)
        LazyColumn(
            contentPadding = PaddingValues(start = SafeH + 48.dp, end = SafeH, top = SafeV + 8.dp, bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.displayMedium, color = Palette.Text) }

            item { Section(stringResource(R.string.section_source)) }
            when (val source = s.source) {
                SourceConfig.Local -> localLibrary(onAddFolder, firstFocus)
                is SourceConfig.Jellyfin -> {
                    item {
                        Info("${source.serverName ?: "Jellyfin"} · ${source.serverUrl}")
                        Info(stringResource(R.string.connected_as, source.userName ?: "?"))
                    }
                    jellyfinPlayback(s)
                }
                null -> {}
            }
            item {
                ActionButton(
                    stringResource(R.string.change_source),
                    onClick = { scope.launch { app.settings.setSource(null); onChangeSource() } },
                    modifier = if (s.source is SourceConfig.Jellyfin) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }

            item { Section(stringResource(R.string.section_display)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ui_label), color = Palette.TextDim, modifier = Modifier.width(140.dp))
                    Chip(stringResource(R.string.ui_4k), selected = s.ui4k, onClick = { scope.launch { app.settings.setUi4k(true) } })
                    Chip(stringResource(R.string.ui_system), selected = !s.ui4k, onClick = { scope.launch { app.settings.setUi4k(false) } })
                }
            }
            item { DisplayDiagnostics() }

            item { Section("Zidoo") }
            item { ZidooDiagnostics() }
        }
    }
}

private fun LazyListScope.localLibrary(onAddFolder: () -> Unit, firstFocus: FocusRequester) {
    item { LocalFolders(onAddFolder, firstFocus) }
    item { TmdbKey() }
}

@Composable
private fun LocalFolders(onAddFolder: () -> Unit, firstFocus: FocusRequester) {
    val app = appContainer()
    val scope = rememberCoroutineScope()
    val sources by app.database.dao().observeSources().collectAsStateWithLifecycle(emptyList())
    val scan by app.scanner.state.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.folders), color = Palette.Text, style = MaterialTheme.typography.titleMedium)
        sources.forEach { source -> FolderRow(source, onRemove = {
            scope.launch {
                app.database.dao().deleteItemsOfSource(source.id)
                app.database.dao().deleteSource(source.id)
                app.scanner.scanAll()
            }
        }) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(stringResource(R.string.add_folder), onAddFolder, Modifier.focusRequester(firstFocus), icon = Icons.Filled.Add)
            ActionButton(stringResource(R.string.scan_now), { app.scanner.scanAll() }, icon = Icons.Filled.Refresh)
            ActionButton(stringResource(R.string.refresh_metadata), { app.scanner.scanAll(refreshMetadata = true) })
        }
        if (scan.running) {
            Info(stringResource(R.string.scanning, scan.sourceName ?: "", scan.processed, scan.total) + (scan.current?.let { " · $it" } ?: ""))
        }
        scan.errors.forEach { (name, error) -> Info("$name : $error", Palette.Accent) }
    }
}

@Composable
private fun FolderRow(source: SourceEntity, onRemove: () -> Unit) {
    val kind = stringResource(if (source.kind == SourceKind.MOVIES) R.string.library_movies else R.string.library_shows)
    val lastScan = if (source.lastScanAt > 0) DateUtils.getRelativeTimeSpanString(source.lastScanAt).toString()
    else stringResource(R.string.never)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(620.dp)) {
            Text("${source.name}  ·  $kind", color = Palette.Text)
            Text(
                "smb://${source.host}/${source.share}/${source.path}".trimEnd('/') + "  ·  " +
                    stringResource(R.string.last_scan, lastScan),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextFaint,
            )
        }
        ActionButton(stringResource(R.string.remove), onRemove, icon = Icons.Filled.Close)
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            TvTextField(stringResource(R.string.tmdb_key), key, { key = it; saved = false }, modifier = Modifier.width(520.dp))
            ActionButton(
                stringResource(if (saved) R.string.saved else R.string.save),
                { scope.launch { app.settings.setTmdbApiKey(key); saved = true } },
                icon = Icons.Filled.Check,
            )
        }
        when {
            hasBuiltIn -> Info(stringResource(R.string.tmdb_key_builtin))
            s.tmdbApiKey == null -> Info(stringResource(R.string.tmdb_key_missing), Palette.Accent)
        }
    }
}

private fun LazyListScope.jellyfinPlayback(s: AppSettings) {
    item { Section(stringResource(R.string.section_playback)) }
    item { PlaybackSource(s) }
    item { PathMappings(s) }
}

@Composable
private fun PlaybackSource(s: AppSettings) {
    val app = appContainer()
    val scope = rememberCoroutineScope()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.source_label), color = Palette.TextDim, modifier = Modifier.width(140.dp))
        Chip(stringResource(R.string.network_path), selected = !s.forceHttp, onClick = { scope.launch { app.settings.setForceHttp(false) } })
        Chip(stringResource(R.string.http_stream), selected = s.forceHttp, onClick = { scope.launch { app.settings.setForceHttp(true) } })
    }
}

@Composable
private fun PathMappings(s: AppSettings) {
    val app = appContainer()
    val scope = rememberCoroutineScope()
    val mappings = remember { mutableStateListOf<PathMapping>().apply { addAll(s.pathMappings) } }
    var saved by remember { mutableStateOf(false) }
    var samplePath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { samplePath = runCatching { app.provider.samplePath() }.getOrNull() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Info(stringResource(R.string.path_mapping_help))
        samplePath?.let { Info(stringResource(R.string.sample_path, it)) }
        mappings.forEachIndexed { index, m ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                TvTextField(
                    stringResource(R.string.server_path), m.from, { mappings[index] = m.copy(from = it); saved = false },
                    placeholder = "/media/movies", modifier = Modifier.width(300.dp),
                )
                TvTextField(
                    stringResource(R.string.network_path), m.to, { mappings[index] = m.copy(to = it); saved = false },
                    placeholder = "smb://user:password@192.168.1.10/movies", modifier = Modifier.width(380.dp),
                )
                ActionButton("", onClick = { mappings.removeAt(index); saved = false }, icon = Icons.Filled.Close)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(stringResource(R.string.add_mapping), onClick = { mappings.add(PathMapping("", "")) }, icon = Icons.Filled.Add)
            ActionButton(
                stringResource(if (saved) R.string.saved else R.string.save),
                onClick = {
                    scope.launch {
                        app.settings.setPathMappings(mappings.filter { it.from.isNotBlank() && it.to.isNotBlank() })
                        saved = true
                    }
                },
                icon = Icons.Filled.Check,
                primary = !saved,
            )
        }
        samplePath?.let { path ->
            val resolved = PathResolver.resolve(path, mappings.toList())
            Info(stringResource(R.string.mapping_result, resolved ?: stringResource(R.string.mapping_none)))
        }
    }
}

@Composable
private fun DisplayDiagnostics() {
    val view = LocalView.current
    val display: Display = view.display ?: return
    val metrics = view.resources.displayMetrics
    val mode = display.mode
    Column {
        Info(stringResource(R.string.hdmi_output, mode.physicalWidth, mode.physicalHeight, fmtHz(mode.refreshRate)))
        Info(stringResource(R.string.ui_render, view.rootView.width, view.rootView.height, metrics.densityDpi))
        val modes = display.supportedModes
            .sortedWith(compareByDescending<Display.Mode> { it.physicalWidth }.thenByDescending { it.refreshRate })
            .joinToString("  ") { "${it.physicalHeight}p${fmtHz(it.refreshRate).removeSuffix(" Hz")}" }
        Info(stringResource(R.string.modes_available, modes))
    }
}

@Composable
private fun ZidooDiagnostics() {
    val context = LocalContext.current
    var api by remember { mutableStateOf<Boolean?>(null) }
    val player = remember {
        runCatching { context.packageManager.getPackageInfo(PlaybackManager.ZIDOO_PACKAGE, 0) }.isSuccess
    }
    LaunchedEffect(Unit) { api = ZidooStatus.isAvailable() }
    Column {
        Info(stringResource(R.string.native_player, PlaybackManager.ZIDOO_PACKAGE, stringResource(if (player) R.string.present else R.string.absent)))
        Info(
            stringResource(
                R.string.control_api,
                stringResource(
                    when (api) {
                        null -> R.string.testing
                        true -> R.string.reachable
                        false -> R.string.unreachable
                    }
                ),
            )
        )
        Spacer(Modifier.height(10.dp))
        Info(stringResource(R.string.version, BuildConfig.VERSION_NAME))
    }
}

private fun fmtHz(hz: Float): String =
    if ((hz * 100).roundToInt() % 100 == 0) "${hz.roundToInt()} Hz" else "%.2f Hz".format(hz)

@Composable
private fun Section(title: String) {
    Column {
        Spacer(Modifier.height(18.dp))
        SectionTitle(title)
    }
}

@Composable
private fun Info(text: String, color: androidx.compose.ui.graphics.Color = Palette.TextDim) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.padding(vertical = 2.dp).width(820.dp))
}
