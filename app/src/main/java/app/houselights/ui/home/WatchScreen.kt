package app.houselights.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.houselights.R
import app.houselights.core.ArtworkKind
import app.houselights.core.HomeSection
import app.houselights.core.MediaItem
import app.houselights.core.SectionType
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.FocusPlaces
import app.houselights.ui.design.FocusTakesPlace
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlGap
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.ListScroll
import app.houselights.ui.design.LocalHouseLights
import app.houselights.ui.design.RestoreFocus
import app.houselights.ui.design.components.AmbientBackdrop
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.CardSkeleton
import app.houselights.ui.design.components.DetailAhead
import app.houselights.ui.design.components.FilmsOfHeading
import app.houselights.ui.design.components.HeroInfo
import app.houselights.ui.design.components.LandscapeCard
import app.houselights.ui.design.components.MediaRow
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.components.wearsNewDot
import app.houselights.ui.design.focusGroupKey
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.houseLit
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.design.rememberRowState
import app.houselights.ui.library.NotSaved
import app.houselights.ui.library.PosterActions
import app.houselights.ui.library.PosterOptions
import app.houselights.ui.library.RefocusNotSaved
import app.houselights.ui.library.actions
import app.houselights.ui.library.saidFor
import app.houselights.ui.util.ReloadOnLibraryChange
import app.houselights.ui.util.rememberSettled
import app.houselights.ui.util.sectionTitle
import app.houselights.ui.util.skeletonDelayPassed
import app.houselights.ui.util.timeLeft

/**
 * « À regarder »: home's rows (Reprendre, À suivre, the latest titles, the library's own rows) under the title in
 * focus, read by home's [vm]; it opens on [row], and Back goes home, to the door it came from. A card's Modifier opens
 * the choice of its pictures ([onEditArtwork]) and its match ([onIdentify]), as its page does.
 */
@Composable
fun WatchScreen(
    vm: HomeViewModel,
    row: WatchRow,
    onOpenItem: (MediaItem) -> Unit,
    onEditArtwork: (String, ArtworkKind) -> Unit = { _, _ -> },
    onIdentify: (String) -> Unit = {},
) {
    // A title watched from here comes back with its progress, as home's rows did.
    ReloadOnLibraryChange(vm.libraryShown) { vm.onLibraryChanged() }
    val state by vm.state.collectAsStateWithLifecycle()
    val notSaved by vm.notSaved.collectAsStateWithLifecycle()
    val cards = remember(vm, onEditArtwork, onIdentify) {
        WatchCards(vm.changes.actions(vm::mark, vm::favorite, onEditArtwork, onIdentify), vm.changes::focusedOn)
    }
    WatchBands(state, row, onOpenItem, cards, notSaved)
}

/** What « À regarder »'s cards do beyond opening their title: their options ([actions], none without), a card focused. */
@Immutable
internal class WatchCards(val actions: PosterActions?, val onFocusTitle: (String) -> Unit = {})

/**
 * The page's title, the hero on the title in focus, then [state]'s rows, the first focus on [row]'s first card. Menu
 * or a long OK on a card opens its options (#47, [PosterOptions]), which [cards] do: a title they take out of its row
 * leaves it, focus going to the card taking its place, a row emptied going, focus on the one under it, else over it; a
 * change [notSaved] is said in the hero, its card focused again.
 */
@Composable
internal fun WatchBands(
    state: HomeState,
    row: WatchRow,
    onOpenItem: (MediaItem) -> Unit,
    cards: WatchCards = WatchCards(null),
    notSaved: NotSaved? = null,
) {
    val sections = (state.content as? HomeContent.Rows)?.sections.orEmpty()
    // The last selected title stays shown and survives navigation; default: the first of the row opened.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val opened = remember(sections.isEmpty()) { sectionOf(sections, row) }
    val shown by rememberSettled { selectedTitle(sections, selectedId ?: sections.getOrNull(opened)?.items?.firstOrNull()?.id) }

    val memory = rememberFocusMemory()
    // A first visit starts at the row asked for, at the top of the list, on its first card.
    val rows = rememberLazyListState(initialFirstVisibleItemIndex = opened)
    val start = sections.getOrNull(opened)
    RestoreFocus(
        memory,
        fallback = start?.items?.firstOrNull()?.let { memory.requester(cardKey(start, it)) },
        ready = sections.isNotEmpty(),
    ) { key ->
        val index = sectionIndex(sections, key)
        if (index != null) rows.scrollToItem(index)
        index != null
    }
    // A card leaving with focus leaves it to the one taking its place in its row; its row gone, to the row under it.
    val places = remember(sections) {
        sections.map { section -> FocusPlaces(FocusKey.section(section.key), section.items.map { cardKey(section, it) }) }
    }
    FocusTakesPlace(places, memory)
    // A change not saved puts its title back: focus goes back to its card, the page's cards being all it has, unless the
    // page was left before it was said.
    RefocusNotSaved(notSaved, memory) { id ->
        sections.firstNotNullOfOrNull { section -> section.items.find { it.id == id }?.let { cardKey(section, it) } }
    }
    // A card's options: Menu or a long OK on it.
    var options by remember { mutableStateOf<MediaItem?>(null) }
    val onOptions = remember { { item: MediaItem -> options = item } }
    // Prepares the settled title's detail page, so OK opens it complete.
    DetailAhead(shown)
    AmbientBackdrop(shown?.backdrop, BackdropStyle.Hero, ready = state.content !is HomeContent.Loading) {
        Column(Modifier.fillMaxSize().houseLit(LocalHouseLights.current)) {
            ScreenTitle(stringResource(R.string.watch_title), Modifier.padding(start = HlSpace.safeH, top = HlSpace.safeV))
            // A fixed height fitting the text at the viewer's text size, whatever has focus.
            HeroInfo(
                shown,
                Modifier.padding(start = HlSpace.safeH, top = HlGap.headToContent),
                notSaved = notSaved.saidFor(shown?.id),
            )
            Spacer(Modifier.height(HlGap.band))
            if (sections.isEmpty()) {
                SkeletonRows()
            } else {
                Sections(
                    sections,
                    memory,
                    rows,
                    onOpenItem,
                    onFocus = { item ->
                        selectedId = item.id
                        cards.onFocusTitle(item.id)
                    },
                    cards = cards,
                    onOptions = onOptions,
                )
            }
        }
        cards.actions?.let { actions -> PosterOptions(options, actions, onDismiss = { options = null }) }
    }
}

@Composable
private fun Sections(
    sections: List<HomeSection>,
    memory: FocusMemory,
    rows: LazyListState,
    onOpen: (MediaItem) -> Unit,
    onFocus: (item: MediaItem) -> Unit,
    cards: WatchCards,
    onOptions: (MediaItem) -> Unit,
) {
    val moreOptions = stringResource(R.string.more_options)
    // A row that must scroll comes to the list's top, where the first rests; the one above leaves whole.
    ListScroll(top = HlSpace.none, toTop = true) {
        LazyColumn(
            state = rows,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = HlFocus.edge),
            verticalArrangement = Arrangement.spacedBy(HlGap.band),
        ) {
            sections.forEach { section ->
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
                            val mod = Modifier.focusKey(cardKey(section, item), memory)
                            val focus = { onFocus(item) }
                            val open = { onOptions(item) }.takeIf { cards.actions?.hasOptions(item) == true }
                            if (section.landscape) {
                                // Named under the picture, as an episode is: a still does not say which film it is.
                                LandscapeCard(
                                    item,
                                    onClick = { onOpen(item) },
                                    modifier = mod,
                                    title = item.headTitle,
                                    facts = listOf(item.episodeLabel, timeLeft(item)),
                                    onFocus = focus,
                                    onLongClick = open,
                                    onLongClickLabel = moreOptions,
                                )
                            } else {
                                PosterCard(
                                    item,
                                    onClick = { onOpen(item) },
                                    modifier = mod,
                                    onFocus = focus,
                                    onLongClick = open,
                                    onLongClickLabel = moreOptions,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Placeholder rows in the real rows' footprint, shown once loading is slow. */
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

/** The row [row] opens the page on: its first of that kind (the latest: the first library's), else the first. */
internal fun sectionOf(sections: List<HomeSection>, row: WatchRow): Int {
    val type = when (row) {
        WatchRow.CONTINUE -> SectionType.CONTINUE
        WatchRow.NEXT_UP -> SectionType.NEXT_UP
        WatchRow.LATEST -> SectionType.LATEST
    }
    return sections.indexOfFirst { it.type == type }.coerceAtLeast(0)
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

/** The row holding what [key] names; null for anything else, or a row gone. */
internal fun sectionIndex(sections: List<HomeSection>, key: String): Int? {
    val row = FocusKey.groups(key).lastOrNull() ?: return null
    return sections.indexOfFirst { FocusKey.section(it.key) == row }.takeIf { it >= 0 }
}
