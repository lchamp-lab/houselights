package app.houselights.ui.library

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.core.Library
import app.houselights.core.LibraryFacets
import app.houselights.core.LibraryFilter
import app.houselights.core.MediaItem
import app.houselights.core.MediaProvider
import app.houselights.core.ProviderCapabilities
import app.houselights.core.Quality
import app.houselights.core.SortOption
import app.houselights.core.WatchStatus
import app.houselights.core.suspendRunCatching
import app.houselights.ui.util.LatestJob
import app.houselights.ui.util.UiError
import app.houselights.ui.util.reported
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class LibraryState(
    val items: List<MediaItem> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    val error: UiError? = null,
    val sort: SortOption = SortOption.TITLE,
    val filter: LibraryFilter = LibraryFilter(),
    val facets: LibraryFacets = LibraryFacets(),
    /** Why the next page could not load, shown at the foot of the wall; none loads by itself until [pageRetrying]. */
    val pageError: UiError? = null,
    val pageRetrying: Boolean = false,
)

/**
 * A library's wall, page by page, from the [provider] of the source in use. Its sort and filter are
 * kept in [saved]: Android may stop the app while a film plays, and the wall comes back as it was left.
 * Opened on part of the library ([opensOn]: a format of the library in numbers, a person), it starts
 * filtered by it, which it then keeps as any filter chosen.
 */
class LibraryViewModel(
    private val provider: () -> MediaProvider,
    private val library: Library,
    private val saved: SavedStateHandle = SavedStateHandle(),
    /** The first page, asked while home's pill slid here. */
    private val ahead: LibraryAhead? = null,
    opensOn: LibraryFilter? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(
        LibraryState(sort = saved.sort(), filter = saved.filter(opensOn?.within(provider().capabilities))),
    )
    val state: StateFlow<LibraryState> = _state

    /** What the source can filter by: the filter menu offers nothing else. */
    val capabilities: ProviderCapabilities get() = provider().capabilities

    /** The first page, a reload in place or the next page: one at a time, the latest asked for. */
    private val pages = LatestJob(viewModelScope)

    /** What the filter menu offers: the latest asked for, the library as it is now. */
    private val facetLoads = LatestJob(viewModelScope)

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
        saved[SORT] = sort.name
        _state.update { it.copy(sort = sort) }
        reset()
    }

    fun setFilter(filter: LibraryFilter) {
        if (filter == _state.value.filter) return
        saved.keep(filter)
        _state.update { it.copy(filter = filter) }
        reset()
    }

    private fun loadFacets() {
        facetLoads.launch {
            suspendRunCatching { provider().libraryFacets(library) }
                .onSuccess { f -> _state.update { it.copy(facets = f) } }
                // The filter menu keeps what it offered.
                .onFailure { Log.w(TAG, "Library facets failed", it) }
        }
    }

    /** A new sort or filter: start again from the first page. */
    private fun reset() {
        pages.cancel()
        _state.update { it.copy(items = emptyList(), total = 0, loading = true, error = null, pageError = null, pageRetrying = false) }
        loadMore()
    }

    /**
     * The library changed (a scan, a film watched): reload what is already shown, in one go and in place,
     * so the grid keeps its position and the focused poster.
     */
    private fun refresh() {
        val s = _state.value
        pages.launch {
            suspendRunCatching {
                provider().libraryPage(library, s.sort, s.filter, start = 0, limit = maxOf(s.items.size, PAGE))
            }.onSuccess { page ->
                val items = page.items.distinctBy(MediaItem::id)
                _state.update {
                    it.copy(items = items, total = page.total, loading = false, error = null, pageError = null, pageRetrying = false)
                }
            }.onFailure { e ->
                // Keep what is shown; the next change or page load tries again. A first page or a
                // retry this reload took over is not left at work: with nothing shown, the wall says
                // why; past it, the foot can try again.
                val error = e.reported(TAG, "Library refresh")
                _state.update {
                    it.copy(loading = false, pageRetrying = false, error = if (it.items.isEmpty()) error else it.error)
                }
            }
        }
    }

    /**
     * Loads the next page; called when focus gets close to the end of the grid. After a page failed, the
     * next one waits for [retryPage]: focus moving along the last lines does not try again and again.
     */
    fun loadMore() {
        if (_state.value.pageError == null) nextPage()
    }

    /** After the next page failed: tries it again. */
    fun retryPage() {
        if (_state.value.pageError == null || pages.isActive) return
        _state.update { it.copy(pageRetrying = true) }
        nextPage()
    }

    private fun nextPage() {
        val s = _state.value
        if (pages.isActive || (s.items.isNotEmpty() && s.items.size >= s.total)) return
        pages.launch {
            suspendRunCatching {
                val first = if (s.items.isEmpty()) ahead?.take(library, s.sort, s.filter) else null
                first ?: provider().libraryPage(library, s.sort, s.filter, start = s.items.size, limit = PAGE)
            }.onSuccess { page ->
                // A title added meanwhile can shift pages: never show one twice (the grid is keyed by id).
                _state.update {
                    it.copy(
                        items = (it.items + page.items).distinctBy(MediaItem::id),
                        total = page.total,
                        loading = false,
                        pageError = null,
                        pageRetrying = false,
                    )
                }
            }.onFailure { e ->
                val error = e.reported(TAG, "Library page")
                // With nothing shown, the wall says why; past the first page, its foot does.
                _state.update {
                    if (it.items.isEmpty()) {
                        it.copy(loading = false, error = error)
                    } else {
                        it.copy(loading = false, pageError = error, pageRetrying = false)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "Library"
        internal const val PAGE = 120

        fun factory(app: AppContainer, library: Library, opensOn: LibraryFilter? = null): ViewModelProvider.Factory = viewModelFactory {
            initializer { LibraryViewModel(app::provider, library, createSavedStateHandle(), app.libraryAhead, opensOn) }
        }
    }
}

// A wall's route holds what it opens on under the names of the last five, as the same plain values: its saved
// state starts with them, which [filter] reads only once [keep] has written them all.
private const val SORT = "sort"
private const val STATUS = "status"
private const val GENRE = "genre"
private const val DECADE = "decade"
private const val QUALITY = "quality"
private const val PICTURE = "picture"
private const val SOUND = "sound"
private const val FILE_TYPE = "fileType"
private const val PERSON = "person"

/** The sort kept, by name: a name no version knows any more is the default. */
private fun SavedStateHandle.sort(): SortOption =
    get<String>(SORT)?.let { name -> SortOption.entries.find { it.name == name } } ?: SortOption.TITLE

/**
 * The filter kept. A wall that has kept none yet starts from what it opens on ([opensOn]), kept from then on:
 * a wall brought back after a stopped process is as it was left, not as it was opened.
 */
private fun SavedStateHandle.filter(opensOn: LibraryFilter?): LibraryFilter {
    if (!contains(STATUS)) return (opensOn ?: LibraryFilter()).also { if (opensOn != null) keep(it) }
    return LibraryFilter(
        status = get<String>(STATUS)?.let { name -> WatchStatus.entries.find { it.name == name } } ?: WatchStatus.ALL,
        genre = get<String>(GENRE),
        decade = get<Int>(DECADE),
        quality = get<String>(QUALITY)?.let { name -> Quality.entries.find { it.name == name } },
        picture = get<String>(PICTURE),
        sound = get<String>(SOUND),
        fileType = get<String>(FILE_TYPE),
        person = get<String>(PERSON),
    )
}

/** Kept as plain values, which a saved state holds across a stopped process. */
private fun SavedStateHandle.keep(filter: LibraryFilter) {
    this[STATUS] = filter.status.name
    this[GENRE] = filter.genre
    this[DECADE] = filter.decade
    this[QUALITY] = filter.quality?.name
    this[PICTURE] = filter.picture
    this[SOUND] = filter.sound
    this[FILE_TYPE] = filter.fileType
    this[PERSON] = filter.person
}

/** [this] without what the source cannot filter by ([can]): a wall never starts on a filter its menu could not take off. */
private fun LibraryFilter.within(can: ProviderCapabilities) = copy(
    picture = picture.takeIf { can.formatFilters },
    sound = sound.takeIf { can.formatFilters },
    fileType = fileType.takeIf { can.formatFilters },
    person = person.takeIf { can.personFilter },
)
