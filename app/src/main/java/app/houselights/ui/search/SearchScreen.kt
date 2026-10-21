package app.houselights.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.MediaItem
import app.houselights.core.hasPage
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.ListScroll
import app.houselights.ui.design.RestoreFocus
import app.houselights.ui.design.focusGroupKey
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.design.components.LetterGrid
import app.houselights.ui.design.components.MediaRow
import app.houselights.ui.design.components.PersonCard
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.components.SearchQuery
import app.houselights.ui.util.appContainer
import kotlinx.coroutines.flow.StateFlow

/**
 * Library search over the black room: its title, the query and an on-screen keyboard (not the system's) on
 * the left, results in rows on the right: titles, people, then sagas if any. Each typed word matches the
 * start of a word, ignoring accents ("amel" finds Amélie). Returning from a result refocuses it.
 */
@Composable
fun SearchScreen(onOpenItem: (MediaItem) -> Unit, onOpenPerson: (String) -> Unit) {
    val app = appContainer()
    val vm: SearchViewModel = viewModel(factory = SearchViewModel.factory(app))
    val query by vm.query.collectAsStateWithLifecycle()
    val firstKey = remember { FocusRequester() }
    val memory = rememberFocusMemory()
    RestoreFocus(memory, firstKey)
    Row(Modifier.fillMaxSize().padding(top = HlSpace.safeV)) {
        // The query is as wide as the keyboard below it.
        Column(Modifier.padding(start = HlSpace.safeH).width(IntrinsicSize.Max)) {
            ScreenTitle(stringResource(R.string.search))
            Spacer(Modifier.height(HlSpace.m))
            SearchQuery(query, stringResource(R.string.search_hint), Modifier.fillMaxWidth())
            Spacer(Modifier.height(HlSpace.m))
            LetterGrid(vm::type, vm::space, vm::delete, first = firstKey)
        }
        // Only Results reads the found state, so new results recompose this column, never the keyboard.
        Results(vm.found, memory, onOpenItem, onOpenPerson, Modifier.weight(1f))
    }
}

/** The results in rows, or a message that nothing matches the query. */
@Composable
private fun Results(
    found: StateFlow<Found?>,
    memory: FocusMemory,
    onOpenItem: (MediaItem) -> Unit,
    onOpenPerson: (String) -> Unit,
    modifier: Modifier,
) {
    val shown by found.collectAsStateWithLifecycle()
    val now = shown ?: return
    val results = now.results
    if (results.isEmpty()) {
        Text(
            stringResource(R.string.search_none, now.query),
            style = Hl.type.lead,
            color = Hl.colors.contentSecondary,
            modifier = modifier.padding(start = HlSpace.xl, top = HlSpace.xxl),
        )
        return
    }
    ListScroll(top = HlSpace.none) { LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = HlFocus.edge),
        verticalArrangement = Arrangement.spacedBy(HlSpace.l),
    ) {
        if (results.titles.isNotEmpty()) {
            item(key = TITLES) {
                MediaRow(stringResource(R.string.search_titles), Modifier.focusGroupKey(FocusKey.row(TITLES), memory)) {
                    items(results.titles, key = { it.id }) { title ->
                        PosterCard(title, onClick = { onOpenItem(title) }, modifier = memory.result(TITLES, title.id))
                    }
                }
            }
        }
        if (results.people.isNotEmpty()) {
            item(key = PEOPLE) {
                val row = Modifier.focusGroupKey(FocusKey.row(PEOPLE), memory)
                MediaRow(stringResource(R.string.search_people), row, spacing = HlSpace.s) {
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
        if (results.sagas.isNotEmpty()) {
            item(key = SAGAS) {
                MediaRow(stringResource(R.string.search_sagas), Modifier.focusGroupKey(FocusKey.row(SAGAS), memory)) {
                    items(results.sagas, key = { it.id }) { saga ->
                        PosterCard(saga, onClick = { onOpenItem(saga) }, modifier = memory.result(SAGAS, saga.id))
                    }
                }
            }
        }
    } }
}

/** Focus key of a result within its row: the same title can appear as a title and, as a saga, in another row. */
private fun FocusMemory.result(row: String, id: String) =
    Modifier.focusKey(FocusKey.within(FocusKey.row(row), FocusKey.item(id)), this)

private const val TITLES = "titles"
private const val PEOPLE = "people"
private const val SAGAS = "sagas"
