package app.houselights.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.houselights.BuildConfig
import app.houselights.R
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.SourceConfig
import app.houselights.settings.AppSettings
import app.houselights.settings.SecretSetting
import app.houselights.ui.design.BackToStart
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.RestoreFocus
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.HlNavItem
import app.houselights.ui.design.components.HlTwoPane
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.keepVerticalFocus
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.onboarding.FolderField
import app.houselights.ui.util.UiError
import app.houselights.ui.util.formatBytes

internal enum class Pane(val label: Int, val icon: ImageVector) {
    Library(R.string.section_source, HlIcons.Folder),
    Numbers(R.string.section_numbers, HlIcons.Numbers),
    Playback(R.string.section_playback, HlIcons.Playback),
    Display(R.string.section_display, HlIcons.Display),
    Storage(R.string.section_storage, HlIcons.Storage),
    About(R.string.section_about, HlIcons.Info),
}

/**
 * Settings in two panes: categories with their current values, and the focused one's rows. [onUseFolders] makes
 * the folders the source and reports a failure on the source's row; the library in numbers opens a filtered wall
 * ([onOpenWall]) or a director's page ([onOpenPerson]).
 */
@Composable
fun SettingsScreen(
    onFolderForm: (folderId: Long?, focus: FolderField) -> Unit,
    onUseFolders: (onError: (UiError) -> Unit) -> Unit,
    onSignIn: () -> Unit,
    onOpenCatalog: () -> Unit = {},
    onOpenWall: (Library, LibraryFilter) -> Unit = { _, _ -> },
    onOpenPerson: (personId: String) -> Unit = {},
) {
    val vm = settingsViewModel()
    val useFolders = {
        vm.clearActionError()
        onUseFolders { vm.actionFailed(SettingAction.SOURCE, it) }
    }
    val s by vm.settings.collectAsStateWithLifecycle()
    val panes = Pane.entries.filter { p ->
        when (p) {
            Pane.Playback -> s.source != null && vm.capabilities.pathMapping
            Pane.Numbers -> s.source != null && vm.counts
            else -> true
        }
    }
    var pane by rememberSaveable { mutableStateOf(Pane.Library) }
    // A failed change is reported in the pane it was made in, not in the next one.
    LaunchedEffect(pane) { vm.clearActionError() }
    val memory = rememberFocusMemory()
    // Each pane keeps its scroll position across the screens it opens.
    val list = rememberSaveable(pane, saver = LazyListState.Saver) { LazyListState() }
    // Focus returns to the row that opened another screen, else to the open pane's entry.
    RestoreFocus(
        memory,
        fallback = memory.requester(paneKey(pane.takeIf { it in panes } ?: panes.first())),
        // Lay out the panes with the stored settings, not the defaults shown before them.
        ready = s.loaded,
    ) { key -> revealRow(list, key, memory) }
    // From the pane on the right, Back returns to its entry; from the entries, it leaves.
    var inPane by remember { mutableStateOf(false) }
    BackToStart(away = inPane) { memory.focus(paneKey(pane)) }
    val storage by vm.storage.collectAsStateWithLifecycle()
    val titles by vm.library.titleCount.collectAsStateWithLifecycle(0)

    HlTwoPane(
        state = list,
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
        },
    ) {
        val rows = remember(pane) { PaneRows(memory, pane.name) }
        PaneContent(
            pane, s, storage, onFolderForm, useFolders, onSignIn, onOpenCatalog, rows, list,
            entry = memory.requester(paneKey(pane)),
            onFocusIn = { inPane = it },
            numbers = NumbersDoors(onOpenWall, onOpenPerson),
        )
    }
}

/** Scrolls [list] item by item until row [key] can be focused (a pane holds a dozen items at most). */
private suspend fun revealRow(list: LazyListState, key: String, memory: FocusMemory): Boolean {
    for (i in 0 until list.layoutInfo.totalItemsCount) {
        list.scrollToItem(i)
        withFrameNanos { }
        if (memory.focus(key)) return true
    }
    return false
}

/** Where the library in numbers leads: a wall filtered by a value it counts, or a director's page. */
private class NumbersDoors(val onOpenWall: (Library, LibraryFilter) -> Unit, val onOpenPerson: (String) -> Unit)

/** The note [pane] shows when one of its secrets could not be read back and must be re-entered; else null. */
internal fun lostSecretNote(pane: Pane, lost: Set<SecretSetting>): Int? = when {
    pane == Pane.Library && SecretSetting.TMDB_KEY in lost -> R.string.settings_lost_tmdb_key
    pane == Pane.Playback && SecretSetting.PATH_MAPPINGS in lost -> R.string.settings_lost_mappings
    else -> null
}

private const val LOST_SECRET = "lost-secret"

private fun paneKey(pane: Pane) = FocusKey.pane(pane.name)

/** A category's current value, under its name. */
@Composable
private fun summary(pane: Pane, s: AppSettings, titles: Int, stats: StorageStats?): String? = when (pane) {
    Pane.Library -> when (val source = s.source) {
        is SourceConfig.Jellyfin -> source.serverName ?: stringResource(R.string.source_jellyfin)
        else -> pluralStringResource(R.plurals.title_count, titles, titles)
    }
    Pane.Playback -> stringResource(if (s.forceHttp) R.string.http_stream else R.string.network_path)
    Pane.Display -> displaySummary(s)
    // Counting happens only when its pane shows, so its entry has no summary.
    Pane.Numbers -> null
    Pane.Storage -> stats?.let { formatBytes(it.total) }
    Pane.About -> stringResource(R.string.nav_version, BuildConfig.VERSION_NAME)
}

/** The rows of [pane]. Focus returning from the navigation goes back to the row it left. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PaneContent(
    pane: Pane,
    s: AppSettings,
    storage: StorageStats?,
    onFolderForm: (folderId: Long?, focus: FolderField) -> Unit,
    onUseFolders: () -> Unit,
    onSignIn: () -> Unit,
    onOpenCatalog: () -> Unit,
    rows: PaneRows,
    list: LazyListState,
    entry: FocusRequester,
    onFocusIn: (Boolean) -> Unit,
    numbers: NumbersDoors,
) {
    val vm = settingsViewModel()
    val counted = if (pane == Pane.Numbers) numbersViewModel() else null
    LazyColumn(
        state = list,
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { onFocusIn(it.hasFocus) }
            .keepVerticalFocus()
            // Left goes back to the pane's own entry, not to the nearest one, which would open another pane.
            .focusProperties { exit = { direction -> if (direction == FocusDirection.Left) entry else FocusRequester.Default } }
            .focusGroup(),
        contentPadding = PaddingValues(bottom = HlFocus.edge, end = HlSpace.m),
        verticalArrangement = Arrangement.spacedBy(HlSpace.l),
    ) {
        // A lost secret shows at the pane's head; a failed change shows on its own row, where focus is.
        lostSecretNote(pane, s.lostSecrets)?.let { note -> item(key = LOST_SECRET) { AttentionNote(stringResource(note)) } }
        when (pane) {
            Pane.Library -> libraryPane(s, onFolderForm, onUseFolders, onSignIn, rows)
            Pane.Numbers -> counted?.let { numbersPane(it.state, rows, numbers.onOpenWall, numbers.onOpenPerson, it::retry) }
            Pane.Playback -> playbackPane(s)
            Pane.Display -> displayPane(s)
            Pane.Storage -> storagePane(storage)
            Pane.About -> aboutPane(onOpenCatalog, rows)
        }
    }
}
