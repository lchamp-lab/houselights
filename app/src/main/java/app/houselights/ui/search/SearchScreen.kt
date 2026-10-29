package app.houselights.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.houselights.R
import app.houselights.core.ArtworkKind
import app.houselights.core.MediaItem
import app.houselights.core.SearchResults
import app.houselights.core.hasPage
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.ListScroll
import app.houselights.ui.design.OnArtwork
import app.houselights.ui.design.RestoreFocus
import app.houselights.ui.design.components.AmbientBackdrop
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.DetailAhead
import app.houselights.ui.design.components.HlEmptyState
import app.houselights.ui.design.components.HlErrorState
import app.houselights.ui.design.components.LandscapeCard
import app.houselights.ui.design.components.LetterGrid
import app.houselights.ui.design.components.MediaRow
import app.houselights.ui.design.components.PersonCard
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.ResultName
import app.houselights.ui.design.components.ResultsHeading
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.components.SearchQuery
import app.houselights.ui.design.focusGroupKey
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.library.NotSaved
import app.houselights.ui.library.PosterActions
import app.houselights.ui.library.PosterOptions
import app.houselights.ui.library.actions
import app.houselights.ui.library.saidFor
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.rememberSettled
import app.houselights.ui.util.valueOrNull

/**
 * Library search, a place lying in the light of the result in focus as a wall does (`Diffuse`): its title, then
 * the query and an on-screen keyboard (not the system's) on the left, the results in rows on the right (titles,
 * sagas, people). Each typed word matches the start of a word, ignoring accents ("amel" finds Amélie). Returning
 * from a result refocuses it. Menu or a long OK on a title or a saga opens its options (#47, [PosterOptions]), whose
 * Modifier opens the choice of its pictures ([onEditArtwork]) and its match ([onIdentify]), as its page does.
 */
@Composable
fun SearchScreen(
    onOpenItem: (MediaItem) -> Unit,
    onOpenPerson: (String) -> Unit,
    modifier: Modifier = Modifier,
    onEditArtwork: (String, ArtworkKind) -> Unit = { _, _ -> },
    onIdentify: (String) -> Unit = {},
    vm: SearchViewModel = viewModel(factory = SearchViewModel.factory(appContainer())),
) {
    val query by vm.query.collectAsStateWithLifecycle()
    // Read only below and while settling, so new results recompose the results, never the keyboard.
    val found = vm.found.collectAsStateWithLifecycle()
    val firstKey = remember { FocusRequester() }
    val memory = rememberFocusMemory()
    RestoreFocus(memory, firstKey)
    // The title or saga last in focus lends the page its light while it is among the results; a person keeps it.
    val focusedId = remember { mutableStateOf<String?>(null) }
    val shown = rememberSettled { found.value?.results?.valueOrNull?.lit(focusedId.value) }
    val notSaved by vm.notSaved.collectAsStateWithLifecycle()
    // A result's options: Menu or a long OK on it.
    var options by remember { mutableStateOf<MediaItem?>(null) }
    val actions = remember(vm, onEditArtwork, onIdentify) { vm.changes.actions(vm::mark, vm::favorite, onEditArtwork, onIdentify) }
    val posters = remember(actions) { ResultPosters(actions) { options = it } }
    DetailAhead(shown.value)
    AmbientBackdrop(shown.value?.backdrop, BackdropStyle.Diffuse, modifier) {
        Column(Modifier.fillMaxSize().padding(top = HlSpace.safeV)) {
            ScreenTitle(stringResource(R.string.search), Modifier.padding(start = HlSpace.safeH))
            Spacer(Modifier.height(HlSpace.m))
            Row(Modifier.fillMaxSize()) {
                // The query is as wide as the keyboard below it; both lie on plates in the picture's dark.
                OnArtwork {
                    Column(Modifier.padding(start = HlSpace.safeH).width(IntrinsicSize.Max)) {
                        SearchQuery(query, stringResource(R.string.search_hint), Modifier.fillMaxWidth())
                        Spacer(Modifier.height(HlSpace.m))
                        LetterGrid(vm::type, vm::space, vm::delete, first = firstKey)
                    }
                }
                Results(
                    found,
                    shown,
                    { id ->
                        focusedId.value = id
                        vm.changes.focusedOn(id)
                    },
                    memory,
                    onOpenItem,
                    onOpenPerson,
                    vm::retry,
                    Modifier.weight(1f),
                    posters = posters,
                    notSaved = notSaved,
                )
            }
        }
        PosterOptions(options, actions, onDismiss = { options = null })
    }
}

/** What a title's or a saga's result opens with Menu or a long OK: its options ([actions], none without), by [onOptions]. */
@Immutable
internal class ResultPosters(val actions: PosterActions?, val onOptions: (MediaItem) -> Unit = {}) {
    /** Opens [item]'s options; null for a result without, which shows no key. */
    fun opens(item: MediaItem): (() -> Unit)? = { onOptions(item) }.takeIf { actions?.hasOptions(item) == true }
}

/** The title or saga [id] names among these results, if any. */
internal fun SearchResults.lit(id: String?): MediaItem? = id?.let { (titles + sagas).find { item -> item.id == it } }

/**
 * The results in rows, each title and saga row naming the one [shown] under it, a message that nothing matches,
 * or why the search failed. A title's or a saga's options are [posters]'; a change of theirs [notSaved] is said under
 * its row.
 */
@Composable
internal fun Results(
    found: State<Found?>,
    shown: State<MediaItem?>,
    onItemFocus: (String) -> Unit,
    memory: FocusMemory,
    onOpenItem: (MediaItem) -> Unit,
    onOpenPerson: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    posters: ResultPosters = ResultPosters(null),
    notSaved: NotSaved? = null,
) {
    val now = found.value ?: return
    val moreOptions = stringResource(R.string.more_options)
    val failed = now.results as? LoadState.Failed
    if (failed != null) {
        // The keys keep focus; Retry lies to their right.
        HlErrorState(failed.error, onRetry, modifier.padding(start = HlSpace.safeH), failed.retrying, focusOnAppear = false)
        return
    }
    val results = now.results.valueOrNull ?: return
    if (results.isEmpty()) {
        // An intertitle, as every message, on the rows' margin; the keys keep focus.
        HlEmptyState(
            stringResource(R.string.search_none, now.query),
            modifier.padding(start = HlSpace.safeH),
            focusOnAppear = false,
        )
        return
    }
    val lit = shown.value
    ListScroll(top = HlSpace.none) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = HlFocus.edge),
            verticalArrangement = Arrangement.spacedBy(HlSpace.xl),
        ) {
            if (results.titles.isNotEmpty()) {
                item(key = TITLES) {
                    Column {
                        val row = Modifier.focusGroupKey(FocusKey.row(TITLES), memory)
                        MediaRow(null, row, heading = { ResultsHeading(stringResource(R.string.search_titles), results.titles.size) }) {
                            items(results.titles, key = { it.id }) { title ->
                                PosterCard(
                                    title,
                                    onClick = { onOpenItem(title) },
                                    modifier = memory.result(TITLES, title.id),
                                    onFocus = { onItemFocus(title.id) },
                                    onLongClick = posters.opens(title),
                                    onLongClickLabel = moreOptions,
                                )
                            }
                        }
                        val named = lit?.takeIf { it in results.titles }
                        ResultName(named, Modifier.padding(start = HlSpace.safeH), notSaved = notSaved.saidFor(named?.id))
                    }
                }
            }
            if (results.sagas.isNotEmpty()) {
                item(key = SAGAS) {
                    Column {
                        val row = Modifier.focusGroupKey(FocusKey.row(SAGAS), memory)
                        MediaRow(null, row, heading = { ResultsHeading(stringResource(R.string.search_sagas), results.sagas.size) }) {
                            items(results.sagas, key = { it.id }) { saga ->
                                LandscapeCard(
                                    saga,
                                    onClick = { onOpenItem(saga) },
                                    modifier = memory.result(SAGAS, saga.id),
                                    onFocus = { onItemFocus(saga.id) },
                                    onLongClick = posters.opens(saga),
                                    onLongClickLabel = moreOptions,
                                )
                            }
                        }
                        val named = lit?.takeIf { it in results.sagas }
                        ResultName(named, Modifier.padding(start = HlSpace.safeH), notSaved = notSaved.saidFor(named?.id))
                    }
                }
            }
            if (results.people.isNotEmpty()) {
                item(key = PEOPLE) {
                    val row = Modifier.focusGroupKey(FocusKey.row(PEOPLE), memory)
                    MediaRow(
                        null,
                        row,
                        spacing = HlSpace.s,
                        heading = { ResultsHeading(stringResource(R.string.search_people), results.people.size) },
                    ) {
                        items(results.people, key = { it.id ?: it.name }) { person ->
                            PersonCard(
                                person,
                                memory.result(PEOPLE, person.id ?: person.name),
                                onOpen = person.id?.takeIf { person.hasPage }?.let { id -> { onOpenPerson(id) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Focus key of a result within its row: the same title can appear as a title and, as a saga, in another row. */
private fun FocusMemory.result(row: String, id: String) =
    Modifier.focusKey(FocusKey.within(FocusKey.row(row), FocusKey.item(id)), this)

private const val TITLES = "titles"
private const val PEOPLE = "people"
private const val SAGAS = "sagas"
