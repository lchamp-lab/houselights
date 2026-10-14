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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.ui.design.HlLayout
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
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.components.Backdrop
import app.houselights.ui.design.components.CollectionLight
import app.houselights.ui.design.components.BadgeRow
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.components.HlMenuButton
import app.houselights.ui.design.components.MenuOption
import app.houselights.ui.design.components.MenuSection
import app.houselights.ui.design.components.LocalGroupFocused
import app.houselights.ui.design.components.MetaLine
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.components.rememberAmbientColor
import app.houselights.ui.util.ReloadOnLibraryChange
import app.houselights.ui.util.appContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val COLUMNS = 7

/** A wall of posters, with the focused title named in the header. */
@Composable
fun LibraryScreen(
    library: Library,
    title: String,
    onOpenItem: (MediaItem) -> Unit,
) {
    val app = appContainer()
    val vm = viewModel(key = "library-${library.id}") { LibraryViewModel(app, library) }
    ReloadOnLibraryChange { vm.onLibraryChanged() }
    val state by vm.state.collectAsStateWithLifecycle()

    var focused by remember { mutableStateOf<MediaItem?>(null) }
    var shown by remember { mutableStateOf<MediaItem?>(null) }
    LaunchedEffect(focused) {
        delay(HlMotion.SETTLE)
        shown = focused
    }
    // A title filtered out of the grid must not stay named in the header.
    LaunchedEffect(state.items) {
        if (shown != null && state.items.none { it.id == shown?.id }) {
            focused = null
            shown = null
        }
    }
    val gridFocus = remember { FocusRequester() }
    var gridHasFocus by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    // The posters on the top row of the wall as it is shown, left to right.
    val wallPosters by remember {
        derivedStateOf {
            val first = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
            state.items.drop(first - first % COLUMNS).take(COLUMNS).mapNotNull { it.poster }
        }
    }
    val scope = rememberCoroutineScope()
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(state.items.isNotEmpty()) {
        if (state.items.isNotEmpty() && !focusedOnce) {
            focusedOnce = true
            runCatching { gridFocus.requestFocus() }
        }
    }

    CompositionLocalProvider(LocalAmbient provides rememberAmbientColor(shown?.backdrop)) {
        Box(Modifier.fillMaxSize()) {
            Backdrop(shown?.backdrop, BackdropStyle.Quiet)
            // Nothing selected: no film on the screen, the room takes the colours of the wall.
            CollectionLight(wallPosters, lit = shown == null)
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = HlSpace.safeH, end = HlSpace.safeH - HlSpace.m, top = HlSpace.safeV),
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
                            color = Hl.colors.contentTertiary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // Collections are browsed by name or date; filters belong to films.
                    if (library.kind != LibraryKind.COLLECTIONS) FilterButton(state.filter, state.facets, vm::setFilter)
                    Spacer(Modifier.width(HlSpace.s))
                    HlMenuButton(
                        text = sortLabel(state.sort),
                        icon = HlIcons.Sort,
                        sections = listOf(
                            MenuSection(
                                stringResource(R.string.sort_by),
                                SortOption.entries.map { opt -> MenuOption(sortLabel(opt), state.sort == opt) { vm.setSort(opt) } },
                            ),
                        ),
                    )
                }
                // What is under the cursor, without taking space from the wall. Only while a
                // poster has focus: on the filter or sort buttons, nothing is under the cursor.
                val infoAlpha by animateFloatAsState(
                    if (gridHasFocus && shown != null) 1f else 0f,
                    tween(HlMotion.FAST, easing = HlMotion.standard),
                    label = "info",
                )
                Row(
                    Modifier.padding(start = HlSpace.safeH, top = HlSpace.xs).height(HlLayout.infoLine).graphicsLayer { alpha = infoAlpha },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    shown?.let {
                        Text(it.title, style = Hl.type.entry, color = Hl.colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(HlSpace.m))
                        MetaLine(it, showGenres = false)
                        if (it.badges.isNotEmpty()) {
                            Spacer(Modifier.width(HlSpace.l))
                            BadgeRow(it.badges.take(4))
                        }
                    }
                }
                when {
                    state.loading && state.items.isEmpty() -> Unit
                    state.items.isEmpty() && state.filter.activeCount > 0 -> Column(Modifier.padding(HlSpace.safeH)) {
                        Text(stringResource(R.string.no_match), style = Hl.type.body, color = Hl.colors.contentSecondary)
                        Spacer(Modifier.height(HlSpace.l))
                        HlButton(stringResource(R.string.reset_filters), { vm.setFilter(LibraryFilter()) }, icon = HlIcons.Filter)
                    }
                    state.items.isEmpty() -> Text(
                        state.error?.let { stringResource(it.message) } ?: stringResource(R.string.empty),
                        style = Hl.type.body,
                        color = Hl.colors.contentSecondary,
                        modifier = Modifier.padding(HlSpace.safeH),
                    )
                    else -> CompositionLocalProvider(LocalGroupFocused provides gridHasFocus) { LazyVerticalGrid(
                        columns = GridCells.Fixed(COLUMNS),
                        state = gridState,
                        contentPadding = PaddingValues(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.m, bottom = HlSpace.xxl),
                        horizontalArrangement = Arrangement.spacedBy(HlSpace.l),
                        verticalArrangement = Arrangement.spacedBy(HlSpace.l),
                        modifier = Modifier.fillMaxSize().onFocusChanged {
                            gridHasFocus = it.hasFocus
                            // Leaving the wall leaves nothing selected; returning focuses a poster again.
                            if (!it.hasFocus) focused = null
                        },
                    ) {
                        itemsIndexed(state.items, key = { _, it -> it.id }) { index, item ->
                            PosterCard(
                                item = item,
                                onClick = { onOpenItem(item) },
                                width = null,
                                onFocused = {
                                    focused = item
                                    // The first line sits under the padding: show it all.
                                    if (index < COLUMNS) scope.launch { gridState.animateScrollToItem(0) }
                                    if (index > state.items.size - COLUMNS * 3) vm.loadMore()
                                },
                                modifier = if (index == 0) Modifier.focusRequester(gridFocus) else Modifier,
                            )
                        }
                    } }
                }
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
        emphasis = if (filter.activeCount > 0) Emphasis.Primary else Emphasis.Secondary,
        closeOnSelect = false,
    )
}
