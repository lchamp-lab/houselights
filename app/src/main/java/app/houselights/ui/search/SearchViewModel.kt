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

/** What a search found for [query]: null while nothing is typed. */
data class Found(val query: String, val results: SearchResults)

/**
 * A search across the library, from the [provider] of the source in use: what is typed shows at
 * once ([query]); what it finds ([found]) comes [WAIT_MS] after the last key, a search still at work
 * given up when a key follows.
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

    /** A space between words: never two, nor one to start with. */
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
