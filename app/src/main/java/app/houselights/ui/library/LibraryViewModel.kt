package app.houselights.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.houselights.AppContainer
import app.houselights.core.Library
import app.houselights.core.LibraryFacets
import app.houselights.core.LibraryFilter
import app.houselights.core.MediaItem
import app.houselights.core.SortOption
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryState(
    val items: List<MediaItem> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
    val sort: SortOption = SortOption.TITLE,
    val filter: LibraryFilter = LibraryFilter(),
    val facets: LibraryFacets = LibraryFacets(),
)

class LibraryViewModel(private val app: AppContainer, private val library: Library) : ViewModel() {
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state
    private var job: Job? = null

    init {
        reload()
        loadFacets()
        viewModelScope.launch {
            app.libraryChanged.collect {
                reload()
                loadFacets()
            }
        }
    }

    fun setSort(sort: SortOption) {
        if (sort == _state.value.sort) return
        _state.update { it.copy(sort = sort) }
        reload()
    }

    fun setFilter(filter: LibraryFilter) {
        if (filter == _state.value.filter) return
        _state.update { it.copy(filter = filter) }
        reload()
    }

    private fun loadFacets() {
        viewModelScope.launch {
            runCatching { app.provider.libraryFacets(library) }.onSuccess { f -> _state.update { it.copy(facets = f) } }
        }
    }

    private fun reload() {
        job?.cancel()
        _state.update { it.copy(items = emptyList(), total = 0, loading = true, error = null) }
        loadMore()
    }

    /** Loads the next page; called when focus gets close to the end of the grid. */
    fun loadMore() {
        val s = _state.value
        if (job?.isActive == true || (s.items.isNotEmpty() && s.items.size >= s.total)) return
        job = viewModelScope.launch {
            runCatching {
                app.provider.libraryPage(library, s.sort, s.filter, start = s.items.size, limit = PAGE)
            }.onSuccess { page ->
                _state.update { it.copy(items = it.items + page.items, total = page.total, loading = false) }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    companion object {
        private const val PAGE = 120
    }
}
