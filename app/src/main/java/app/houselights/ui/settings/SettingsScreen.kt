package app.houselights.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.view.Display
import app.houselights.BuildConfig
import app.houselights.R
import app.houselights.core.SourceConfig
import app.houselights.settings.AppSettings
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.HlNavItem
import app.houselights.ui.design.components.HlTwoPane
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.components.Wordmark
import app.houselights.ui.design.keepVerticalFocus
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.RestoreFocus
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.keepChildFocus
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.util.formatBytes

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
 * [onUseFolders] makes the folders the source (or adds the first), [onSignIn] signs in to Jellyfin.
 */
@Composable
fun SettingsScreen(
    onAddFolder: () -> Unit,
    onUseFolders: () -> Unit,
    onSignIn: () -> Unit,
    onOpenCatalog: () -> Unit = {},
) {
    val vm = settingsViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    val panes = if (s.source != null && vm.capabilities.pathMapping) Pane.entries else Pane.entries - Pane.Playback
    var pane by rememberSaveable { mutableStateOf(Pane.Library) }
    val memory = rememberFocusMemory()
    // Each arrival puts focus back on the row that opened another screen, else on the entry of the
    // pane open: the pane stays as it was left.
    RestoreFocus(memory, fallback = memory.requester(paneKey(pane.takeIf { it in panes } ?: panes.first())))
    val storage by vm.storage.collectAsStateWithLifecycle()
    val titles by vm.library.titleCount.collectAsStateWithLifecycle(0)

    HlTwoPane(
        side = {
            ScreenTitle(stringResource(R.string.settings_title))
            Spacer(Modifier.height(HlSpace.xl))
            Column(Modifier.keepVerticalFocus(), verticalArrangement = Arrangement.spacedBy(HlSpace.xxs)) {
                panes.forEach { p ->
                    HlNavItem(
                        icon = p.icon,
                        title = stringResource(p.label),
                        summary = summary(p, s, titles, storage),
                        selected = p == pane,
                        onFocused = { pane = p },
                        modifier = Modifier.focusKey(paneKey(p), memory),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // The About pane shows the wordmark itself.
            if (pane != Pane.About) Wordmark(Modifier.padding(start = HlSpace.m, bottom = HlSpace.l))
        },
    ) {
        val rows = remember(pane) { PaneRows(memory, pane.name) }
        PaneContent(pane, s, storage, onAddFolder, onUseFolders, onSignIn, onOpenCatalog, rows)
    }
}

/** A pane's entry in the navigation; its rows are keyed within it. */
private fun paneKey(pane: Pane) = FocusKey.pane(pane.name)

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

/** The rows of [pane]. Focus coming back from the navigation returns to the row it left. */
@Composable
private fun PaneContent(
    pane: Pane,
    s: AppSettings,
    storage: StorageStats?,
    onAddFolder: () -> Unit,
    onUseFolders: () -> Unit,
    onSignIn: () -> Unit,
    onOpenCatalog: () -> Unit,
    rows: PaneRows,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().keepVerticalFocus().keepChildFocus(),
        contentPadding = PaddingValues(bottom = HlFocus.edge, end = HlSpace.m),
        verticalArrangement = Arrangement.spacedBy(HlSpace.l),
    ) {
        when (pane) {
            Pane.Library -> libraryPane(s, onAddFolder, onUseFolders, onSignIn, rows)
            Pane.Playback -> playbackPane(s)
            Pane.Display -> displayPane(s)
            Pane.Storage -> storagePane(storage)
            Pane.About -> aboutPane(onOpenCatalog, rows)
        }
    }
}
