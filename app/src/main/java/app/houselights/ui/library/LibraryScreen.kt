package app.houselights.ui.library

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.ListScroll
import app.houselights.ui.design.BackToStart
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.RestoreFocus
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.R
import app.houselights.core.Definition
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.LibraryFacets
import app.houselights.core.LibraryFilter
import app.houselights.core.Quality
import app.houselights.core.SortOption
import app.houselights.core.WatchStatus
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.components.Backdrop
import app.houselights.ui.design.components.TitleLine
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.components.HlMenuButton
import app.houselights.ui.design.components.MenuOption
import app.houselights.ui.design.components.MenuSection
import app.houselights.ui.design.components.LocalGroupFocused
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.components.rememberAmbientColor
import app.houselights.ui.design.components.CardSkeleton
import app.houselights.ui.design.components.HlEmptyState
import app.houselights.ui.home.ErrorMessage
import app.houselights.ui.util.PartFailed
import app.houselights.ui.util.ReloadOnLibraryChange
import app.houselights.ui.util.retainedError
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.skeletonDelayPassed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val COLUMNS = 7

/** Rows of empty frames while the wall loads: more than the screen shows, like the wall itself. */
private const val SKELETON_ROWS = 3

/** A wall of posters, with the focused title named in the header. */
@Composable
fun LibraryScreen(
    library: Library,
    title: String,
    onOpenItem: (MediaItem) -> Unit,
) {
    val app = appContainer()
    val vm: LibraryViewModel = viewModel(key = "library-${library.id}", factory = LibraryViewModel.factory(app, library))
    ReloadOnLibraryChange { vm.onLibraryChanged() }
    val state by vm.state.collectAsStateWithLifecycle()

    // The title last selected stays on the screen while focus is on the buttons, and is found again
    // coming back; before any, or once a filter takes it off the wall, the first title. Set at every
    // step across the wall, it is only read in [ShowOnceSettled]: a step recomposes that alone, never
    // the header.
    val selectedId = rememberSaveable { mutableStateOf<String?>(null) }
    val shown = remember { mutableStateOf<MediaItem?>(null) }
    ShowOnceSettled(selectedId, state.items, shown)
    val gridFocus = remember { FocusRequester() }
    var gridHasFocus by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // Each arrival puts focus back on the poster left, else on the first one.
    val memory = rememberFocusMemory()
    RestoreFocus(memory, gridFocus, ready = state.items.isNotEmpty()) { key ->
        val index = FocusKey.itemId(key)?.let { id -> state.items.indexOfFirst { it.id == id } } ?: -1
        if (index >= 0) gridState.scrollToItem(index)
        index >= 0
    }
    // Past the first line, Back returns to the first poster; from the first line, it goes home. Set
    // at every step too, and read in [BackToFirstPoster] alone.
    val focusedIndex = remember { mutableStateOf<Int?>(null) }
    BackToFirstPoster(focusedIndex) {
        gridState.animateScrollToItem(0)
        runCatching { gridFocus.requestFocus() }
    }
    // Where the foot stood when the next page failed: once Réessayer brings the page, focus goes to
    // its first poster, where the button was, not nowhere.
    var footAt by remember { mutableIntStateOf(-1) }
    LaunchedEffect(state.pageError, state.items.size) {
        if (state.pageError != null) {
            footAt = state.items.size
        } else if (footAt >= 0 && memory.last == RETRY_PAGE) {
            // A reload in place may have taken the foot away with no page added: the last poster then.
            val first = state.items.getOrNull(footAt) ?: state.items.lastOrNull()
            footAt = -1
            if (first != null) {
                withFrameNanos { }
                memory.focus(FocusKey.item(first.id))
            }
        }
    }

    val ambient = rememberAmbientColor(shown.value?.backdrop)
    CompositionLocalProvider(LocalAmbient provides { ambient.value }) {
        Box(Modifier.fillMaxSize()) {
            Backdrop(shown.value?.backdrop, BackdropStyle.Quiet)
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.safeV),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScreenTitle(title)
                    Spacer(Modifier.width(HlSpace.m))
                    if (state.total > 0) {
                        Text(
                            pluralStringResource(
                                if (library.kind == LibraryKind.COLLECTIONS) R.plurals.collection_count else R.plurals.title_count,
                                state.total,
                                state.total,
                            ),
                            style = Hl.type.meta,
                            color = Hl.colors.contentSecondary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // Collections are browsed by name or date; filters belong to films.
                    if (library.kind != LibraryKind.COLLECTIONS) FilterButton(state.filter, state.facets, vm::setFilter)
                    Spacer(Modifier.width(HlSpace.s))
                    // "Trier", and how it is sorted now: alone, "Titre" would read as a title.
                    HlMenuButton(
                        text = stringResource(R.string.sort),
                        icon = HlIcons.Sort,
                        detail = sortLabel(state.sort),
                        sections = listOf(
                            MenuSection(null, SortOption.entries.map { MenuOption(sortOrder(it), state.sort == it) { vm.setSort(it) } }),
                        ),
                    )
                }
                // The title selected, named without taking space from the wall: one line inside the
                // safe area, a long name drops its facts first, then ends in an ellipsis.
                Box(
                    Modifier
                        .padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.xs)
                        .height(HlLayout.infoLine),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    shown.value?.let { TitleLine(it) }
                }
                // Trying again keeps the error on screen, its button at work, until the answer.
                val wallError = retainedError(state.error?.takeIf { state.items.isEmpty() }, retrying = state.loading)
                when {
                    state.items.isEmpty() && wallError != null -> ErrorMessage(wallError, vm::retry, retrying = state.loading)
                    state.loading && state.items.isEmpty() -> SkeletonWall()
                    state.items.isEmpty() && state.filter.activeCount > 0 -> HlEmptyState(
                        stringResource(R.string.no_match),
                        Modifier.padding(start = HlSpace.safeH, top = HlSpace.l),
                        action = stringResource(R.string.reset_filters),
                        actionIcon = HlIcons.Filter,
                        onAction = { vm.setFilter(LibraryFilter()) },
                    )
                    state.items.isEmpty() -> HlEmptyState(
                        stringResource(if (library.kind == LibraryKind.COLLECTIONS) R.string.empty_collections else R.string.empty_title),
                        Modifier.padding(start = HlSpace.safeH, top = HlSpace.l),
                        backHint = true,
                    )
                    // The wall starts under the header: a line of posters comes back to where the first rests.
                    else -> CompositionLocalProvider(LocalGroupFocused provides gridHasFocus) {
                        ListScroll(top = HlSpace.m) { LazyVerticalGrid(
                        columns = GridCells.Fixed(COLUMNS),
                        state = gridState,
                        contentPadding = PaddingValues(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.m, bottom = HlFocus.edge),
                        horizontalArrangement = Arrangement.spacedBy(HlSpace.l),
                        verticalArrangement = Arrangement.spacedBy(HlSpace.l),
                        modifier = Modifier.fillMaxSize().onFocusChanged {
                            gridHasFocus = it.hasFocus
                            // Leaving the wall for the buttons above: the title selected stays.
                            if (!it.hasFocus) focusedIndex.value = null
                        },
                    ) {
                        itemsIndexed(state.items, key = { _, it -> it.id }) { index, item ->
                            PosterCard(
                                item = item,
                                onClick = { onOpenItem(item) },
                                width = null,
                                onFocused = {
                                    selectedId.value = item.id
                                    focusedIndex.value = index
                                    // The first line sits under the padding: show it all.
                                    if (index < COLUMNS) scope.launch { gridState.animateScrollToItem(0) }
                                    if (index > state.items.size - COLUMNS * 3) vm.loadMore()
                                },
                                modifier = Modifier
                                    .focusKey(FocusKey.item(item.id), memory)
                                    .then(if (index == 0) Modifier.focusRequester(gridFocus) else Modifier),
                            )
                        }
                        // The next page could not load: why, at the foot of the wall, and what fixes it.
                        state.pageError?.let { error ->
                            item(key = PAGE_FAILED, span = { GridItemSpan(maxLineSpan) }) {
                                PartFailed(
                                    error,
                                    retrying = state.pageRetrying,
                                    onRetry = vm::retryPage,
                                    modifier = Modifier.padding(top = HlSpace.m),
                                    action = Modifier.focusKey(RETRY_PAGE, memory),
                                )
                            }
                        }
                    } } }
                }
            }
        }
    }
}

/** The key of the wall's foot when the next page failed: never a title's id. */
private const val PAGE_FAILED = "page-failed"

/** Réessayer, at the foot of the wall. */
private val RETRY_PAGE = FocusKey.action("retry-page")

/** Back returns to the first poster from past the first line of the wall; from the first line, it leaves. */
internal fun backReturnsToFirst(focusedIndex: Int?): Boolean = focusedIndex != null && focusedIndex >= COLUMNS

/**
 * Follows the title [selectedId] names among [items], and shows it ([shown]) once focus settles:
 * the backdrop loads no image on every step across the wall. The selection is read here, so a step
 * recomposes this alone.
 */
@Composable
private fun ShowOnceSettled(selectedId: State<String?>, items: List<MediaItem>, shown: MutableState<MediaItem?>) {
    val id = selectedId.value
    val selected = remember(id, items) { items.find { it.id == id } ?: items.firstOrNull() }
    LaunchedEffect(selected) {
        if (shown.value != null) delay(HlMotion.SETTLE)
        shown.value = selected
    }
}

/** Back past the first line of the wall ([focusedIndex]) runs [toFirst]: read here, a step recomposes this alone. */
@Composable
private fun BackToFirstPoster(focusedIndex: State<Int?>, toFirst: suspend () -> Unit) {
    BackToStart(away = backReturnsToFirst(focusedIndex.value), toStart = toFirst)
}

/** Empty frames where the wall will be, laid out like it, once loading takes long enough to be seen. */
@Composable
private fun SkeletonWall() {
    if (!skeletonDelayPassed()) return
    Column(
        Modifier.padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.m),
        verticalArrangement = Arrangement.spacedBy(HlSpace.l),
    ) {
        repeat(SKELETON_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.l)) {
                repeat(COLUMNS) { CardSkeleton(null, 2f / 3f, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun sortLabel(sort: SortOption): String = stringResource(
    when (sort) {
        SortOption.TITLE -> R.string.sort_title
        SortOption.ADDED -> R.string.sort_added
        SortOption.YEAR -> R.string.sort_year
        SortOption.RATING -> R.string.sort_rating
    }
)

/** A sort as the menu offers it: what it sorts on, and in which order. */
@Composable
private fun sortOrder(sort: SortOption): String = stringResource(
    when (sort) {
        SortOption.TITLE -> R.string.sort_title_order
        SortOption.ADDED -> R.string.sort_added_order
        SortOption.YEAR -> R.string.sort_year_order
        SortOption.RATING -> R.string.sort_rating_order
    }
)

/** Filter menu: status, genre, decade and quality, combined; stays open while choosing. */
@Composable
private fun FilterButton(filter: LibraryFilter, facets: LibraryFacets, onChange: (LibraryFilter) -> Unit) {
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
        Quality.DOLBY_VISION to "Dolby Vision",
    )
    val decadeLabel = @Composable { d: Int -> stringResource(R.string.decade, d) }

    val sections = buildList {
        add(
            MenuSection(
                stringResource(R.string.filter_status),
                WatchStatus.entries.map { st -> MenuOption(statusLabels.getValue(st), filter.status == st) { onChange(filter.copy(status = st)) } },
            )
        )
        if (facets.genres.isNotEmpty()) add(
            MenuSection(
                stringResource(R.string.filter_genre),
                listOf(MenuOption(stringResource(R.string.all_genres), filter.genre == null) { onChange(filter.copy(genre = null)) }) +
                    facets.genres.map { g -> MenuOption(g, filter.genre == g) { onChange(filter.copy(genre = g)) } },
            )
        )
        if (facets.decades.isNotEmpty()) add(
            MenuSection(
                stringResource(R.string.filter_decade),
                listOf(MenuOption(stringResource(R.string.all_decades), filter.decade == null) { onChange(filter.copy(decade = null)) }) +
                    facets.decades.map { d -> MenuOption(decadeLabel(d), filter.decade == d) { onChange(filter.copy(decade = d)) } },
            )
        )
        if (facets.qualities.isNotEmpty()) add(
            MenuSection(
                stringResource(R.string.filter_quality),
                listOf(MenuOption(stringResource(R.string.all_qualities), filter.quality == null) { onChange(filter.copy(quality = null)) }) +
                    facets.qualities.map { q -> MenuOption(qualityLabels.getValue(q), filter.quality == q) { onChange(filter.copy(quality = q)) } },
            )
        )
        if (filter.activeCount > 0) add(
            MenuSection(null, listOf(MenuOption(stringResource(R.string.reset_filters), false) { onChange(LibraryFilter()) }))
        )
    }

    // The button names the active filter, or how many are combined.
    val detail = when (filter.activeCount) {
        0 -> null
        1 -> when {
            filter.status != WatchStatus.ALL -> statusLabels.getValue(filter.status)
            filter.genre != null -> filter.genre
            filter.decade != null -> decadeLabel(filter.decade)
            else -> filter.quality?.let { qualityLabels.getValue(it) }
        }
        else -> pluralStringResource(R.plurals.filter_count, filter.activeCount, filter.activeCount)
    }
    HlMenuButton(
        text = stringResource(R.string.filter),
        icon = HlIcons.Filter,
        sections = sections,
        detail = detail,
        // An active filter is a state, not the main action: its detail is lit, the button stays secondary.
        detailLit = filter.activeCount > 0,
        closeOnSelect = false,
    )
}
