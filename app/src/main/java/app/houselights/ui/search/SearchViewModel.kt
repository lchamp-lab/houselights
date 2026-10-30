package app.houselights.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.core.LibraryEvents
import app.houselights.core.MediaItem
import app.houselights.core.MediaProvider
import app.houselights.core.SearchResults
import app.houselights.ui.library.NotSaved
import app.houselights.ui.library.PosterChanges
import app.houselights.ui.library.Undone
import app.houselights.ui.library.favoredAs
import app.houselights.ui.library.markedAs
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.loadState
import app.houselights.ui.util.map
import app.houselights.ui.util.readingAgain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** The search for [query]: its [results], or why it failed, kept while it is retried. */
data class Found(val query: String, val results: LoadState<SearchResults>)

/**
 * Library search: [query] updates on each key; [found] follows [WAIT_MS] after the last key, null while the query
 * is empty, a running search being cancelled by the next key; [retry] runs a failed one again, [reload] any once the
 * library changed, the results shown meanwhile. The query is kept
 * in [saved], typed key by key on the remote, in case Android stops the app while a film plays. A result's options mark
 * a title or a saga, or make a title a favourite (#47), shown at once, saved in [work] and told to the other screens
 * ([events]).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val provider: () -> MediaProvider,
    // An empty handle for a test that gives none: the factory hands the real one.
    //noinspection VisibleForTests
    private val saved: SavedStateHandle = SavedStateHandle(),
    events: LibraryEvents? = null,
    /** Where a result's change is saved, outliving the search; its own scope by default. */
    work: CoroutineScope? = null,
) : ViewModel() {
    val query: StateFlow<String> = saved.getStateFlow(QUERY, "")

    private val retries = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val reloads = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The results as their options changed them, shown over the read of the search they were changed in. */
    private val marked = MutableStateFlow(Changed())

    /** The search's last read, as the source answered it, under what its results' options changed. */
    private var read: Found? = null

    val found: StateFlow<Found?> = query
        .debounce(WAIT_MS)
        .flatMapLatest<String, Found?> { typed ->
            val words = typed.trim()
            if (words.isEmpty()) {
                flowOf(null)
            } else {
                flow {
                    var found = search(words)
                    emit(found)
                    merge(retries.map { true }, reloads.map { false }).collect { retrying ->
                        if (retrying && found.results !is LoadState.Failed) return@collect
                        if (retrying) emit(found.copy(results = found.results.readingAgain()))
                        found = search(words)
                        emit(found)
                    }
                }
            }
        }
        .onEach { read = it }
        .combine(marked) { searched, changes -> searched?.let { changes.over(it) } }
        // Kept while the view model lives: the library changing is what reads it again ([reload]).
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private suspend fun search(words: String): Found =
        Found(words, loadState(TAG, "Search") { provider().catalog?.search(words) ?: SearchResults() })

    /** What a result's options change, saved as the title's page saves it. */
    internal val changes = PosterChanges(provider, events, work ?: viewModelScope, TAG)

    /** The last change a result's options asked that was not saved, said under its row while it has focus. */
    val notSaved: StateFlow<NotSaved?> get() = changes.notSaved

    /** Marks [item] watched, or not once it is, from its result's options: its mark shows at once, the result staying. */
    fun mark(item: MediaItem) {
        val played = !item.user.played
        change(item.markedAs(played)) { undo -> changes.mark(item, played, undo) }
    }

    /** Makes [item] a favourite, or not once it is, from its result's options, shown at once. */
    fun favorite(item: MediaItem) {
        val favorite = !item.user.favorite
        change(item.favoredAs(favorite)) { undo -> changes.favorite(item, favorite, undo) }
    }

    /**
     * Shows [changed] at once over the results read; then [save]s it, undone if not, that change alone: another of the
     * result still saving or saved keeps showing. Read again, as when the query is typed anew, the results are what the
     * source says.
     */
    private fun change(changed: MediaItem, save: (undo: (Undone) -> Unit) -> Unit) {
        val shown = read ?: return
        marked.update { it.with(shown, changed) }
        save { undone -> marked.update { it.undone(shown, undone) } }
    }

    /** Runs the search that failed again, its failure shown meanwhile. */
    fun retry() {
        retries.tryEmit(Unit)
    }

    /** Runs the search shown again, the library having changed, its results shown until the new ones come. */
    fun reload() {
        reloads.tryEmit(Unit)
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

        private const val TAG = "Search"
        private const val QUERY = "query"

        fun factory(app: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SearchViewModel(app::provider, createSavedStateHandle(), events = app, work = app.scope) }
        }
    }
}

/**
 * Titles and sagas as their options changed them ([items], by id), over the one read of a search they were changed in
 * ([read], that very answer): a read after it says what the source holds then. Each is its own, never equal to another,
 * so that every change shows.
 */
private class Changed(val read: Found? = null, val items: Map<String, MediaItem> = emptyMap()) {
    /** [changed] shown over [read], with those changed before over it. */
    fun with(read: Found, changed: MediaItem) = Changed(read, (if (read === this.read) items else emptyMap()) + (changed.id to changed))

    /** [undone]'s result shown over [read] with that change undone, as the source has it. */
    fun undone(read: Found, undone: Undone): Changed {
        val shown = items[undone.id]
        return if (read !== this.read || shown == null) this else Changed(read, items + (undone.id to undone.over(shown)))
    }

    /** [found] with what was changed over it, when it is the read they were changed over. */
    fun over(found: Found): Found = if (found !== read || items.isEmpty()) found else found.copy(results = found.results.map { shown(it) })

    private fun shown(results: SearchResults) = results.copy(
        titles = results.titles.map { items[it.id] ?: it },
        sagas = results.sagas.map { items[it.id] ?: it },
    )
}
