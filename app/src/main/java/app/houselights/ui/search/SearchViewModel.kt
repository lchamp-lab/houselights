package app.houselights.ui.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.core.MediaProvider
import app.houselights.core.SearchResults
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** The results of a search for [query]. */
data class Found(val query: String, val results: SearchResults)

/**
 * Library search: [query] updates on each key; [found] follows [WAIT_MS] after the last key, null while the query
 * is empty, a running search being cancelled by the next key.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val provider: () -> MediaProvider) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    val found: StateFlow<Found?> = _query
        .debounce(WAIT_MS)
        .mapLatest { typed ->
            val words = typed.trim()
            if (words.isEmpty()) {
                null
            } else {
                val results = suspendRunCatching { provider().catalog?.search(words) }
                    .onFailure { Log.w(TAG, "Search", it) }
                    .getOrNull()
                Found(words, results ?: SearchResults())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

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
