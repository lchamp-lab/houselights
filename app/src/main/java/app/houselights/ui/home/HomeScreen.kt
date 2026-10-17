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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.components.HlIconButton
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.HlEmptyState
import app.houselights.ui.design.components.HlErrorState
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.ListScroll
import app.houselights.ui.design.BackToStart
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.RestoreFocus
import app.houselights.ui.design.focusGroupKey
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.design.rememberRowState
import app.houselights.R
import app.houselights.core.HomeSection
import app.houselights.core.Library
import app.houselights.core.MediaItem
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
import app.houselights.ui.design.components.AttentionNote
import app.houselights.ui.design.components.AmbientBackdrop
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.CardSkeleton
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.HeroInfo
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.LandscapeCard
import app.houselights.ui.design.components.MediaRow
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.ScanProgress
import app.houselights.ui.util.ReloadOnLibraryChange
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.libraryName
import app.houselights.ui.util.rememberSettled
import app.houselights.ui.util.skeletonDelayPassed
import app.houselights.ui.util.timeLeft
import app.houselights.ui.util.scanErrorText
import app.houselights.ui.util.sectionTitle
import app.houselights.ui.util.ErrorAction
import app.houselights.ui.util.LocalErrorFix
import app.houselights.ui.util.UiError
import app.houselights.ui.util.toUiError
import kotlinx.coroutines.flow.StateFlow

@Composable
fun HomeScreen(
    onOpenItem: (MediaItem) -> Unit,
    onOpenLibrary: (Library) -> Unit,
    onOpenSettings: () -> Unit,
    onAddFolder: () -> Unit,
) {
    val app = appContainer()
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(app))
    ReloadOnLibraryChange { vm.load() }
    val state by vm.state.collectAsStateWithLifecycle()
    HomeBands(state, vm.scan, onOpenItem, onOpenLibrary, onOpenSettings, onAddFolder, onReload = vm::load, onRescan = vm::rescan)
}

/** Home as [state] lays it out, its bar, its hero and its rows, and where focus goes among them. */
@Composable
internal fun HomeBands(
    state: HomeState,
    scan: StateFlow<ScanState>,
    onOpenItem: (MediaItem) -> Unit,
    onOpenLibrary: (Library) -> Unit,
    onOpenSettings: () -> Unit,
    onAddFolder: () -> Unit,
    onReload: () -> Unit,
    onRescan: () -> Unit,
) {
    val content = state.content
    val sections = (content as? HomeContent.Rows)?.sections.orEmpty()

    // The title last selected stays on the screen while focus is on the tabs, and is found again
    // coming back; before any, the first title of the first row, where focus lands.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // The backdrop only changes once focus settles: no image load on every scroll step. The
    // selection is read there alone: a step along a row recomposes nothing here.
    val shown by rememberSettled { selectedTitle(sections, selectedId) }

    val memory = rememberFocusMemory()
    val rows = rememberLazyListState()
    // Each arrival puts focus back where it was: a card (by its row and its id), a tab, the
    // settings button; on a first visit, the first card.
    val first = sections.firstOrNull()
    RestoreFocus(
        memory,
        fallback = first?.items?.firstOrNull()?.let { memory.requester(cardKey(first, it)) },
        ready = sections.isNotEmpty(),
    ) { key ->
        val index = sectionIndex(sections, key)
        if (index != null) rows.scrollToItem(index)
        index != null
    }
    // Below the first row, Back goes up to the tabs; from the first row or the tabs, it leaves.
    var focusedRow by remember { mutableStateOf<Int?>(null) }
    BackToStart(away = backGoesUp(focusedRow)) {
        memory.focus(TRACK)
        rows.animateScrollToItem(0)
    }

    AmbientBackdrop(shown?.backdrop, BackdropStyle.Hero) {
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
                // Into the first row, once laid out: its group is then there to take focus.
                intoRows = { first?.takeIf { rows.firstVisibleItemIndex == 0 }?.let { memory.requester(FocusKey.section(it.key)) } },
            )
            when (content) {
                is HomeContent.Browsing -> {
                    // Its height holds the text at the viewer's size, and stays the same whatever has focus.
                    HeroInfo(shown, Modifier.padding(start = HlSpace.safeH, top = HlSpace.l).height(textRoom(HlLayout.hero)))
                    Spacer(Modifier.height(HlSpace.l))
                    // A folder read for the first time: the counter stays above the rows, which fill in
                    // under it as titles are found.
                    if (content.firstScan) {
                        FirstScan(scan, Modifier.padding(start = HlSpace.safeH, bottom = HlSpace.m), aboveRows = true)
                    }
                    when (content) {
                        is HomeContent.Loading -> SkeletonRows()
                        is HomeContent.Rows -> Sections(
                            sections = content.sections,
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
                // Nothing to show yet: what the screen says starts where the title under focus would.
                is HomeContent.Message -> NoRows(
                    content,
                    scan,
                    onReload = onReload,
                    onRescan = onRescan,
                    onSettings = onOpenSettings,
                    onAddFolder = onAddFolder,
                    modifier = Modifier.padding(start = HlSpace.safeH, top = HlSpace.l),
                )
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
    /** The first row, when there is one to go down to. */
    intoRows: () -> FocusRequester?,
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
            modifier = Modifier.weight(1f, fill = false).padding(end = HlSpace.l).focusGroupKey(TRACK, memory),
            scrollable = true,
            enterAt = returnTo ?: current,
        )
        HlIconButton(
            HlIcons.Settings,
            stringResource(R.string.nav_settings),
            onClick = onSettings,
            // Down goes into the first row as from the tabs, on the card it last had, else its first:
            // not on the card that lies under the button, at the far end of the row.
            modifier = Modifier
                .focusKey(FocusKey.action("settings"), memory)
                .focusProperties { down = intoRows() ?: FocusRequester.Default },
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
                    modifier = Modifier.focusGroupKey(FocusKey.section(section.key), memory),
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

/**
 * Same footprint as the real rows, so nothing jumps when data arrives; once loading takes long enough
 * to be seen. Nothing lies below them, so none of the room is kept meanwhile.
 */
@Composable
private fun SkeletonRows() {
    if (!skeletonDelayPassed()) return
    Column(Modifier.padding(start = HlSpace.safeH, top = HlSpace.xl), verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                repeat(8) { CardSkeleton(HlSize.poster, 2f / 3f) }
            }
        }
    }
}

/**
 * The first scan, counted: what is being read, how far it has gone, the file being read. When TMDB
 * stopped answering it, titles come from the files alone: said under the counter, with the key to
 * change when TMDB refused it ([scanNoteAction]). [aboveRows]: nothing the scan comes to moves the
 * rows under it, as the file's line and the note keep their room from the start. The [progress] is
 * read here alone: a file read recomposes the counter, never home.
 */
@Composable
private fun FirstScan(progress: StateFlow<ScanState>, modifier: Modifier, aboveRows: Boolean = false) {
    val scan by progress.collectAsStateWithLifecycle()
    val tmdbIssue = scan.tmdbIssue?.toUiError()
    Column(modifier) {
        ScanProgress(
            scan.processed,
            scan.total,
            label = stringResource(R.string.home_scanning),
            current = if (aboveRows) scan.current.orEmpty() else scan.current,
            lineWidth = HlLayout.readableNarrow,
        )
        TmdbIssue(tmdbIssue, keepRoom = aboveRows)
        scanNoteAction(tmdbIssue, aboveRows)?.let { action ->
            val fix = LocalErrorFix.current
            Spacer(Modifier.height(HlSpace.m))
            HlButton(stringResource(action.label), onClick = { fix(action) })
        }
    }
}

/**
 * Why TMDB stopped answering the scan, if it did. [keepRoom]: laid out before it says anything, both
 * notes unseen, so that one coming halfway through the scan takes the room kept for it.
 */
@Composable
private fun TmdbIssue(issue: UiError?, keepRoom: Boolean) {
    if (issue == null && !keepRoom) return
    val width = Modifier.widthIn(max = HlLayout.readableNarrow)
    Spacer(Modifier.height(HlSpace.m))
    Box {
        if (keepRoom) {
            listOf(UiError.TMDB_KEY_REFUSED, UiError.TMDB_OFFLINE).forEach { each ->
                AttentionNote(stringResource(noteOf(each)), width.alpha(0f).clearAndSetSemantics {})
            }
        }
        issue?.let { AttentionNote(stringResource(noteOf(it)), width) }
    }
}

/** What home says under the counter when TMDB stopped answering for [issue]. */
private fun noteOf(issue: UiError): Int =
    if (issue == UiError.TMDB_KEY_REFUSED) R.string.home_scan_tmdb_refused else R.string.home_scan_tmdb_offline

/**
 * What the scan's counter offers to do of [issue]: change the key TMDB refused, only on a home with
 * no rows. Between the tabs and the rows, a button would take Down from the tabs, and its coming
 * would push the rows down while the scan runs; the key row in Settings says it there.
 */
internal fun scanNoteAction(issue: UiError?, aboveRows: Boolean): ErrorAction? =
    issue?.action?.takeIf { it == ErrorAction.EDIT_TMDB_KEY && !aboveRows }

/** No rows and nothing loading: the first scan, a failure, a scan that failed, or a library still empty. */
@Composable
private fun NoRows(
    content: HomeContent.Message,
    progress: StateFlow<ScanState>,
    onReload: () -> Unit,
    onRescan: () -> Unit,
    onSettings: () -> Unit,
    onAddFolder: () -> Unit,
    modifier: Modifier,
) {
    when (content) {
        HomeContent.FirstScan -> FirstScan(progress, modifier)
        is HomeContent.Failed -> HlErrorState(content.error, onReload, modifier, content.retrying)
        HomeContent.ReadingFolders -> Unit
        is HomeContent.ScanFailed -> ScanFailed(content.folder, content.error, onRescan, onSettings, modifier)
        is HomeContent.NoFilms -> NoFilms(content.hasFolders, onAddFolder, onSettings, modifier)
        HomeContent.ServerEmpty ->
            HlEmptyState(stringResource(R.string.empty_title), modifier, message = stringResource(R.string.empty_server))
    }
}

/**
 * Folders without a film yet, or none at all: a verb, adding the folder that holds them, and
 * beside it the folders already there ([hasFolders]).
 */
@Composable
private fun NoFilms(hasFolders: Boolean, onAddFolder: () -> Unit, onSettings: () -> Unit, modifier: Modifier) {
    HlEmptyState(
        stringResource(R.string.home_empty_title),
        modifier,
        message = stringResource(R.string.home_empty_local),
        action = stringResource(R.string.add_folder),
        actionIcon = HlIcons.Add,
        onAction = onAddFolder,
        secondaryAction = stringResource(R.string.home_see_folders).takeIf { hasFolders },
        onSecondaryAction = onSettings,
    )
}

/** Nothing to show because [folder] could not be read: why ([error]), a way to scan again ([onRescan]), and the folders. */
@Composable
private fun ScanFailed(folder: SourceEntity, error: ScanError, onRescan: () -> Unit, onSettings: () -> Unit, modifier: Modifier) {
    HlEmptyState(
        stringResource(R.string.home_scan_failed),
        modifier,
        message = scanErrorText(error, folder.host),
        attention = true,
        action = stringResource(R.string.retry),
        actionIcon = HlIcons.Refresh,
        onAction = onRescan,
        secondaryAction = stringResource(R.string.home_see_folders),
        onSecondaryAction = onSettings,
    )
}
