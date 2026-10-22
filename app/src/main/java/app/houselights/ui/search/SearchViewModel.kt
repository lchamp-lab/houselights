package app.houselights.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.core.MediaProvider
import app.houselights.core.SearchResults
import app.houselights.core.suspendRunCatching
import app.houselights.ui.util.UiError
import app.houselights.ui.util.reported
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** The results of a search for [query], or why it failed ([error]), kept while it is [retrying]. */
data class Found(val query: String, val results: SearchResults, val error: UiError? = null, val retrying: Boolean = false)

/**
 * Library search: [query] updates on each key; [found] follows [WAIT_MS] after the last key, null while the query
 * is empty, a running search being cancelled by the next key; [retry] runs a failed one again.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val provider: () -> MediaProvider) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val retries = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val found: StateFlow<Found?> = _query
        .debounce(WAIT_MS)
        .flatMapLatest<String, Found?> { typed ->
            val words = typed.trim()
            if (words.isEmpty()) flowOf(null) else flow {
                var found = search(words)
                emit(found)
                retries.collect {
                    if (found.error == null) return@collect
                    emit(found.copy(retrying = true))
                    found = search(words)
                    emit(found)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

    private suspend fun search(words: String): Found = suspendRunCatching { provider().catalog?.search(words) }.fold(
        { Found(words, it ?: SearchResults()) },
        { Found(words, SearchResults(), error = it.reported(TAG, "Search")) },
    )

    /** Runs the search that failed again, its failure shown meanwhile. */
    fun retry() {
        retries.tryEmit(Unit)
    }

    fun type(key: Char) = _query.update { it + key }

    /** Adds a space, unless the query is empty or already ends with one. */
    fun space() = _query.update { if (it.isEmpty() || it.endsWith(' ')) it else "$it " }

    fun delete() = _query.update { it.dropLast(1) }

    private inline fun MutableStateFlow<String>.update(change: (String) -> String) {
        value = change(value)
    }

    companion object {
        /** A search starts this long after the last key. */
        const val WAIT_MS = 150L

        private const val STOP_AFTER_MS = 5_000L
        private const val TAG = "Search"

        fun factory(app: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SearchViewModel(app::provider) }
        }
    }
}
