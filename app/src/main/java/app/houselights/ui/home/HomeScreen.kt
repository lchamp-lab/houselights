package app.houselights.ui.home

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.components.HlIconButton
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.HlEmptyState
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.ListScroll
import app.houselights.ui.design.BackToStart
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.RestoreFocus
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.design.rememberRowState
import app.houselights.R
import app.houselights.core.HomeSection
import app.houselights.core.Library
import app.houselights.core.MediaItem
import app.houselights.core.SourceConfig
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanState
import app.houselights.providers.local.SourceEntity
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.OnArtwork
import app.houselights.ui.design.awaitMotion
import app.houselights.ui.design.textRoom
import app.houselights.ui.design.LocalHouseLights
import app.houselights.ui.design.houseLit
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.components.Backdrop
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.CardSkeleton
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.HeroInfo
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.LandscapeCard
import app.houselights.ui.design.components.MediaRow
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.ScanProgress
import app.houselights.ui.design.components.rememberAmbientColor
import app.houselights.ui.util.ReloadOnLibraryChange
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.libraryName
import app.houselights.ui.util.timeLeft
import app.houselights.ui.util.scanErrorText
import app.houselights.ui.util.sectionTitle
import app.houselights.ui.util.ErrorAction
import app.houselights.ui.util.LocalErrorFix
import app.houselights.ui.util.UiError
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    onOpenItem: (MediaItem) -> Unit,
    onOpenLibrary: (Library) -> Unit,
    onOpenSettings: () -> Unit,
    onAddFolder: () -> Unit,
) {
    val app = appContainer()
    val vm = viewModel { HomeViewModel(app) }
    ReloadOnLibraryChange { vm.load() }
    val state by vm.state.collectAsStateWithLifecycle()
    val scan by app.library.scan.collectAsStateWithLifecycle()
    val folders by app.library.folders.collectAsStateWithLifecycle(null)
    val settings by app.settings.state.collectAsStateWithLifecycle()

    // The title last selected stays on the screen while focus is on the tabs, and is found again
    // coming back; before any, the first title of the first row, where focus lands.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = remember(selectedId, state.sections) { selectedTitle(state.sections, selectedId) }
    // The backdrop only changes once focus settles: no image load on every scroll step.
    var shown by remember { mutableStateOf<MediaItem?>(null) }
    LaunchedEffect(selected) {
        if (shown != null) delay(HlMotion.SETTLE)
        shown = selected
    }
    val ambient = rememberAmbientColor(shown?.backdrop)

    val memory = rememberFocusMemory()
    val rows = rememberLazyListState()
    // Each arrival puts focus back where it was: a card (by its row and its id), a tab, the
    // settings button; on a first visit, the first card.
    val first = state.sections.firstOrNull()
    RestoreFocus(
        memory,
        fallback = first?.items?.firstOrNull()?.let { memory.requester(cardKey(first, it)) },
        ready = state.sections.isNotEmpty(),
    ) { key ->
        val index = sectionIndex(state.sections, key)
        if (index != null) rows.scrollToItem(index)
        index != null
    }
    // Below the first row, Back goes up to the tabs; from the first row or the tabs, it leaves.
    var focusedRow by remember { mutableStateOf<Int?>(null) }
    BackToStart(away = backGoesUp(focusedRow)) {
        memory.focus(TRACK)
        rows.animateScrollToItem(0)
    }

    CompositionLocalProvider(LocalAmbient provides { ambient.value }) {
        Box(Modifier.fillMaxSize()) {
            Backdrop(shown?.backdrop, BackdropStyle.Hero)
            Column(Modifier.fillMaxSize().houseLit(LocalHouseLights.current)) {
                TopBar(
                    libraries = state.libraries,
                    memory = memory,
                    // Back from a library, focus enters the track on that library's tab.
                    returnTo = state.libraries.indexOfFirst { memory.last == trackTab(it) }.takeIf { it >= 0 }?.plus(1),
                    onLibrary = { library ->
                        // Coming back from the library finds focus on its tab.
                        memory.record(trackTab(library))
                        onOpenLibrary(library)
                    },
                    onSettings = onOpenSettings,
                )
                // A folder read for the first time: the counter stays above the rows, which fill in
                // under it as titles are found.
                val firstScan = firstScan(scan, state.sections.isEmpty(), folders)
                if (state.sections.isEmpty() && !state.loading) {
                    // Nothing to show yet: what the screen says starts where the title under focus would.
                    val message = Modifier.padding(start = HlSpace.safeH, top = HlSpace.l)
                    if (firstScan) {
                        FirstScan(scan, message)
                    } else {
                        NoRows(
                            state,
                            scan,
                            folders,
                            local = settings.source == SourceConfig.Local,
                            onReload = vm::load,
                            onSettings = onOpenSettings,
                            onAddFolder = onAddFolder,
                            modifier = message,
                        )
                    }
                } else {
                    // Its height holds the text at the viewer's size, and stays the same whatever has focus.
                    HeroInfo(shown, Modifier.padding(start = HlSpace.safeH, top = HlSpace.l).height(textRoom(HlLayout.hero)))
                    Spacer(Modifier.height(HlSpace.l))
                    if (firstScan) FirstScan(scan, Modifier.padding(start = HlSpace.safeH, bottom = HlSpace.m))
                    when {
                        state.sections.isEmpty() -> SkeletonRows()
                        else -> Sections(
                            sections = state.sections,
                            memory = memory,
                            rows = rows,
                            onOpen = onOpenItem,
                            onFocus = { section, item ->
                                focusedRow = section
                                selectedId = item.id
                            },
                            onLeave = { focusedRow = null },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    libraries: List<Library>,
    memory: FocusMemory,
    returnTo: Int?,
    onLibrary: (Library) -> Unit,
    onSettings: () -> Unit,
) {
    // Where you are among the libraries, in one track; settings apart, as an action. Both sit on
    // the image, on their plates. A long track scrolls: the button always keeps its place.
    OnArtwork { Row(
        Modifier.fillMaxWidth().padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.safeV),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        // The white slides to the library chosen, then it opens (at once when animations are off);
        // back home, "Accueil" is lit again.
        var current by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        HlSegmented(
            options = listOf(stringResource(R.string.nav_home)) + libraries.map { it.title ?: libraryName(it.kind) },
            selected = current,
            onSelect = { i ->
                if (i > 0 && current == 0) {
                    current = i
                    scope.launch {
                        awaitMotion(HlMotion.STANDARD)
                        onLibrary(libraries[i - 1])
                    }
                }
            },
            // Focus coming back to the track lands where the track's own rule puts it.
            modifier = Modifier.weight(1f, fill = false).padding(end = HlSpace.l).focusRequester(memory.requester(TRACK)),
            scrollable = true,
            enterAt = returnTo ?: current,
        )
        HlIconButton(
            HlIcons.Settings,
            stringResource(R.string.nav_settings),
            onClick = onSettings,
            modifier = Modifier.focusKey(FocusKey.action("settings"), memory),
        )
    } }
}

@Composable
private fun Sections(
    sections: List<HomeSection>,
    memory: FocusMemory,
    rows: LazyListState,
    onOpen: (MediaItem) -> Unit,
    onFocus: (section: Int, item: MediaItem) -> Unit,
    /** Focus left the rows, for the tabs above: the title selected stays. */
    onLeave: () -> Unit,
) {
    // Up from the first row goes to the tabs, whichever card it starts from: the track is one
    // group, and the settings button, nearer some cards, is not where Up means to go.
    val toTabs = Modifier.focusProperties { up = memory.requester(TRACK) }
    // The rows start under the hero: a row that has to move comes to the top of the list, where
    // the first rests, and the one above leaves whole.
    ListScroll(top = HlSpace.none, toTop = true) { LazyColumn(
        state = rows,
        modifier = Modifier.fillMaxSize().onFocusChanged { if (!it.hasFocus) onLeave() },
        contentPadding = PaddingValues(bottom = HlFocus.edge),
        verticalArrangement = Arrangement.spacedBy(HlSpace.xs),
    ) {
        sections.forEachIndexed { sectionIndex, section ->
            item(key = section.key) {
                MediaRow(
                    header = sectionTitle(section),
                    // A card gone since focus left it: focus comes back to its row.
                    modifier = Modifier.focusRequester(memory.requester(FocusKey.section(section.key))),
                    spacing = if (section.landscape) HlSpace.l else HlSpace.m,
                    state = rememberRowState(section.items.map { it.id }),
                ) {
                    items(section.items, key = { it.id }) { item ->
                        val mod = Modifier.focusKey(cardKey(section, item), memory).then(if (sectionIndex == 0) toTabs else Modifier)
                        val focus = { onFocus(sectionIndex, item) }
                        if (section.landscape) {
                            // Named under the picture, as an episode is: a still does not say which film it is.
                            LandscapeCard(
                                item,
                                onClick = { onOpen(item) },
                                modifier = mod,
                                title = item.seriesTitle ?: item.title,
                                facts = listOf(item.episodeLabel, timeLeft(item)),
                                onFocused = focus,
                            )
                        } else {
                            PosterCard(item, onClick = { onOpen(item) }, modifier = mod, onFocused = focus)
                        }
                    }
                }
            }
        }
    } }
}

/** The title of [sections] named [id]; when there is none (or it has left them), the first title. */
internal fun selectedTitle(sections: List<HomeSection>, id: String?): MediaItem? =
    sections.firstNotNullOfOrNull { section -> section.items.find { it.id == id } } ?: sections.firstOrNull()?.items?.firstOrNull()

/** A row of home, whatever its position: its kind and its library. */
internal val HomeSection.key: String get() = "$type-$libraryId"

/** A card of home: the same title may be in two rows. */
internal fun cardKey(section: HomeSection, item: MediaItem) = FocusKey.within(FocusKey.section(section.key), FocusKey.item(item.id))

/** The row of [sections] holding what [key] names; null for the tabs, the settings button, a row gone. */
internal fun sectionIndex(sections: List<HomeSection>, key: String): Int? {
    val row = FocusKey.groups(key).lastOrNull() ?: return null
    return sections.indexOfFirst { FocusKey.section(it.key) == row }.takeIf { it >= 0 }
}

/** Back goes up to the tabs from below the first row; from the first row or the tabs, it leaves. */
internal fun backGoesUp(focusedRow: Int?): Boolean = focusedRow != null && focusedRow > 0

/** The library tabs, as one group: what they hold is the track's to focus. */
private const val TRACK = "track"

/** The key a library's tab is remembered by. */
private fun trackTab(library: Library) = FocusKey.within(TRACK, FocusKey.tab(library.id))

/** Same footprint as the real rows, so nothing jumps when data arrives. */
@Composable
private fun SkeletonRows() {
    Column(Modifier.padding(start = HlSpace.safeH, top = HlSpace.xl), verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                repeat(8) { CardSkeleton(HlSize.poster, 2f / 3f) }
            }
        }
    }
}

/** A folder read for the first time, or a scan going on while home has nothing to show. */
internal fun firstScan(scan: ScanState, empty: Boolean, folders: List<SourceEntity>?): Boolean =
    scan.running && (empty || folders.orEmpty().any { it.id == scan.sourceId && it.lastScanAt <= 0 })

/** The first folder still there whose last scan failed, and why. */
internal fun failedFolder(errors: Map<Long, ScanError>, folders: List<SourceEntity>): Pair<SourceEntity, ScanError>? =
    folders.firstNotNullOfOrNull { folder -> errors[folder.id]?.let { folder to it } }

/** The first scan, counted: what is being read, how far it has gone, the file being read. */
@Composable
private fun FirstScan(scan: ScanState, modifier: Modifier) {
    ScanProgress(
        scan.processed,
        scan.total,
        modifier,
        label = stringResource(R.string.home_scanning),
        current = scan.current,
        lineWidth = HlLayout.readableNarrow,
    )
}

/** No rows and nothing loading: a failure, a scan that failed, or a library still empty. */
@Composable
private fun NoRows(
    state: HomeState,
    scan: ScanState,
    folders: List<SourceEntity>?,
    local: Boolean,
    onReload: () -> Unit,
    onSettings: () -> Unit,
    onAddFolder: () -> Unit,
    modifier: Modifier,
) {
    val failed = failedFolder(scan.errors, folders.orEmpty())
    when {
        state.error != null -> ErrorIntertitle(state.error, onReload, modifier)
        // Folders are still being read: nothing to say about them yet.
        local && folders == null -> Unit
        // Not "add a folder" when there is one: its scan failed, and it says why.
        local && failed != null -> ScanFailed(failed, onSettings, modifier)
        local -> NoFilms(folders.orEmpty(), onAddFolder, onSettings, modifier)
        else -> HlEmptyState(stringResource(R.string.empty_title), modifier, message = stringResource(R.string.empty_server))
    }
}

/**
 * Folders without a film yet, or none at all: a verb, adding the folder that holds them, and
 * beside it the folders already there.
 */
@Composable
private fun NoFilms(folders: List<SourceEntity>, onAddFolder: () -> Unit, onSettings: () -> Unit, modifier: Modifier) {
    HlEmptyState(
        stringResource(R.string.home_empty_title),
        modifier,
        message = stringResource(R.string.home_empty_local),
        action = stringResource(R.string.add_folder),
        actionIcon = HlIcons.Add,
        onAction = onAddFolder,
        secondaryAction = stringResource(R.string.home_see_folders).takeIf { folders.isNotEmpty() },
        onSecondaryAction = onSettings,
    )
}

/** Nothing to show because the folders could not be read: why, a way to scan again, and the folders. */
@Composable
private fun ScanFailed(failed: Pair<SourceEntity, ScanError>, onSettings: () -> Unit, modifier: Modifier) {
    val library = appContainer().library
    val (folder, error) = failed
    HlEmptyState(
        stringResource(R.string.home_scan_failed),
        modifier,
        message = scanErrorText(error, folder.host),
        attention = true,
        action = stringResource(R.string.retry),
        actionIcon = HlIcons.Refresh,
        onAction = { library.scan() },
        secondaryAction = stringResource(R.string.home_see_folders),
        onSecondaryAction = onSettings,
    )
}

/** An [ErrorIntertitle] where a screen's content would start: on its margin, under its header. */
@Composable
fun ErrorMessage(error: UiError, onRetry: () -> Unit) {
    ErrorIntertitle(error, onRetry, Modifier.padding(start = HlSpace.safeH, top = HlSpace.l))
}

/**
 * Something could not load: what happened, what to check, and what fixes it, as an intertitle that
 * calls for attention, in place of what would have been shown. Trying again is the screen's own
 * [onRetry]; the other actions lead where the problem is fixed ([LocalErrorFix]). An error no
 * action fixes leaves the way out to Back.
 */
@Composable
fun ErrorIntertitle(error: UiError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val action = error.action
    val fix = LocalErrorFix.current
    HlEmptyState(
        stringResource(error.title),
        modifier,
        message = stringResource(error.message),
        attention = true,
        action = action?.let { stringResource(it.label) },
        actionIcon = HlIcons.Refresh.takeIf { action == ErrorAction.RETRY },
        onAction = { if (action == ErrorAction.RETRY) onRetry() else action?.let(fix) },
        backHint = action == null,
    )
}
