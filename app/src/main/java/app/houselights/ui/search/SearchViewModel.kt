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
import app.houselights.ui.library.Laid
import app.houselights.ui.library.NotSaved
import app.houselights.ui.library.PosterChanges
import app.houselights.ui.library.Undone
import app.houselights.ui.library.favoredAs
import app.houselights.ui.library.markedAs
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.loadState
import app.houselights.ui.util.map
import app.houselights.ui.util.readingAgain
import app.houselights.ui.util.valueOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
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

    /** How many times the search was asked to be read again ([reload]): one asked while it reads is kept for after it. */
    private val reloads = MutableStateFlow(0)

    /**
     * What a result's options change, saved as the title's page saves it, held over every read of the search until it
     * is read again ([PendingChanges][app.houselights.ui.library.PendingChanges]).
     */
    internal val changes = PosterChanges(provider, events, work ?: viewModelScope, TAG)

    /** The results as their options changed them, shown over the read of the search they were changed in. */
    private val marked = MutableStateFlow(Changed())

    /** The search's last read, as the source answered it with what its results' options hold over it. */
    @Volatile
    private var read: Found? = null

    val found: StateFlow<Found?> = query
        .debounce(WAIT_MS)
        // A space typed after a word, or erased at its end, leaves the words as they were: no search again.
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest<String, Found?> { words ->
            if (words.isEmpty()) {
                flowOf(null)
            } else {
                flow {
                    // A reload asked while the first read runs reads again after it, as one asked later does.
                    val asked = reloads.value
                    var found = search(words)
                    emit(found)
                    merge(retries.map { true }, reloads.dropWhile { it == asked }.map { false }).collect { retrying ->
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

    /** The search for [words], read now, what its results' options changed and the source may not say yet over it. */
    private suspend fun search(words: String): Found {
        val reading = changes.pending.reading()
        val results = loadState(TAG, "Search") { provider().catalog?.search(words) ?: SearchResults() }
        return Found(words, results.map { it.under(changes.pending.over(reading)) })
    }

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
     * Shows [changed] at once over the results read; then [save]s it, undone if not, that change alone, over the
     * results then shown, read again meanwhile or not: another of the result still saving or saved keeps showing. Read
     * again, the results are what the source says, with the changes it may not say yet ([search]).
     */
    private fun change(changed: MediaItem, save: (undo: (Undone) -> Unit) -> Unit) {
        val shown = read ?: return
        marked.update { it.with(shown, changed) }
        save { undone -> read?.let { now -> marked.update { it.undone(now, undone) } } }
    }

    /** Runs the search that failed again, its failure shown meanwhile. */
    fun retry() {
        retries.tryEmit(Unit)
    }

    /** Runs the search shown again, the library having changed, its results shown until the new ones come. */
    fun reload() {
        reloads.update { it + 1 }
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
 * ([read], that very answer): a read after it lays what is held of them itself ([SearchResults.under]). Each is its
 * own, never equal to another, so that every change shows.
 */
private class Changed(val read: Found? = null, val items: Map<String, MediaItem> = emptyMap()) {
    /** [changed] shown over [read], with those changed before over it. */
    fun with(read: Found, changed: MediaItem) = Changed(read, (if (read === this.read) items else emptyMap()) + (changed.id to changed))

    /**
     * [undone]'s result shown over [read], the results now shown, with that change undone, as the source has it: over
     * what was changed of it there, or over the result as [read] holds it, read again since the change.
     */
    fun undone(read: Found, undone: Undone): Changed {
        val kept = if (read === this.read) items else emptyMap()
        val shown = kept[undone.id] ?: read.results.valueOrNull?.let { it.titles + it.sagas }?.firstOrNull { it.id == undone.id }
        return if (shown == null) this else Changed(read, kept + (undone.id to undone.over(shown)))
    }

    /** [found] with what was changed over it, when it is the read they were changed over. */
    fun over(found: Found): Found = if (found !== read || items.isEmpty()) found else found.copy(results = found.results.map { shown(it) })

    private fun shown(results: SearchResults) = results.copy(
        titles = results.titles.map { items[it.id] ?: it },
        sagas = results.sagas.map { items[it.id] ?: it },
    )
}

/** [this], as a read answered it, showing the changes its results' options asked that it may not know ([laid]). */
private fun SearchResults.under(laid: List<Laid>): SearchResults {
    if (laid.isEmpty()) return this
    val held = laid.associateBy { it.item.id }
    return copy(titles = titles.map { held[it.id]?.over(it) ?: it }, sagas = sagas.map { held[it.id]?.over(it) ?: it })
}
