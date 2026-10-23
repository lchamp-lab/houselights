package app.houselights.ui.home

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
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.components.HlIconButton
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.HeaderControls
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
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanState
import app.houselights.providers.local.SourceEntity
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
import app.houselights.ui.design.components.HeroInfo
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.LandscapeCard
import app.houselights.ui.design.components.MediaRow
import app.houselights.ui.design.components.wearsNewDot
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
import androidx.compose.ui.platform.LocalContext
import app.houselights.ui.design.components.DetailAhead
import app.houselights.ui.design.components.PicturesAhead
import app.houselights.ui.util.screenWidthPx
import coil.imageLoader
import app.houselights.core.SectionType
import app.houselights.ui.design.components.FilmsOfHeading

@Composable
fun HomeScreen(
    onOpenItem: (MediaItem) -> Unit,
    /** Opens a library's wall; for films, also the sagas it can switch to. */
    onOpenLibrary: (Library, sagas: Library?) -> Unit,
    onOpenSettings: () -> Unit,
    onAddFolder: () -> Unit,
    onOpenSearch: () -> Unit = {},
) {
    val app = appContainer()
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(app))
    ReloadOnLibraryChange { vm.onLibraryChanged() }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val screen = screenWidthPx()
    HomeBands(
        state,
        vm.scan,
        onOpenItem,
        onOpenLibrary,
        onOpenSettings,
        onAddFolder,
        onReload = vm::load,
        onRescan = vm::rescan,
        onLibraryAhead = { app.libraryAhead.ask(app.scope, it, PicturesAhead(context, screen), context.imageLoader) },
        onOpenSearch = onOpenSearch.takeIf { app.providerOrNull()?.capabilities?.search == true },
    )
}

/** Home for [state]: its bar, hero and rows, and focus among them. */
@Composable
internal fun HomeBands(
    state: HomeState,
    scan: StateFlow<ScanState>,
    onOpenItem: (MediaItem) -> Unit,
    onOpenLibrary: (Library, sagas: Library?) -> Unit,
    onOpenSettings: () -> Unit,
    onAddFolder: () -> Unit,
    onReload: () -> Unit,
    onRescan: () -> Unit,
    /** Prefetches a library's wall while the track moves to its tab. */
    onLibraryAhead: (Library) -> Unit = {},
    /** Null when the source cannot search. */
    onOpenSearch: (() -> Unit)? = null,
) {
    val content = state.content
    val sections = (content as? HomeContent.Rows)?.sections.orEmpty()

    // The last selected title stays shown while focus is on the tabs and survives navigation; default: the first.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // Changes once focus settles, so no image loads per step; read only there, so a step recomposes nothing here.
    val shown by rememberSettled { selectedTitle(sections, selectedId) }

    val memory = rememberFocusMemory()
    val rows = rememberLazyListState()
    // Focus returns where it was (a card by row and id, a tab, a button); a first visit starts on the first card.
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
    var focusedRow by remember { mutableStateOf<Int?>(null) }
    BackToStart(away = backGoesUp(focusedRow)) {
        memory.focus(TRACK)
        rows.animateScrollToItem(0)
    }

    // Complete for the launch screen once rows are in and the hero shows a title (a frame after the rows).
    val whole = content !is HomeContent.Loading && (shown != null || sections.isEmpty())
    // Prepares the settled title's detail page, so OK opens it complete.
    DetailAhead(shown)
    AmbientBackdrop(shown?.backdrop, BackdropStyle.Hero, ready = whole) {
        Column(Modifier.fillMaxSize().houseLit(LocalHouseLights.current)) {
            TopBar(
                libraries = state.tabs,
                memory = memory,
                // Back from a library, focus enters the track on that library's tab.
                returnTo = state.tabs.indexOfFirst { memory.last == trackTab(it) }.takeIf { it >= 0 }?.plus(1),
                onLibrary = { library ->
                    memory.record(trackTab(library))
                    // The sagas have no tab: the films' wall shows them in their place.
                    onOpenLibrary(library, state.sagas.takeIf { library.kind == LibraryKind.MOVIES })
                },
                onLibraryAhead = onLibraryAhead,
                onSettings = onOpenSettings,
                onSearch = onOpenSearch,
                // The first row's group, once laid out, so it can take focus.
                intoRows = { first?.takeIf { rows.firstVisibleItemIndex == 0 }?.let { memory.requester(FocusKey.section(it.key)) } },
            )
            when (content) {
                is HomeContent.Browsing -> {
                    // A fixed height fitting the text at the viewer's text size, whatever has focus.
                    HeroInfo(shown, Modifier.padding(start = HlSpace.safeH, top = HlSpace.l).height(textRoom(HlLayout.hero)))
                    Spacer(Modifier.height(HlSpace.l))
                    // A folder's first scan: the counter stays above the rows as they fill in.
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
                // The message starts where the focused title would.
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

/** The places, home's as Gradation, and the tiles, [HlSpace.safeV] from the top; as tall as a compact tile (`HomeLayoutTest`). */
@Composable
internal fun TopBar(
    libraries: List<Library>,
    memory: FocusMemory,
    returnTo: Int?,
    onLibrary: (Library) -> Unit,
    onLibraryAhead: (Library) -> Unit,
    onSettings: () -> Unit,
    /** The first row's focus target, if any. */
    intoRows: () -> FocusRequester?,
    /** Null when the source cannot search. */
    onSearch: (() -> Unit)? = null,
) {
    // The places on the veil, the actions apart on their plates; a long track scrolls, the tiles stay.
    OnArtwork { HeaderControls { Row(
        Modifier.fillMaxWidth().padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.safeV),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The chosen library lights before it opens; returning home makes home the place again.
        var current by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        HlSegmented(
            options = listOf(stringResource(R.string.nav_home)) + libraries.map { it.title ?: libraryName(it.kind) },
            selected = current,
            onSelect = { i ->
                if (i > 0 && current == 0) {
                    current = i
                    // Prefetch the wall while its name lights.
                    onLibraryAhead(libraries[i - 1])
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
            markFirst = true,
        )
        // Down from the buttons enters the first row as from the tabs, not on the card beneath the button.
        val down = Modifier.focusProperties { down = intoRows() ?: FocusRequester.Default }
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
            if (onSearch != null) {
                HlIconButton(
                    HlIcons.Search,
                    stringResource(R.string.search),
                    onClick = onSearch,
                    modifier = Modifier.focusKey(FocusKey.action("search"), memory).then(down),
                )
            }
            HlIconButton(
                HlIcons.Settings,
                stringResource(R.string.nav_settings),
                onClick = onSettings,
                modifier = Modifier.focusKey(FocusKey.action("settings"), memory).then(down),
            )
        }
    } } }
}

@Composable
private fun Sections(
    sections: List<HomeSection>,
    memory: FocusMemory,
    rows: LazyListState,
    onOpen: (MediaItem) -> Unit,
    onFocus: (section: Int, item: MediaItem) -> Unit,
    /** Focus left the rows for the tabs; the selected title stays. */
    onLeave: () -> Unit,
) {
    // Up from any first-row card goes to the tabs, not to the nearer action buttons.
    val toTabs = Modifier.focusProperties { up = memory.requester(TRACK) }
    // A row that must scroll comes to the list's top, where the first rests; the one above leaves whole.
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
                    // A director's row is headed as a poster bills it.
                    heading = section.person?.takeIf { section.type == SectionType.DIRECTOR }?.let { p -> { FilmsOfHeading(p.name) } },
                    // If the focused card is gone, focus returns to its row.
                    modifier = Modifier.focusGroupKey(FocusKey.section(section.key), memory),
                    spacing = section.spacing,
                    state = rememberRowState(section.items.map { it.id }),
                    news = section.items.count { it.wearsNewDot() },
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

/** The title [id] in [sections], or the first title when it is absent. */
internal fun selectedTitle(sections: List<HomeSection>, id: String?): MediaItem? =
    sections.firstNotNullOfOrNull { section -> section.items.find { it.id == id } } ?: sections.firstOrNull()?.items?.firstOrNull()

/** A Home row's stable key: its type and library. */
internal val HomeSection.key: String get() = "$type-$libraryId"

/** Card spacing: stills, titled below the picture, sit further apart than posters. */
internal val HomeSection.spacing: Dp get() = if (landscape) HlSpace.l else HlSpace.m

/** A Home card's key: the same title may sit in two rows. */
internal fun cardKey(section: HomeSection, item: MediaItem) = FocusKey.within(FocusKey.section(section.key), FocusKey.item(item.id))

/** The row holding what [key] names; null for the tabs, the action buttons, or a row gone. */
internal fun sectionIndex(sections: List<HomeSection>, key: String): Int? {
    val row = FocusKey.groups(key).lastOrNull() ?: return null
    return sections.indexOfFirst { FocusKey.section(it.key) == row }.takeIf { it >= 0 }
}

/** Back goes up to the tabs from below the first row; from the first row or the tabs, it leaves. */
internal fun backGoesUp(focusedRow: Int?): Boolean = focusedRow != null && focusedRow > 0

/** The library tabs as one focus group, which picks the tab to focus. */
private const val TRACK = "track"

private fun trackTab(library: Library) = FocusKey.within(TRACK, FocusKey.tab(library.id))

/** Placeholder rows in the real rows' footprint, shown once loading is slow (nothing below needs their room). */
@Composable
private fun SkeletonRows() {
    if (!skeletonDelayPassed()) return
    Column(Modifier.padding(start = HlSpace.safeH, top = HlSpace.xl), verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                repeat(8) { CardSkeleton(HlSize.poster, HlSize.POSTER_RATIO) }
            }
        }
    }
}

/** The first scan's counter; [aboveRows] keeps its lines' room so rows stay put. Only it reads [progress]. */
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

/** Why TMDB stopped answering, if it did; [keepRoom] lays out both notes unseen so one arriving later fits. */
@Composable
private fun TmdbIssue(issue: UiError?, keepRoom: Boolean) {
    if (issue == null && !keepRoom) return
    val width = Modifier.widthIn(max = HlLayout.readableNarrow)
    Spacer(Modifier.height(HlSpace.m))
    Box {
        if (keepRoom) {
            listOf(UiError.TMDB_KEY_REFUSED, UiError.TMDB_OFFLINE).forEach { each ->
                AttentionNote(stringResource(noteOf(each)), width.alpha(0f).clearAndSetSemantics {}, style = Hl.type.caption)
            }
        }
        issue?.let { AttentionNote(stringResource(noteOf(it)), width, style = Hl.type.caption) }
    }
}

private fun noteOf(issue: UiError): Int =
    if (issue == UiError.TMDB_KEY_REFUSED) R.string.home_scan_tmdb_refused else R.string.home_scan_tmdb_offline

/** The counter's fix for [issue] (edit a refused TMDB key), only without rows: above rows it would steal Down. */
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

/** No films yet: an action to add the folder holding them and, with [hasFolders], a link to the folders. */
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

/** [folder] could not be read: the [error], a rescan ([onRescan]) and the folders. */
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
