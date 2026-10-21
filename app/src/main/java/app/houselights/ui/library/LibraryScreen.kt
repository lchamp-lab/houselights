package app.houselights.ui.library

import app.houselights.ui.design.components.HlTitleSwitch
import app.houselights.ui.design.components.HeaderControls
import app.houselights.ui.design.OnArtwork
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.annotation.StringRes
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import app.houselights.ui.design.tryRequestFocus
import app.houselights.R
import app.houselights.core.Definition
import app.houselights.core.HdrFormat
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.BadgeTokens
import app.houselights.core.FileTypes
import app.houselights.core.Formats
import app.houselights.core.Person
import app.houselights.core.ProviderCapabilities
import app.houselights.core.LibraryFacets
import app.houselights.core.LibraryFilter
import app.houselights.core.Quality
import app.houselights.core.SortOption
import app.houselights.core.WatchStatus
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.TitleLine
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.AmbientBackdrop
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.components.HlMenuButton
import app.houselights.ui.design.components.MenuOption
import app.houselights.ui.design.components.MenuSection
import app.houselights.ui.design.components.LocalGroupFocused
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.components.CardSkeleton
import app.houselights.ui.design.components.HlEmptyState
import app.houselights.ui.design.components.HlErrorState
import app.houselights.ui.util.PartFailed
import app.houselights.ui.util.ReloadOnLibraryChange
import app.houselights.ui.util.retainedError
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.rememberSettled
import app.houselights.ui.util.skeletonDelayPassed
import kotlinx.coroutines.launch
import app.houselights.core.SortKeys
import app.houselights.ui.util.NBSP
import app.houselights.ui.util.libraryName
import app.houselights.ui.util.pictureName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import app.houselights.ui.design.components.WithItsPicture
import app.houselights.ui.design.components.DetailAhead

internal const val COLUMNS = 7

/** Placeholder rows while the wall loads: more than the screen shows, like the wall. */
private const val SKELETON_ROWS = 3

/** A poster wall, filtered by [opensOn] if given (a format, or person [personName]); films can switch to [sagas]. */
@Composable
fun LibraryScreen(
    library: Library,
    title: String,
    onOpenItem: (MediaItem) -> Unit,
    opensOn: LibraryFilter? = null,
    personName: String? = null,
    sagas: Library? = null,
) {
    val app = appContainer()
    var showSagas by rememberSaveable { mutableStateOf(false) }
    val wall = sagas?.takeIf { showSagas } ?: library
    val vm: LibraryViewModel = viewModel(
        key = "library-${wall.id}",
        factory = LibraryViewModel.factory(app, wall, opensOn.takeIf { wall == library }),
    )
    ReloadOnLibraryChange { vm.onLibraryChanged() }
    val state by vm.state.collectAsStateWithLifecycle()

    // Shown once focus settles, read outside composition: a step loads no image and recomposes nothing.
    val selectedId = rememberSaveable { mutableStateOf<String?>(null) }
    val shown = rememberSettled { selectedIn(state.items, selectedId.value) }
    val gridFocus = remember { FocusRequester() }
    var gridHasFocus by remember { mutableStateOf(false) }
    // Films and sagas each start at their top.
    val gridState = rememberSaveable(wall.id, saver = LazyGridState.Saver) { LazyGridState() }
    val scope = rememberCoroutineScope()
    // Focus returns to the last poster, else the first.
    val memory = rememberFocusMemory()
    RestoreFocus(memory, gridFocus, ready = state.items.isNotEmpty()) { key ->
        val index = FocusKey.itemId(key)?.let { id -> state.items.indexOfFirst { it.id == id } } ?: -1
        if (index >= 0) gridState.scrollToItem(index)
        index >= 0
    }
    // Set at every step and read only in BackToFirstPoster.
    val focusedIndex = remember { mutableStateOf<Int?>(null) }
    BackToFirstPoster(focusedIndex) {
        gridState.animateScrollToItem(0)
        gridFocus.tryRequestFocus()
    }
    // Where the failed page's foot was, so focus moves to its first poster once Réessayer loads it.
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

    // Prepares the settled title's detail page, so OK opens it complete.
    DetailAhead(shown.value)
    AmbientBackdrop(shown.value?.backdrop, BackdropStyle.Diffuse) {
        Column(Modifier.fillMaxSize()) {
            // The wall's name lit as the place, its count beside it; the menus on plates over the wall's picture.
            OnArtwork { HeaderControls { Row(
                Modifier.fillMaxWidth().padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.safeV),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                    // With sagas, ⇄ after the title turns the wall from the films to their sagas, and back.
                    if (sagas == null) {
                        ScreenTitle(title, Modifier.alignByBaseline())
                    } else {
                        val films = title
                        val other = libraryName(LibraryKind.COLLECTIONS)
                        HlTitleSwitch(
                            if (showSagas) other else films,
                            label = if (showSagas) films else other,
                            onSwitch = { showSagas = !showSagas },
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                    if (state.total > 0) {
                        val count = pluralStringResource(
                            if (wall.kind == LibraryKind.COLLECTIONS) R.plurals.collection_count else R.plurals.title_count,
                            state.total,
                            state.total,
                        )
                        WallFolio(count, state.items, shown, state.total, state.sort, Modifier.alignByBaseline())
                    }
                }
                Spacer(Modifier.weight(1f))
                // The person the wall was opened for, offered by the filter menu.
                val person = opensOn?.person?.let { id -> personName?.let { Person(it, null, null, id) } }
                WallMenus(wall.kind, state, vm.capabilities, person, vm::setFilter, vm::setSort)
            } } }
            // The selected title on one line; a long name drops its facts first, then ends in an ellipsis.
            Box(
                Modifier
                    .padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.xs)
                    .height(HlLayout.infoLine),
                contentAlignment = Alignment.CenterStart,
            ) {
                // Shown with its backdrop, as on Home.
                WithItsPicture(shown.value) { TitleLine(it) }
            }
            // While retrying, the error stays with its button busy.
            val wallError = retainedError(state.error?.takeIf { state.items.isEmpty() }, retrying = state.loading)
            when {
                state.items.isEmpty() && wallError != null -> HlErrorState(
                    wallError,
                    vm::retry,
                    Modifier.padding(start = HlSpace.safeH, top = HlSpace.l),
                    retrying = state.loading,
                )
                state.loading && state.items.isEmpty() -> SkeletonWall()
                state.items.isEmpty() && state.filter.activeCount > 0 -> HlEmptyState(
                    stringResource(R.string.no_match),
                    Modifier.padding(start = HlSpace.safeH, top = HlSpace.l),
                    action = stringResource(R.string.reset_filters),
                    actionIcon = HlIcons.Filter,
                    onAction = { vm.setFilter(LibraryFilter()) },
                )
                state.items.isEmpty() -> HlEmptyState(
                    stringResource(if (wall.kind == LibraryKind.COLLECTIONS) R.string.empty_collections else R.string.empty_title),
                    Modifier.padding(start = HlSpace.safeH, top = HlSpace.l),
                    backHint = true,
                )
                // A scrolled line of posters comes to where the first line rests.
                else -> CompositionLocalProvider(LocalGroupFocused provides gridHasFocus) {
                    ListScroll(top = HlFocus.wallRoom) { LazyVerticalGrid(
                    columns = GridCells.Fixed(COLUMNS),
                    state = gridState,
                    contentPadding = PaddingValues(
                        start = HlSpace.safeH, end = HlSpace.safeH, top = HlFocus.wallRoom, bottom = HlFocus.edge,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(HlLayout.wallGutter),
                    verticalArrangement = Arrangement.spacedBy(HlSpace.l),
                    modifier = Modifier.fillMaxSize().onFocusChanged {
                        gridHasFocus = it.hasFocus
                        // Leaving for the buttons above keeps the selected title.
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
                    // The next page's error and its fix, at the wall's foot.
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

/** Item key of the failed-page foot; never a title id. */
private const val PAGE_FAILED = "page-failed"

/** Réessayer, at the foot of the wall. */
private val RETRY_PAGE = FocusKey.action("retry-page")

/** Back returns to the first poster from past the first line; from the first line, it leaves. */
internal fun backReturnsToFirst(focusedIndex: Int?): Boolean = focusedIndex != null && focusedIndex >= COLUMNS

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
                repeat(COLUMNS) { CardSkeleton(null, 2f / 3f, Modifier.weight(1f)) }
            }
        }
    }
}

/** The header's menus: filters (not for sagas, which are browsed by name or date), then sort. */
@Composable
private fun WallMenus(
    kind: LibraryKind,
    state: LibraryState,
    can: ProviderCapabilities,
    person: Person?,
    onFilter: (LibraryFilter) -> Unit,
    onSort: (SortOption) -> Unit,
) {
    if (kind != LibraryKind.COLLECTIONS) FilterButton(state.filter, state.facets, can, person, onFilter)
    Spacer(Modifier.width(HlSpace.s))
    // "Trier" plus the current sort, a choice: "Titre" alone would read as a title.
    HlMenuButton(
        text = stringResource(R.string.sort),
        icon = HlIcons.Sort,
        detail = sortLabel(state.sort),
        detailLit = true,
        sections = listOf(
            MenuSection(null, SortOption.entries.map { MenuOption(sortOrder(it), state.sort == it) { onSort(it) } }),
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
    }
)

/** A sort as the menu offers it: its key and direction. */
@Composable
private fun sortOrder(sort: SortOption): String = stringResource(
    when (sort) {
        SortOption.TITLE -> R.string.sort_title_order
        SortOption.ADDED -> R.string.sort_added_order
        SortOption.YEAR -> R.string.sort_year_order
        SortOption.RATING -> R.string.sort_rating_order
    }
)

/**
 * Filter menu, combined filters, open while choosing. Sections offer the library's [facets] plus any active value,
 * so a filter set elsewhere can be cleared, and nothing the source cannot filter by ([can]).
 */
@Composable
internal fun FilterButton(
    filter: LibraryFilter,
    facets: LibraryFacets,
    can: ProviderCapabilities,
    person: Person?,
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
    val decadeLabels = (facets.decades + listOfNotNull(filter.decade)).associateWith { stringResource(R.string.decade, it) }
    val unnamed = stringResource(R.string.filter_person_unnamed)
    val personName = { id: String -> person?.takeIf { it.id == id }?.name ?: unnamed }
    // The files' picture, finer than their quality, where the source filters by it.
    val pictures = can.formatFilters && (facets.pictures.isNotEmpty() || filter.picture != null)

    val sections = listOfNotNull(
        MenuSection(
            stringResource(R.string.filter_status),
            WatchStatus.entries.map { st -> MenuOption(statusLabels.getValue(st), filter.status == st) { onChange(filter.copy(status = st)) } },
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
        MenuSection(null, listOf(MenuOption(stringResource(R.string.reset_filters), false) { onChange(LibraryFilter()) }))
            .takeIf { filter.activeCount > 0 },
    )

    // The button names the active filter, or how many are combined.
    val detail = when (filter.activeCount) {
        0 -> null
        1 -> activeName(filter, statusLabels, qualityLabels, personName)
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
    val folio = item?.takeIf { index >= COLUMNS * FOLIO_FROM_LINE }?.let { folioOf(it, sort) }
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

/** [item]'s key in a wall sorted by [sort]: initial, year, month added or score; null when it has none. */
internal fun folioOf(item: MediaItem, sort: SortOption, locale: Locale = Locale.getDefault()): String? = when (sort) {
    SortOption.TITLE -> SortKeys.initial(item.title)
    SortOption.YEAR -> item.year?.toString()
    SortOption.ADDED -> item.addedAt?.let { monthOf(it, locale) }
    SortOption.RATING -> item.communityRating?.let { String.format(locale, "%.1f", it) }
}

/** The month and year of [epochMs], as a heading writes them: "Octobre 2026". */
private fun monthOf(epochMs: Long, locale: Locale): String =
    SimpleDateFormat("LLLL yyyy", locale).format(Date(epochMs)).replaceFirstChar { it.titlecase(locale) }
