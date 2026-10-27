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
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaProvider
import app.houselights.core.Page
import app.houselights.core.ProviderCapabilities
import app.houselights.core.Quality
import app.houselights.core.SortOption
import app.houselights.core.WatchStatus
import app.houselights.core.sagas
import app.houselights.core.suspendRunCatching
import app.houselights.ui.util.LatestJob
import app.houselights.ui.util.UiError
import app.houselights.ui.util.reported
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What a films wall with sagas shows, chosen in its filter menu. */
enum class WallView {
    /** The films, each saga's as one poster in their place. */
    FILMS_AND_SAGAS,

    /** The sagas alone. */
    SAGAS,
}

data class LibraryState(
    val items: List<MediaItem> = emptyList(),
    val total: Int = 0,
    /** Of [total], the sagas, when the source counted them. */
    val sagas: Int? = null,
    val loading: Boolean = true,
    val error: UiError? = null,
    val sort: SortOption = SortOption.TITLE,
    val filter: LibraryFilter = LibraryFilter(),
    val facets: LibraryFacets = LibraryFacets(),
    /** What a films wall with sagas shows; null for any other wall, which has nothing to choose. */
    val view: WallView? = null,
    /** Why the next page could not load, shown at the wall's foot; no page loads by itself until [pageRetrying]. */
    val pageError: UiError? = null,
    val pageRetrying: Boolean = false,
) {
    /** The filters the wall shows: none on sagas a source cannot filter ([filtersApply]). */
    fun applied(can: ProviderCapabilities): LibraryFilter = if (filtersApply(view, can)) filter else LibraryFilter()
}

/** Whether a wall showing [view] is filtered: the sagas alone only where the source filters them by their films. */
internal fun filtersApply(view: WallView?, can: ProviderCapabilities): Boolean = view != WallView.SAGAS || can.sagaFilters

/**
 * A library's wall, page by page. Sort, filter and view are kept in [saved], as Android may kill the app during
 * playback. Opened on part of the library ([opensOn]: a format, a person), it starts filtered by it and shows every
 * film; else a films wall shows each saga of the source's [sagas] as one title, or the sagas alone ([setView]), as
 * long as the source shows sagas.
 */
class LibraryViewModel(
    /** The source's provider; null while there is none (its connection could not be read back), and nothing loads. */
    private val provider: () -> MediaProvider?,
    private val library: Library,
    // An empty handle for a test that gives none: the factory hands the real one.
    //noinspection VisibleForTests
    private val saved: SavedStateHandle = SavedStateHandle(),
    /** The first page, prefetched as its door on home was chosen. */
    private val ahead: LibraryAhead? = null,
    opensOn: LibraryFilter? = null,
    sagas: Library? = null,
) : ViewModel() {
    /** Whether this wall shows the source's sagas among its films: a films wall of the whole library. */
    private val followsSagas = opensOn == null && library.kind == LibraryKind.MOVIES

    /** The sagas this wall groups its films into, and shows alone; followed as the Sagas setting changes. */
    private var sagas = sagas.takeIf { followsSagas }

    private val _state = MutableStateFlow(
        LibraryState(
            sort = saved.sort(),
            filter = saved.filter(opensOn?.within(capabilities)),
            view = if (this.sagas == null) null else saved.view(),
        ),
    )
    val state: StateFlow<LibraryState> = _state

    /** What the source can filter by: the filter menu offers nothing else; nothing without a source. */
    val capabilities: ProviderCapabilities get() = provider()?.capabilities ?: ProviderCapabilities.NONE

    /** The provider a load reads; without a source the load fails, as the wall gives way to the welcome screen. */
    private fun source(): MediaProvider = checkNotNull(provider()) { "No library source configured" }

    /** The first page, an in-place reload or the next page: one at a time, a new one cancelling the previous. */
    private val pages = LatestJob(viewModelScope)

    /** Loads what the filter menu offers, from the library as it is now; a new load cancels the previous. */
    private val facetLoads = LatestJob(viewModelScope)

    /** Reads whether the source still shows sagas before reloading; a new read cancels the previous. */
    private val sagaReads = LatestJob(viewModelScope)

    init {
        reset()
        loadFacets()
    }

    /**
     * Shows what the library holds after a change (a scan, a film watched, the Sagas setting), without losing the
     * place; a running scan's progress ([scanOnly]) reloads the titles alone, the filters and sagas waiting for its end.
     */
    fun onLibraryChanged(scanOnly: Boolean = false) {
        if (scanOnly) return refresh()
        if (followsSagas) followSagas() else refresh()
        loadFacets()
    }

    /**
     * Reloads in place, unless the source no longer shows the wall's sagas, or shows some again: then the wall starts
     * over, its films alone with nothing to choose, or with their sagas among them. A failed read keeps the sagas.
     */
    private fun followSagas() {
        sagaReads.launch {
            val now = suspendRunCatching { source().libraries().sagas() }
                .getOrElse { sagas }
            if (now?.id == sagas?.id) {
                refresh()
            } else {
                sagas = now
                // Hidden, the sagas alone are forgotten: shown again, the wall opens on the films and their sagas.
                if (now == null) saved.remove<String>(VIEW)
                _state.update { it.copy(view = now?.let { saved.view() }) }
                reset()
            }
        }
    }

    /** After a failure, loads again from the first page. */
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

    /** Shows the films and their sagas, or the sagas alone, with the same filters; only a wall with sagas has a view. */
    fun setView(view: WallView) {
        val shown = _state.value.view
        if (shown == null || view == shown) return
        saved[VIEW] = view.name
        _state.update { it.copy(view = view) }
        reset()
    }

    /** Back to the whole wall: no filter, and the films with their sagas. */
    fun clearFilters() {
        val s = _state.value
        val view = s.view?.let { WallView.FILMS_AND_SAGAS }
        if (s.filter == LibraryFilter() && s.view == view) return
        saved.keep(LibraryFilter())
        view?.let { saved[VIEW] = it.name }
        _state.update { it.copy(filter = LibraryFilter(), view = view) }
        reset()
    }

    /** The library a page of [s] is asked of, its filter, and whether its sagas are grouped. */
    private fun asked(s: LibraryState): Asked = when (s.view) {
        WallView.SAGAS -> Asked(checkNotNull(sagas), s.applied(capabilities), grouped = false)
        WallView.FILMS_AND_SAGAS -> Asked(library, s.filter, grouped = true)
        null -> Asked(library, s.filter, grouped = false)
    }

    private class Asked(val library: Library, val filter: LibraryFilter, val grouped: Boolean)

    private suspend fun MediaProvider.page(s: LibraryState, start: Int, limit: Int): Page =
        asked(s).let { libraryPage(it.library, s.sort, it.filter, start, limit, it.grouped) }

    private fun loadFacets() {
        facetLoads.launch {
            suspendRunCatching { source().libraryFacets(library) }
                .onSuccess { f -> _state.update { it.copy(facets = f) } }
                // On failure, the filter menu keeps what it offered.
                .onFailure { Log.w(TAG, "Library facets failed", it) }
        }
    }

    private fun reset() {
        pages.cancel()
        _state.update {
            it.copy(items = emptyList(), total = 0, sagas = null, loading = true, error = null, pageError = null, pageRetrying = false)
        }
        loadMore()
    }

    /** Reloads what is shown in one request, in place, so the grid keeps its position and focused poster. */
    private fun refresh() {
        val s = _state.value
        pages.launch {
            suspendRunCatching {
                source().page(s, start = 0, limit = maxOf(s.items.size, PAGE))
            }.onSuccess { page ->
                val items = page.items.distinctBy(MediaItem::id)
                _state.update {
                    it.copy(
                        items = items, total = page.total, sagas = page.sagas, loading = false, error = null, pageError = null,
                        pageRetrying = false,
                    )
                }
            }.onFailure { e ->
                // Keep what is shown; clear any busy state this reload superseded; report only if nothing shows.
                val error = e.reported(TAG, "Library refresh")
                _state.update {
                    it.copy(loading = false, pageRetrying = false, error = if (it.items.isEmpty()) error else it.error)
                }
            }
        }
    }

    /** Loads the next page as focus nears the grid's end; after a failure, only [retryPage] tries again. */
    fun loadMore() {
        if (_state.value.pageError == null) nextPage()
    }

    /** Retries the next page after it failed. */
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
                val first = if (s.items.isEmpty()) asked(s).let { ahead?.take(it.library, s.sort, it.filter, it.grouped) } else null
                first ?: source().page(s, start = s.items.size, limit = PAGE)
            }.onSuccess { page ->
                // A title added meanwhile can shift pages: never show one twice (the grid is keyed by id).
                _state.update {
                    it.copy(
                        items = (it.items + page.items).distinctBy(MediaItem::id),
                        total = page.total,
                        sagas = page.sagas,
                        loading = false,
                        pageError = null,
                        pageRetrying = false,
                    )
                }
            }.onFailure { e ->
                val error = e.reported(TAG, "Library page")
                // With nothing shown, the wall explains the failure; past the first page, its foot does.
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

        fun factory(app: AppContainer, library: Library, opensOn: LibraryFilter?, sagas: Library?): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { LibraryViewModel(app::providerOrNull, library, createSavedStateHandle(), app.libraryAhead, opensOn, sagas) }
            }
    }
}

/** Whether [library]'s wall shows its films' [sagas] among them: a films wall, of a source with sagas. */
internal fun groups(library: Library, sagas: Library?): Boolean = sagas != null && library.kind == LibraryKind.MOVIES

// A wall's route passes what it opens on under the last five keys, as the same plain values, so its saved
// state starts with them; [filter] reads the saved state only once [keep] has written every key.
private const val SORT = "sort"
private const val VIEW = "view"
private const val STATUS = "status"
private const val GENRE = "genre"
private const val DECADE = "decade"
private const val QUALITY = "quality"
private const val PICTURE = "picture"
private const val SOUND = "sound"
private const val FILE_TYPE = "fileType"
private const val PERSON = "person"

/** The kept sort, by name; an unknown name falls back to the default. */
private fun SavedStateHandle.sort(): SortOption =
    get<String>(SORT)?.let { name -> SortOption.entries.find { it.name == name } } ?: SortOption.TITLE

/** The kept view, by name; films and sagas by default. */
private fun SavedStateHandle.view(): WallView =
    get<String>(VIEW)?.let { name -> WallView.entries.find { it.name == name } } ?: WallView.FILMS_AND_SAGAS

/** The kept filter, else [opensOn], kept at once so a restored wall is as it was left, not as it was opened. */
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

/** Keeps [filter] as plain values, which saved state holds across a killed process. */
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

/** [this] without what the source cannot filter by ([can]), so the menu can always clear the opening filter. */
private fun LibraryFilter.within(can: ProviderCapabilities) = copy(
    picture = picture.takeIf { can.formatFilters },
    sound = sound.takeIf { can.formatFilters },
    fileType = fileType.takeIf { can.formatFilters },
    person = person.takeIf { can.personFilter },
)
