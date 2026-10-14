package app.houselights.ui.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.houselights.AppContainer
import app.houselights.core.Library
import app.houselights.core.LibraryFacets
import app.houselights.core.LibraryFilter
import app.houselights.core.MediaItem
import app.houselights.core.SortOption
import app.houselights.core.suspendRunCatching
import app.houselights.ui.util.UiError
import app.houselights.ui.util.toUiError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryState(
    val items: List<MediaItem> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    val error: UiError? = null,
    val sort: SortOption = SortOption.TITLE,
    val filter: LibraryFilter = LibraryFilter(),
    val facets: LibraryFacets = LibraryFacets(),
)

class LibraryViewModel(private val app: AppContainer, private val library: Library) : ViewModel() {
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state
    private var job: Job? = null

    init {
        reset()
        loadFacets()
    }

    /** The library changed (a scan, a film watched): show what it holds now, without losing the place. */
    fun onLibraryChanged() {
        refresh()
        loadFacets()
    }

    /** After a failure: load again from the first page. */
    fun retry() = reset()

    fun setSort(sort: SortOption) {
        if (sort == _state.value.sort) return
        _state.update { it.copy(sort = sort) }
        reset()
    }

    fun setFilter(filter: LibraryFilter) {
        if (filter == _state.value.filter) return
        _state.update { it.copy(filter = filter) }
        reset()
    }

    private fun loadFacets() {
        viewModelScope.launch {
            suspendRunCatching { app.provider.libraryFacets(library) }.onSuccess { f -> _state.update { it.copy(facets = f) } }
        }
    }

    /** A new sort or filter: start again from the first page. */
    private fun reset() {
        job?.cancel()
        _state.update { it.copy(items = emptyList(), total = 0, loading = true, error = null) }
        loadMore()
    }

    /**
     * The library changed (a scan, a film watched): reload what is already shown, in one go and in place,
     * so the grid keeps its position and the focused poster.
     */
    private fun refresh() {
        job?.cancel()
        val s = _state.value
        job = viewModelScope.launch {
            suspendRunCatching {
                app.provider.libraryPage(library, s.sort, s.filter, start = 0, limit = maxOf(s.items.size, PAGE))
            }.onSuccess { page ->
                _state.update { it.copy(items = page.items.distinctBy(MediaItem::id), total = page.total, loading = false, error = null) }
            }.onFailure { e ->
                // Keep what is shown; the next change or page load tries again.
                Log.w(TAG, "Library refresh failed", e)
            }
        }
    }

    /** Loads the next page; called when focus gets close to the end of the grid. */
    fun loadMore() {
        val s = _state.value
        if (job?.isActive == true || (s.items.isNotEmpty() && s.items.size >= s.total)) return
        job = viewModelScope.launch {
            suspendRunCatching {
                app.provider.libraryPage(library, s.sort, s.filter, start = s.items.size, limit = PAGE)
            }.onSuccess { page ->
                // A title added meanwhile can shift pages: never show one twice (the grid is keyed by id).
                _state.update { it.copy(items = (it.items + page.items).distinctBy(MediaItem::id), total = page.total, loading = false) }
            }.onFailure { e ->
                Log.w(TAG, "Library page failed", e)
                _state.update { it.copy(loading = false, error = e.toUiError()) }
            }
        }
    }

    companion object {
        private const val TAG = "Library"
        private const val PAGE = 120
    }
}
