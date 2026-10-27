package app.houselights.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.core.MediaProvider
import app.houselights.core.SearchResults
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.loadState
import app.houselights.ui.util.readingAgain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** The search for [query]: its [results], or why it failed, kept while it is retried. */
data class Found(val query: String, val results: LoadState<SearchResults>)

/**
 * Library search: [query] updates on each key; [found] follows [WAIT_MS] after the last key, null while the query
 * is empty, a running search being cancelled by the next key; [retry] runs a failed one again. The query is kept
 * in [saved], typed key by key on the remote, in case Android stops the app while a film plays.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val provider: () -> MediaProvider,
    // An empty handle for a test that gives none: the factory hands the real one.
    //noinspection VisibleForTests
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    val query: StateFlow<String> = saved.getStateFlow(QUERY, "")

    private val retries = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val found: StateFlow<Found?> = query
        .debounce(WAIT_MS)
        .flatMapLatest<String, Found?> { typed ->
            val words = typed.trim()
            if (words.isEmpty()) flowOf(null) else flow {
                var found = search(words)
                emit(found)
                retries.collect {
                    if (found.results !is LoadState.Failed) return@collect
                    emit(found.copy(results = found.results.readingAgain()))
                    found = search(words)
                    emit(found)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

    private suspend fun search(words: String): Found =
        Found(words, loadState(TAG, "Search") { provider().catalog?.search(words) ?: SearchResults() })

    /** Runs the search that failed again, its failure shown meanwhile. */
    fun retry() {
        retries.tryEmit(Unit)
    }

    fun type(key: Char) = update { it + key }

    /** Adds a space, unless the query is empty or already ends with one. */
    fun space() = update { if (it.isEmpty() || it.endsWith(' ')) it else "$it " }

    fun delete() = update { it.dropLast(1) }

    private inline fun update(change: (String) -> String) {
        saved[QUERY] = change(query.value)
    }

    companion object {
        /** A search starts this long after the last key. */
        const val WAIT_MS = 150L

        private const val STOP_AFTER_MS = 5_000L
        private const val TAG = "Search"
        private const val QUERY = "query"

        fun factory(app: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SearchViewModel(app::provider, createSavedStateHandle()) }
        }
    }
}
