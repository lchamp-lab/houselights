package app.houselights.ui.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.RestoreFocus
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.HlNavItem
import app.houselights.ui.design.components.HlTwoPane
import app.houselights.ui.design.components.PosterField
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.components.bleed
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.keepVerticalFocus
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.onboarding.FolderField
import app.houselights.ui.onboarding.LoginFill
import app.houselights.ui.util.PartFailed
import app.houselights.ui.util.UiError
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.roomPosters
import kotlinx.coroutines.delay

internal enum class Pane(val label: Int, val icon: ImageVector) {
    Library(R.string.section_source, HlIcons.Folder),
    Numbers(R.string.section_numbers, HlIcons.Numbers),
    Playback(R.string.section_playback, HlIcons.Playback),
    Trakt(R.string.section_trakt, HlIcons.History),
    Display(R.string.section_display, HlIcons.Display),
    Storage(R.string.section_storage, HlIcons.Storage),
    About(R.string.section_about, HlIcons.Info),
}

/**
 * Settings in two panes: its places, a word each, and the focused one's rows. [onUseFolders] makes the folders the
 * source, keeping the server left or signing out of it, and reports a failure on the source's row; [onSignIn] opens
 * the sign-in, filled in as it says; [onSourceResume] follows the kept server made the source again. The library in
 * numbers opens a filtered wall ([onOpenWall]) or a director's page ([onOpenPerson]). A folder just added or edited
 * ([addedFolder]) takes focus on its showcase, one whose edit was left ([folderSheet]) its sheet too, then
 * [onAddedFolderShow]. [onFolderForm] adds a folder or edits one, a folder of the player's disks through the flow.
 */
@Composable
internal fun SettingsScreen(
    onFolderForm: (folderId: Long?, focus: FolderField, onDisk: Boolean) -> Unit,
    onUseFolders: (keepServer: Boolean, onError: (UiError) -> Unit) -> Unit,
    onSignIn: (LoginFill) -> Unit,
    onSourceResume: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenCatalog: () -> Unit = {},
    onOpenWall: (Library, LibraryFilter) -> Unit = { _, _ -> },
    onOpenPerson: (personId: String) -> Unit = {},
    onOpenToIdentify: () -> Unit = {},
    addedFolder: Long? = null,
    folderSheet: Long? = null,
    onAddedFolderShow: () -> Unit = {},
    vm: SettingsViewModel = rememberSettingsViewModel(),
) {
    val app = appContainer()
    val sources = SourceDoors(
        useFolders = { keepServer ->
            vm.clearActionError()
            onUseFolders(keepServer) { vm.actionFailed(SettingAction.SOURCE, it) }
        },
        signIn = onSignIn,
        resumed = onSourceResume,
        toIdentify = onOpenToIdentify,
    )
    val s by vm.settings.collectAsStateWithLifecycle()
    val asksTracks by vm.asksTracks.collectAsStateWithLifecycle()
    val panes = panesFor(s.source, vm.capabilities.pathMapping, vm.counts, asksTracks, s.keepsTrakt)
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
    // About's « Mises à jour », in a build that updates itself alone.
    val updates = aboutUpdates(app.updates.state, app.updates)

    // The room is the library's posters, as a wall's field.
    PosterField(roomPosters(), modifier) {
        HlTwoPane(
            state = list,
            side = {
                ScreenTitle(stringResource(R.string.settings_title))
                Spacer(Modifier.height(HlSpace.l))
                SettingsPlaces(panes, pane, memory, ready = s.loaded) { pane = it }
            },
        ) {
            val rows = remember(pane) { PaneRows(memory, pane.name) }
            CompositionLocalProvider(LocalSettingsViewModel provides vm) {
                PaneContent(
                    pane, s, storage, onFolderForm, sources, onOpenCatalog, rows, list,
                    entry = memory.requester(paneKey(pane)),
                    onFocusIn = { inPane = it },
                    numbers = NumbersDoors(onOpenWall, onOpenPerson),
                    updates = updates,
                    added = folderSheet?.let { NewFolder(it, sheet = true, shown = onAddedFolderShow) }
                        ?: addedFolder?.let { NewFolder(it, shown = onAddedFolderShow) },
                )
            }
        }
    }
}

/**
 * The settings' places, a word each, opening its pane ([onOpen]) once focus has settled on it ([HlMotion.SETTLE]) or
 * on OK, so stepping down the list builds no pane it only crosses. Once the settings are [ready], a place leaving the
 * list while [open], Trakt disconnected under a server, gives way to the place after it, else the last, which takes
 * focus from it.
 */
@Composable
internal fun SettingsPlaces(panes: List<Pane>, open: Pane, memory: FocusMemory, ready: Boolean, onOpen: (Pane) -> Unit) {
    val opening by rememberUpdatedState(onOpen)
    LaunchedEffect(open, panes, ready) {
        if (ready && open !in panes) {
            val next = Pane.entries.drop(open.ordinal + 1).firstOrNull { it in panes } ?: panes.last()
            opening(next)
            memory.focus(paneKey(next))
        }
    }
    val shown by rememberUpdatedState(open)
    // The place focus is on; null once focus leaves the list, as into the pane shown.
    var focused by remember { mutableStateOf<Pane?>(null) }
    LaunchedEffect(focused) {
        val place = focused ?: return@LaunchedEffect
        delay(HlMotion.SETTLE)
        if (place != shown) opening(place)
    }
    Column(Modifier.keepVerticalFocus().onFocusChanged { if (!it.hasFocus) focused = null }) {
        panes.forEach { p ->
            HlNavItem(
                icon = p.icon,
                title = stringResource(p.label),
                selected = p == open,
                // The window's own first focus, before the screen is restored, leaves the pane that was open.
                onFocus = { if (!memory.arriving) focused = p },
                modifier = Modifier.focusKey(paneKey(p), memory),
                onClick = { if (p != open) onOpen(p) },
            )
        }
    }
}

/**
 * Scrolls [list] item by item until row [key] can be focused (a pane holds a dozen items at most); a row gone, back
 * where it was, so the plate it was on can take focus.
 */
internal suspend fun revealRow(list: LazyListState, key: String, memory: FocusMemory): Boolean {
    val (index, offset) = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
    for (i in 0 until list.layoutInfo.totalItemsCount) {
        list.scrollToItem(i)
        withFrameNanos { }
        if (memory.focus(key)) return true
    }
    list.scrollToItem(index, offset)
    withFrameNanos { }
    return false
}

/** Where the library in numbers leads: a wall filtered by a value it counts, or a director's page. */
private class NumbersDoors(val onOpenWall: (Library, LibraryFilter) -> Unit, val onOpenPerson: (String) -> Unit)

/**
 * Where the source's rows lead: the folders, keeping the server left or not ([useFolders]), a sign-in filled in as
 * asked ([signIn]), what follows the kept server made the source again ([resumed]), and the titles to identify
 * ([toIdentify]).
 */
internal class SourceDoors(
    val useFolders: (keepServer: Boolean) -> Unit,
    val signIn: (LoginFill) -> Unit,
    val resumed: () -> Unit,
    val toIdentify: () -> Unit = {},
)

/**
 * The note [pane] shows when one of its secrets could not be read back and must be re-entered; else null. Trakt's waits
 * for the [folders]: paused elsewhere, its pane offers no way to connect again.
 */
internal fun lostSecretNote(pane: Pane, lost: Set<SecretSetting>, folders: Boolean): Int? = when {
    pane == Pane.Library && SecretSetting.TMDB_KEY in lost -> R.string.settings_lost_tmdb_key
    pane == Pane.Playback && SecretSetting.PATH_MAPPINGS in lost -> R.string.settings_lost_mappings
    pane == Pane.Trakt && folders && SecretSetting.TRAKT in lost -> R.string.settings_lost_trakt
    else -> null
}

/**
 * Whether [pane] shows a secret the Keystore did not open for now ([unread]): the library's TMDB key or kept server,
 * Trakt's session only with the [folders].
 */
internal fun unreadSecretIn(pane: Pane, unread: Set<SecretSetting>, folders: Boolean): Boolean = when (pane) {
    Pane.Library -> SecretSetting.TMDB_KEY in unread || SecretSetting.KEPT_SERVER in unread
    Pane.Playback -> SecretSetting.PATH_MAPPINGS in unread
    Pane.Trakt -> folders && SecretSetting.TRAKT in unread
    else -> false
}

/** The pane's secret is kept but not opened for now: said so, Réessayer reading it again. */
@Composable
private fun SecretUnread() {
    val vm = settingsViewModel()
    val reading by vm.readingSecrets.collectAsStateWithLifecycle()
    PartFailed(UiError.SECRETS_UNREAD, reading, vm::readSecretsAgain)
}

/** [pane]'s secret lost or not opened for now, said at its head. */
private fun LazyListScope.secretNote(pane: Pane, s: AppSettings, folders: Boolean) {
    if (unreadSecretIn(pane, s.unreadSecrets, folders)) {
        item(key = LOST_SECRET) { SecretUnread() }
    } else {
        lostSecretNote(pane, s.lostSecrets, folders)?.let { note -> item(key = LOST_SECRET) { AttentionNote(stringResource(note)) } }
    }
}

private const val LOST_SECRET = "lost-secret"

/** Whether [pane] shows a note at its head ([secretNote]): a secret lost, or not opened for now. */
private fun notedAtHead(pane: Pane, s: AppSettings, folders: Boolean): Boolean =
    unreadSecretIn(pane, s.unreadSecrets, folders) || lostSecretNote(pane, s.lostSecrets, folders) != null

/**
 * Brings into sight a note coming at the head of a pane ([noted]) while its [list] is at its top: the list keeps its
 * place by the key of its first row, so that row would stay at the top and the note come above it, out of sight, and,
 * taking no focus, never shown. Read in composition, before the list measures, and asked by index there; whether the
 * list is at its top changes only as it leaves it or comes back.
 */
@Composable
internal fun NoteInSight(list: LazyListState, noted: Boolean) {
    // The last value seen, kept without a state: writing it never composes again.
    val was = remember(list) { booleanArrayOf(noted) }
    if (noted && !was[0] && !list.canScrollBackward) list.requestScrollToItem(0)
    was[0] = noted
}

private fun paneKey(pane: Pane) = FocusKey.pane(pane.name)

/**
 * The panes listed with [source]: playback for its path mappings or the player's [tracks], counts where it counts,
 * Trakt with the folders, and under another source while it keeps an account ([trakt]), paused.
 */
internal fun panesFor(source: SourceConfig?, pathMapping: Boolean, counts: Boolean, tracks: Boolean, trakt: Boolean): List<Pane> =
    Pane.entries.filter { p ->
        when (p) {
            Pane.Playback -> source != null && (pathMapping || tracks)
            Pane.Numbers -> source != null && counts
            // Jellyfin keeps its own watch history, and has its own Trakt plugin: there, Trakt pauses.
            Pane.Trakt -> source == SourceConfig.Local || (source != null && trakt)
            else -> true
        }
    }

/** A Trakt account is kept: connected, or saved but not opened at this launch. */
internal val AppSettings.keepsTrakt: Boolean
    get() = trakt?.ended == false || SecretSetting.TRAKT in lostSecrets || SecretSetting.TRAKT in unreadSecrets

/** The rows of [pane]. Focus returning from the navigation goes back to the row it left. */
@Composable
private fun PaneContent(
    pane: Pane,
    s: AppSettings,
    storage: StorageStats?,
    onFolderForm: (folderId: Long?, focus: FolderField, onDisk: Boolean) -> Unit,
    sources: SourceDoors,
    onOpenCatalog: () -> Unit,
    rows: PaneRows,
    list: LazyListState,
    entry: FocusRequester,
    onFocusIn: (Boolean) -> Unit,
    numbers: NumbersDoors,
    updates: UpdateUi?,
    added: NewFolder? = null,
) {
    val vm = settingsViewModel()
    val asksTracks by vm.asksTracks.collectAsStateWithLifecycle()
    val counted = if (pane == Pane.Numbers) numbersViewModel() else null
    // The library is counted the first time its pane shows, and its numbers kept while Settings is open.
    LaunchedEffect(counted) { counted?.count() }
    // The storage is measured each time its pane shows.
    LaunchedEffect(pane) { if (pane == Pane.Storage) vm.refreshStorage() }
    val trakt = if (pane == Pane.Trakt) traktViewModel() else null
    val folders = s.source == SourceConfig.Local
    NoteInSight(list, noted = notedAtHead(pane, s, folders))
    LazyColumn(
        state = list,
        modifier = Modifier
            // Runs on to the screen's end, so a row of showcases does; its blocks keep to the pane.
            .bleed(end = HlSpace.safeH)
            .fillMaxSize()
            .onFocusChanged { onFocusIn(it.hasFocus) }
            .keepVerticalFocus()
            // Left goes back to the pane's own entry, not to the nearest one, which would open another pane.
            .focusProperties { onExit = { if (requestedFocusDirection == FocusDirection.Left) entry.requestFocus() } }
            .focusGroup(),
        contentPadding = PaddingValues(bottom = HlFocus.edge, end = HlSpace.safeH),
        verticalArrangement = Arrangement.spacedBy(HlSpace.l),
    ) {
        // A lost or unread secret shows at the pane's head; a failed change shows on its own row, where focus is.
        secretNote(pane, s, folders)
        when (pane) {
            Pane.Library -> libraryPane(s, onFolderForm, sources, rows, added)
            Pane.Numbers -> counted?.let { numbersPane(it.state, rows, numbers.onOpenWall, numbers.onOpenPerson, it::retry) }
            Pane.Playback -> playbackPane(s, server = vm.capabilities.pathMapping, tracks = asksTracks)
            Pane.Trakt -> trakt?.let { traktPane(it, s.trakt, folders, unread = SecretSetting.TRAKT in s.unreadSecrets) }
            Pane.Display -> displayPane(s, rows)
            Pane.Storage -> storagePane(storage)
            Pane.About -> aboutPane(onOpenCatalog, rows, updates, jellyfin = s.source is SourceConfig.Jellyfin)
        }
    }
}
