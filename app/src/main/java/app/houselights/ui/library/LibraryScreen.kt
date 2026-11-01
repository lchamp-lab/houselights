package app.houselights.ui.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.ArtworkKind
import app.houselights.core.BadgeTokens
import app.houselights.core.Definition
import app.houselights.core.FileTypes
import app.houselights.core.Formats
import app.houselights.core.HdrFormat
import app.houselights.core.Library
import app.houselights.core.LibraryFacets
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.Person
import app.houselights.core.ProviderCapabilities
import app.houselights.core.Quality
import app.houselights.core.SortKeys
import app.houselights.core.SortOption
import app.houselights.core.WatchStatus
import app.houselights.ui.design.BackToStart
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.FocusPlaces
import app.houselights.ui.design.FocusTakesPlace
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.OnArtwork
import app.houselights.ui.design.RestoreFocus
import app.houselights.ui.design.components.AmbientBackdrop
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.CardSkeleton
import app.houselights.ui.design.components.DetailAhead
import app.houselights.ui.design.components.HeaderControls
import app.houselights.ui.design.components.HlEmptyState
import app.houselights.ui.design.components.HlErrorState
import app.houselights.ui.design.components.HlMenuButton
import app.houselights.ui.design.components.LocalGroupFocused
import app.houselights.ui.design.components.MenuOption
import app.houselights.ui.design.components.MenuSection
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.PosterWallGrid
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.components.TitleLine
import app.houselights.ui.design.components.WithItsPicture
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.design.scrollHome
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.util.NBSP
import app.houselights.ui.util.PartFailed
import app.houselights.ui.util.ReloadOnLibraryChange
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.libraryName
import app.houselights.ui.util.pictureName
import app.houselights.ui.util.rememberSettled
import app.houselights.ui.util.retainedError
import app.houselights.ui.util.skeletonDelayPassed
import app.houselights.ui.util.titleCount
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Placeholder rows while the wall loads: more than the screen shows, like the wall. */
private const val SKELETON_ROWS = 3

/**
 * A poster wall, filtered by [opensOn] if given (a format, or person [personName]); else films show their [sagas]. A
 * poster's Modifier opens the choice of its pictures ([onEditArtwork]) and its match ([onIdentify]), as its page does.
 */
@Composable
fun LibraryScreen(
    library: Library,
    title: String,
    onOpenItem: (MediaItem) -> Unit,
    opensOn: LibraryFilter? = null,
    personName: String? = null,
    sagas: Library? = null,
    onEditArtwork: (String, ArtworkKind) -> Unit = { _, _ -> },
    onIdentify: (String) -> Unit = {},
    vm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(appContainer(), library, opensOn, sagas)),
) {
    ReloadOnLibraryChange { scanOnly -> vm.onLibraryChanged(scanOnly) }
    Wall(
        vm,
        library,
        title,
        onOpenItem,
        // The person the wall was opened for, offered by the filter menu.
        person = opensOn?.person?.let { id -> personName?.let { Person(it, null, null, id) } },
        onEditArtwork = onEditArtwork,
        onIdentify = onIdentify,
    )
}

/**
 * The wall [vm] reads, [library]'s, named [title], or the sagas alone: a page, its header at the top, the selected
 * title, then two whole lines of posters. Back from past the first line returns to the first poster, then leaves. Menu
 * or a long OK on a poster opens its options (#47, [PosterOptions]); a title they take out of the wall's filter leaves
 * it at once, focus going to the poster taking its place, as after Back from its page.
 */
@Composable
internal fun Wall(
    vm: LibraryViewModel,
    library: Library,
    title: String,
    onOpenItem: (MediaItem) -> Unit,
    person: Person? = null,
    onEditArtwork: (String, ArtworkKind) -> Unit = { _, _ -> },
    onIdentify: (String) -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val notSaved by vm.notSaved.collectAsStateWithLifecycle()
    val can = vm.capabilities
    val choices = remember(vm) { WallChoices(vm::setView, vm::clearFilters, vm::setFilter, vm::setSort) }
    val loads = remember(vm) { WallLoads(vm::retry, vm::loadMore, vm::retryPage) }
    // Shown once focus settles, read outside composition: a step loads no image and recomposes nothing.
    val selectedId = rememberSaveable { mutableStateOf<String?>(null) }
    val shown = rememberSettled { selectedIn(state.items, selectedId.value) }
    // Films and sagas each start at their top, as does a wall started over (a sort, a filter): kept, its grid would open
    // where its first poster went in the titles read anew.
    val gridState = rememberSaveable(state.view, state.generation, saver = LazyGridState.Saver) { LazyGridState() }
    // Where the poster last focused stands, kept while a page it opened shows.
    val place = rememberSaveable { mutableIntStateOf(0) }
    val grid = remember(gridState) { WallGrid(gridState, selectedId, place) }
    // Focus returns to the last poster, else the one taking its place (marked on its page), else the first.
    val memory = rememberFocusMemory()
    RestoreFocus(
        memory,
        grid.first,
        ready = state.items.isNotEmpty(),
        inPlace = { key -> inPlaceOf(key, state.items, grid.place.intValue)?.let { FocusKey.item(it.id) } },
    ) { key ->
        val index = FocusKey.itemId(key)?.let { id -> state.items.indexOfFirst { it.id == id } } ?: -1
        if (index >= 0) grid.state.scrollToItem(index)
        index >= 0
    }
    // A poster leaving with focus, marked from its options or on its page, leaves it to the one taking its place.
    val places = remember(state.items) { listOf(FocusPlaces(null, state.items.map { FocusKey.item(it.id) })) }
    FocusTakesPlace(places, memory)
    FocusAfterRetry(state, memory)
    // A poster's options: Menu or a long OK on it.
    var options by remember { mutableStateOf<MediaItem?>(null) }
    val actions = remember(vm, onEditArtwork, onIdentify) { vm.changes.actions(vm::mark, vm::favorite, onEditArtwork, onIdentify) }
    val posters = remember(vm, actions) { WallPosters(actions, onOptions = { options = it }, onFocusTitle = vm.changes::focusedOn) }
    // A change not saved puts its title back in its place: focus goes back to it, unless it went up to the menus meanwhile,
    // or the wall was left before it was said.
    var inMenus by remember { mutableStateOf(false) }
    RefocusNotSaved(notSaved, memory, refocuses = { !inMenus })
    // The sagas alone are named as such, on the films' wall.
    val sagasAlone = state.view == WallView.SAGAS
    val kind = if (sagasAlone) LibraryKind.COLLECTIONS else library.kind
    val name = if (sagasAlone) libraryName(LibraryKind.COLLECTIONS) else title

    // Prepares the settled title's detail page, so OK opens it complete.
    DetailAhead(shown.value)
    AmbientBackdrop(shown.value?.backdrop, BackdropStyle.Diffuse) {
        BackToFirstPoster(grid.focusedIndex) {
            grid.state.scrollHome()
            grid.first.tryRequestFocus()
        }
        Column(Modifier.fillMaxSize()) {
            WallHeader(state, kind, name, shown, person, can, choices, onFocusChange = { inMenus = it })
            WallBody(state, kind, grid, memory, shown, can, choices, loads, onOpenItem, posters, notSaved)
        }
        PosterOptions(options, actions, onDismiss = { options = null })
    }
}

/**
 * The posters' options ([actions]), which Menu or a long OK opens ([onOptions]), the Menu key in the focused poster's
 * corner saying so; a poster taking focus says so ([onFocusTitle]), so a change not saved is said only while its title
 * has it.
 */
@Immutable
private class WallPosters(val actions: PosterActions, val onOptions: (MediaItem) -> Unit, val onFocusTitle: (String) -> Unit)

/** What took the place of the title [key] names, gone from [items]: the one now at its [place], else the last. */
private fun inPlaceOf(key: String, items: List<MediaItem>, place: Int): MediaItem? {
    val id = FocusKey.itemId(key) ?: return null
    if (items.any { it.id == id }) return null
    return items.getOrNull(place) ?: items.lastOrNull()
}

/** What the wall's menus choose: its view, its filters, all of them off, and its sort. */
@Immutable
private class WallChoices(
    val onView: (WallView) -> Unit,
    val onClearFilters: () -> Unit,
    val onFilter: (LibraryFilter) -> Unit,
    val onSort: (SortOption) -> Unit,
)

/** What loads the wall: the wall again, its next page, and that page again once it failed. */
@Immutable
private class WallLoads(val onRetry: () -> Unit, val onMore: () -> Unit, val onRetryPage: () -> Unit)

/**
 * The grid's state, its first poster's requester, the selected title, the focused poster's index (read by Back), and
 * the [place] of the poster last focused, kept while a page it opened shows.
 */
@Stable
private class WallGrid(val state: LazyGridState, val selectedId: MutableState<String?>, val place: MutableIntState) {
    val first = FocusRequester()
    val focusedIndex = mutableStateOf<Int?>(null)
}

/** Once Réessayer at the wall's foot loads its page, focus moves to that page's first poster. */
@Composable
private fun FocusAfterRetry(state: LibraryState, memory: FocusMemory) {
    // Where the failed page's foot was.
    var footAt by remember { mutableIntStateOf(-1) }
    LaunchedEffect(state.pageError, state.items.size) {
        if (state.pageError != null) {
            footAt = state.items.size
        } else if (footAt >= 0 && memory.last == RETRY_PAGE) {
            // An in-place reload may remove the foot without adding a page: then the last poster.
            val first = state.items.getOrNull(footAt) ?: state.items.lastOrNull()
            footAt = -1
            if (first != null) {
                withFrameNanos { }
                memory.focus(FocusKey.item(first.id))
            }
        }
    }
}

/** The wall's [name] lit as the place, at the safe area's top, its count beside it; the menus on plates over the wall's picture. */
@Composable
private fun WallHeader(
    state: LibraryState,
    kind: LibraryKind,
    name: String,
    shown: State<MediaItem?>,
    person: Person?,
    can: ProviderCapabilities,
    choices: WallChoices,
    onFocusChange: (inMenus: Boolean) -> Unit,
) {
    OnArtwork {
        HeaderControls {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.safeV)
                    .onFocusChanged { onFocusChange(it.hasFocus) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row {
                    ScreenTitle(name, Modifier.alignByBaseline())
                    if (state.total > 0) {
                        val count = wallCount(state, kind)
                        WallFolio(count, state.items, shown, state.total, state.sort, Modifier.alignByBaseline().padding(start = HlSpace.m))
                    }
                }
                Spacer(Modifier.weight(1f))
                WallMenus(kind, state, can, person, choices)
            }
        }
    }
}

/** What the wall shows: "8 titres · 1 saga", its titles alone where the source does not count its sagas. */
@Composable
private fun wallCount(state: LibraryState, kind: LibraryKind): String {
    if (kind == LibraryKind.COLLECTIONS) return pluralStringResource(R.plurals.collection_count, state.total, state.total)
    val sagas = state.sagas ?: 0
    val titles = state.total - sagas
    return listOfNotNull(
        titles.takeIf { it > 0 }?.let { titleCount(it) },
        sagas.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.collection_count, it, it) },
    ).joinToString(DOT)
}

/** Between the parts of the count, and of the filter button's detail. */
private const val DOT = " · "

/** The selected title on one line, then the posters, or why there are none. */
@Composable
private fun WallBody(
    state: LibraryState,
    kind: LibraryKind,
    grid: WallGrid,
    memory: FocusMemory,
    shown: State<MediaItem?>,
    can: ProviderCapabilities,
    choices: WallChoices,
    loads: WallLoads,
    onOpenItem: (MediaItem) -> Unit,
    posters: WallPosters,
    notSaved: NotSaved?,
) {
    Column {
        // A long name drops its facts first, then ends in an ellipsis.
        Box(
            Modifier
                .padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.xs)
                .height(HlLayout.infoLine),
            contentAlignment = Alignment.CenterStart,
        ) {
            // Shown with its backdrop, as on Home; a change not saved said in place of its title's facts.
            WithItsPicture(shown.value) { TitleLine(it, notSaved = notSaved.saidFor(it.id)) }
        }
        // While retrying, the error stays with its button busy.
        val wallError = retainedError(state.error?.takeIf { state.items.isEmpty() }, retrying = state.loading)
        val message = Modifier.padding(start = HlSpace.safeH, top = HlSpace.l)
        when {
            state.items.isEmpty() && wallError != null -> HlErrorState(wallError, loads.onRetry, message, retrying = state.loading)
            state.loading && state.items.isEmpty() -> SkeletonWall()
            // Takes the filters off, the sagas alone still shown.
            state.items.isEmpty() && state.applied(can).activeCount > 0 -> HlEmptyState(
                stringResource(R.string.no_match),
                message,
                action = stringResource(R.string.reset_filters),
                actionIcon = HlIcons.Filter,
                onAction = { choices.onFilter(LibraryFilter()) },
            )
            state.items.isEmpty() -> HlEmptyState(
                stringResource(if (kind == LibraryKind.COLLECTIONS) R.string.empty_collections else R.string.empty_title),
                message,
                backHint = true,
            )
            else -> Posters(state, grid, memory, loads, onOpenItem, posters)
        }
    }
}

/** The posters, seven across; a scrolled line comes to where the first line rests. */
@Composable
private fun Posters(
    state: LibraryState,
    grid: WallGrid,
    memory: FocusMemory,
    loads: WallLoads,
    onOpenItem: (MediaItem) -> Unit,
    posters: WallPosters,
) {
    var gridHasFocus by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val moreOptions = stringResource(R.string.more_options)
    CompositionLocalProvider(LocalGroupFocused provides gridHasFocus) {
        PosterWallGrid(
            grid.state,
            Modifier.onFocusChanged {
                gridHasFocus = it.hasFocus
                // Leaving for the buttons above keeps the selected title.
                if (!it.hasFocus) grid.focusedIndex.value = null
            },
        ) {
            itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                PosterCard(
                    item = item,
                    onClick = { onOpenItem(item) },
                    width = null,
                    onFocus = {
                        grid.selectedId.value = item.id
                        grid.focusedIndex.value = index
                        grid.place.intValue = index
                        posters.onFocusTitle(item.id)
                        // The first line sits under the padding: show it all.
                        if (index < HlLayout.WALL_COLUMNS) scope.launch { grid.state.scrollHome() }
                        if (index > state.items.size - HlLayout.WALL_COLUMNS * 3) loads.onMore()
                    },
                    onLongClick = { posters.onOptions(item) }.takeIf { posters.actions.hasOptions(item) },
                    onLongClickLabel = moreOptions,
                    modifier = Modifier
                        .focusKey(FocusKey.item(item.id), memory)
                        .then(if (index == 0) Modifier.focusRequester(grid.first) else Modifier),
                )
            }
            // The next page's error and its fix, at the wall's foot.
            state.pageError?.let { error ->
                item(key = PAGE_FAILED, span = { GridItemSpan(maxLineSpan) }) {
                    PartFailed(
                        error,
                        retrying = state.pageRetrying,
                        onRetry = loads.onRetryPage,
                        modifier = Modifier.padding(top = HlSpace.m),
                        actionModifier = Modifier.focusKey(RETRY_PAGE, memory),
                    )
                }
            }
        }
    }
}

/** Item key of the failed-page foot; never a title id. */
private const val PAGE_FAILED = "page-failed"

/** Réessayer, at the foot of the wall. */
private val RETRY_PAGE = FocusKey.action("retry-page")

/** Back returns to the first poster from past the first line; from the first line, it leaves. */
internal fun backReturnsToFirst(focusedIndex: Int?): Boolean = focusedIndex != null && focusedIndex >= HlLayout.WALL_COLUMNS

/** The title [id] in [items], or the first when absent (a filter may remove it). */
private fun selectedIn(items: List<MediaItem>, id: String?): MediaItem? = items.find { it.id == id } ?: items.firstOrNull()

/** Back past the first line ([focusedIndex]) runs [toFirst]; reading it here keeps recomposition local. */
@Composable
private fun BackToFirstPoster(focusedIndex: State<Int?>, toFirst: suspend () -> Unit) {
    BackToStart(away = backReturnsToFirst(focusedIndex.value), toStart = toFirst)
}

/** Placeholder posters laid out like the wall, shown once loading is slow. */
@Composable
private fun SkeletonWall() {
    if (!skeletonDelayPassed()) return
    Column(
        Modifier.padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlFocus.wallRoom),
        verticalArrangement = Arrangement.spacedBy(HlSpace.l),
    ) {
        repeat(SKELETON_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(HlLayout.wallGutter)) {
                repeat(HlLayout.WALL_COLUMNS) { CardSkeleton(null, HlSize.POSTER_RATIO, Modifier.weight(1f)) }
            }
        }
    }
}

/** The header's menus: filters, with first what a films wall with sagas shows, then sort. */
@Composable
private fun RowScope.WallMenus(kind: LibraryKind, state: LibraryState, can: ProviderCapabilities, person: Person?, choices: WallChoices) {
    // A sagas library has no filters of its own; the sagas a films wall shows alone are filtered through its films.
    if (kind != LibraryKind.COLLECTIONS || state.view != null) {
        FilterButton(state.filter, state.facets, can, person, state.view, choices.onView, choices.onClearFilters, choices.onFilter)
    }
    Spacer(Modifier.width(HlSpace.s))
    SortButton(state.sort, choices.onSort)
}

/** Sort menu: « Trier » and the [sort] in use, lit, as a choice: « Titre » alone would read as a title. */
@Composable
internal fun SortButton(sort: SortOption, onSort: (SortOption) -> Unit, modifier: Modifier = Modifier) {
    HlMenuButton(
        text = stringResource(R.string.sort),
        icon = HlIcons.Sort,
        modifier = modifier,
        detail = sortLabel(sort),
        detailLit = true,
        sections = listOf(
            MenuSection(null, SortOption.entries.map { MenuOption(sortOrder(it), sort == it) { onSort(it) } }),
        ),
    )
}

@Composable
private fun sortLabel(sort: SortOption): String = stringResource(
    when (sort) {
        SortOption.TITLE -> R.string.sort_title
        SortOption.ADDED -> R.string.sort_added
        SortOption.YEAR -> R.string.sort_year
        SortOption.RATING -> R.string.sort_rating
    },
)

/** A sort as the menu offers it: its key and direction. */
@Composable
private fun sortOrder(sort: SortOption): String = stringResource(
    when (sort) {
        SortOption.TITLE -> R.string.sort_title_order
        SortOption.ADDED -> R.string.sort_added_order
        SortOption.YEAR -> R.string.sort_year_order
        SortOption.RATING -> R.string.sort_rating_order
    },
)

/**
 * Filter menu, combined filters, open while choosing. A films wall with sagas first chooses its [view], named on the
 * button before the filters. Sections offer the library's [facets] plus any active value, so a filter set elsewhere
 * can be cleared, and nothing the source cannot filter by ([can]); [onClear] takes every filter off, and the view
 * back to the films and sagas.
 */
@Composable
internal fun FilterButton(
    filter: LibraryFilter,
    facets: LibraryFacets,
    can: ProviderCapabilities,
    person: Person?,
    view: WallView?,
    onView: (WallView) -> Unit,
    onClear: () -> Unit,
    onChange: (LibraryFilter) -> Unit,
) {
    val statusLabels = mapOf(
        WatchStatus.ALL to stringResource(R.string.show_all),
        WatchStatus.UNWATCHED to stringResource(R.string.filter_unwatched),
        WatchStatus.IN_PROGRESS to stringResource(R.string.status_in_progress),
        WatchStatus.WATCHED to stringResource(R.string.status_watched),
        WatchStatus.FAVORITES to stringResource(R.string.section_favorites),
    )
    val qualityLabels = mapOf(
        Quality.UHD_4K to Definition.ULTRA_HD.label,
        Quality.HDR to stringResource(R.string.quality_hdr),
        Quality.DOLBY_VISION to HdrFormat.DOLBY_VISION.token,
    )
    val unnamed = stringResource(R.string.filter_person_unnamed)
    val personName = { id: String -> person?.takeIf { it.id == id }?.name ?: unnamed }
    val sagasName = libraryName(LibraryKind.COLLECTIONS)
    // Sagas the source cannot filter by their films show whole: the films' filters wait for them.
    val filtered = filtersApply(view, can)
    val filters = if (filtered) filter else LibraryFilter()

    val shows = view?.let {
        MenuSection(
            stringResource(R.string.filter_show),
            listOf(
                MenuOption(stringResource(R.string.show_grouped), it == WallView.FILMS_AND_SAGAS) { onView(WallView.FILMS_AND_SAGAS) },
                MenuOption(sagasName, it == WallView.SAGAS) { onView(WallView.SAGAS) },
            ),
        )
    }
    val sections = listOfNotNull(shows) +
        (if (filtered) filterSections(filter, facets, can, person, statusLabels, qualityLabels, personName, onChange) else emptyList()) +
        listOfNotNull(
            MenuSection(null, listOf(MenuOption(stringResource(R.string.reset_filters), false, onClear)))
                .takeIf { filters.activeCount > 0 || view == WallView.SAGAS },
        )

    // The button names the sagas shown alone, then the active filter, or how many are combined.
    val active = when (filters.activeCount) {
        0 -> null
        1 -> activeName(filters, statusLabels, qualityLabels, personName)
        else -> pluralStringResource(R.plurals.filter_count, filters.activeCount, filters.activeCount)
    }
    val detail = listOfNotNull(sagasName.takeIf { view == WallView.SAGAS }, active).joinToString(DOT).ifEmpty { null }
    HlMenuButton(
        text = stringResource(R.string.filter),
        icon = HlIcons.Filter,
        sections = sections,
        detail = detail,
        // An active filter is a state, not the main action: its detail is lit, the button stays secondary.
        detailLit = detail != null,
        closeOnSelect = false,
    )
}

/** The filters' sections: what the library holds ([facets]) and the source can filter by ([can]). */
@Composable
private fun filterSections(
    filter: LibraryFilter,
    facets: LibraryFacets,
    can: ProviderCapabilities,
    person: Person?,
    statusLabels: Map<WatchStatus, String>,
    qualityLabels: Map<Quality, String>,
    personName: (String) -> String,
    onChange: (LibraryFilter) -> Unit,
): List<MenuSection> {
    val decadeLabels = (facets.decades + listOfNotNull(filter.decade)).associateWith { stringResource(R.string.decade, it) }
    // The files' picture, finer than their quality, where the source filters by it.
    val pictures = can.formatFilters && (facets.pictures.isNotEmpty() || filter.picture != null)
    return listOfNotNull(
        MenuSection(
            stringResource(R.string.filter_status),
            WatchStatus.entries.map { st ->
                MenuOption(statusLabels.getValue(st), filter.status == st) { onChange(filter.copy(status = st)) }
            },
        ),
        choices(R.string.filter_genre, R.string.all_genres, facets.genres, filter.genre, { it }, { g, held -> sameGenre(held, g) }) {
            onChange(filter.copy(genre = it))
        },
        choices(R.string.filter_decade, R.string.all_decades, facets.decades, filter.decade, decadeLabels::getValue) {
            onChange(filter.copy(decade = it))
        },
        if (pictures) {
            pictureChoices(filter, facets, onChange)
        } else {
            choices(R.string.filter_quality, R.string.all_qualities, facets.qualities, filter.quality, qualityLabels::getValue) {
                onChange(filter.copy(quality = it))
            }
        },
        choices(R.string.filter_sound, R.string.all_sounds, facets.sounds, filter.sound, Formats::soundName) {
            onChange(filter.copy(sound = it))
        }.takeIf { can.formatFilters },
        choices(R.string.filter_file, R.string.all_files, facets.fileTypes, filter.fileType, FileTypes::label) {
            onChange(filter.copy(fileType = it))
        }.takeIf { can.formatFilters },
        choices(R.string.filter_person, R.string.all_people, listOfNotNull(person?.id), filter.person, personName) {
            onChange(filter.copy(person = it))
        }.takeIf { can.personFilter },
    )
}

/** A menu section: [all], then [values] plus [held] if absent from them (matched with [same]); null if empty. */
@Composable
private fun <T : Any> choices(
    @StringRes title: Int,
    @StringRes all: Int,
    values: List<T>,
    held: T?,
    label: (T) -> String,
    same: (shown: T, held: T?) -> Boolean = { shown, h -> shown == h },
    choose: (T?) -> Unit,
): MenuSection? {
    val offered = if (held == null || values.any { same(it, held) }) values else values + held
    if (offered.isEmpty()) return null
    return MenuSection(
        stringResource(title),
        listOf(MenuOption(stringResource(all), held == null) { choose(null) }) +
            offered.map { MenuOption(label(it), same(it, held)) { choose(it) } },
    )
}

/** The picture section by case names ("Ultra HD"); a plain "HDR" token ([BadgeTokens.HDR]) checks any HDR. */
@Composable
private fun pictureChoices(filter: LibraryFilter, facets: LibraryFacets, onChange: (LibraryFilter) -> Unit): MenuSection {
    val held = filter.picture ?: filter.quality?.let(::pictureOf)
    val (definitions, ranges) = (facets.pictures + listOfNotNull(held)).distinct().filter { it != BadgeTokens.HDR }
        .partition { token -> Definition.entries.any { it.token == token } }
    val anyHdr = filter.quality == Quality.HDR || filter.picture == BadgeTokens.HDR
    val hdr = MenuOption(stringResource(R.string.quality_hdr), anyHdr) { onChange(filter.copy(quality = Quality.HDR, picture = null)) }

    fun option(token: String) = MenuOption(pictureName(token), held == token) { onChange(filter.copy(quality = null, picture = token)) }
    val all = MenuOption(stringResource(R.string.all_pictures), filter.quality == null && filter.picture == null) {
        onChange(filter.copy(quality = null, picture = null))
    }
    return MenuSection(
        stringResource(R.string.filter_picture),
        listOf(all) + definitions.map(::option) + listOfNotNull(hdr.takeIf { anyHdr || Quality.HDR in facets.qualities }) +
            ranges.map(::option),
    )
}

/** The badge token a quality names; null for HDR, which names no single one. */
private fun pictureOf(quality: Quality): String? = when (quality) {
    Quality.UHD_4K -> Definition.ULTRA_HD.token
    Quality.DOLBY_VISION -> HdrFormat.DOLBY_VISION.token
    Quality.HDR -> null
}

/** The single active filter, by its menu name. */
@Composable
private fun activeName(
    filter: LibraryFilter,
    statusLabels: Map<WatchStatus, String>,
    qualityLabels: Map<Quality, String>,
    personName: (String) -> String,
): String? = when {
    filter.status != WatchStatus.ALL -> statusLabels.getValue(filter.status)
    filter.genre != null -> filter.genre
    filter.decade != null -> stringResource(R.string.decade, filter.decade)
    filter.quality != null -> qualityLabels.getValue(filter.quality)
    filter.picture != null -> pictureName(filter.picture)
    filter.sound != null -> Formats.soundName(filter.sound)
    filter.fileType != null -> FileTypes.label(filter.fileType)
    else -> filter.person?.let(personName)
}

/** [held] equals [shown] ignoring ASCII case only, as SQLite's lower() does ("Épouvante" and "épouvante" differ). */
private fun sameGenre(held: String?, shown: String): Boolean =
    held != null && held.length == shown.length && held.zip(shown).all { (a, b) -> a == b || sameAsciiLetter(a, b) }

private fun sameAsciiLetter(a: Char, b: Char): Boolean = a.isLetter() && a < '\u0080' && a.equals(b, ignoreCase = true)

/** The folio: the count, and past the third line the selected title's sort key and position; read only here. */
@Composable
private fun WallFolio(
    count: String,
    items: List<MediaItem>,
    shown: State<MediaItem?>,
    total: Int,
    sort: SortOption,
    modifier: Modifier = Modifier,
) {
    val item = shown.value
    val index = item?.let { selected -> items.indexOfFirst { it.id == selected.id } } ?: -1
    val folio = item?.takeIf { index >= HlLayout.WALL_COLUMNS * FOLIO_FROM_LINE }?.let { folioOf(it, sort) }
    if (folio == null) {
        Text(count, style = Hl.type.meta, color = Hl.colors.contentSecondary, modifier = modifier)
        return
    }
    val quiet = Hl.colors.contentSecondary
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
        Text(folio, style = Hl.type.subject, color = quiet, modifier = Modifier.alignByBaseline())
        Text("${index + 1}$NBSP/$NBSP$total", style = Hl.type.meta, color = quiet, modifier = Modifier.alignByBaseline())
    }
}

/** The wall line past which the folio shows the selected title's position. */
private const val FOLIO_FROM_LINE = 3

/** [item]'s key in a wall sorted by [sort]: initial, year (a saga's latest), month added or score; null when it has none. */
internal fun folioOf(item: MediaItem, sort: SortOption, locale: Locale = Locale.getDefault()): String? = when (sort) {
    SortOption.TITLE -> SortKeys.initial(item.title)
    SortOption.YEAR -> (item.endYear?.takeIf { item.kind == MediaKind.COLLECTION } ?: item.year)?.toString()
    SortOption.ADDED -> item.addedAt?.let { monthOf(it, locale) }
    SortOption.RATING -> item.communityRating?.let { String.format(locale, "%.1f", it) }
}

/** The month and year of [epochMs], as a heading writes them: "Octobre 2026". */
private fun monthOf(epochMs: Long, locale: Locale): String =
    SimpleDateFormat("LLLL yyyy", locale).format(Date(epochMs)).replaceFirstChar { it.titlecase(locale) }
