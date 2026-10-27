package app.houselights.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.core.ArtworkChoices
import app.houselights.core.ArtworkKind
import app.houselights.core.ArtworkOption
import app.houselights.core.Identity
import app.houselights.core.MatchCandidate
import app.houselights.core.MediaItem
import app.houselights.core.MediaProvider
import app.houselights.core.MetadataEditor
import app.houselights.core.suspendRunCatching
import app.houselights.ui.util.LatestJob
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.UiError
import app.houselights.ui.util.errorOrNull
import app.houselights.ui.util.loadState
import app.houselights.ui.util.reloading
import app.houselights.ui.util.reported
import app.houselights.ui.util.toUiError
import app.houselights.ui.util.valueOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "Edit"

private fun MediaProvider.editorOrFail(): MetadataEditor = editor ?: error("This source cannot be edited")

/** A title and the choices for one piece of its artwork, loaded together. */
data class ArtworkPage(val item: MediaItem, val choices: ArtworkChoices)

data class ArtworkState(
    val page: LoadState<ArtworkPage> = LoadState.Loading,
    /** Why the shown choices could not be saved or reloaded; shown in their place until they load again. */
    val failure: UiError? = null,
    /** The choice being saved (its image fetched, then stored); the others wait. */
    val choosing: ArtworkOption? = null,
    /** The choice is saved ([choosing] stays set): the screen can close. */
    val done: Boolean = false,
) {
    val item: MediaItem? get() = page.valueOrNull?.item
    val choices: ArtworkChoices? get() = page.valueOrNull?.choices

    /** What replaces the choices: why they could not load, or why the chosen one could not be saved. */
    val error: UiError? get() = failure ?: page.errorOrNull

    /** Why TMDB's images are missing from the choices, when the request failed or could not be made. */
    val onlineError: UiError? get() = choices?.onlineError?.toUiError()
}

/** One artwork kind's choices; saving runs in [work], which outlives the screen, so Back never half-saves. */
class ArtworkViewModel(
    private val itemId: String,
    private val kind: ArtworkKind,
    private val provider: () -> MediaProvider,
    private val work: CoroutineScope,
) : ViewModel() {
    private val _state = MutableStateFlow(ArtworkState())
    val state: StateFlow<ArtworkState> = _state
    private val loads = LatestJob(viewModelScope)

    init {
        load()
    }

    /** Loads the title and its choices; on retry, only the latest load is shown. */
    fun load() {
        _state.update { it.copy(page = it.page.reloading(), failure = null) }
        loads.launch {
            val loaded = loadState(TAG, "Artwork ($kind) of $itemId") {
                val source = provider()
                val item = source.item(itemId)
                ArtworkPage(item, source.editorOrFail().artwork(item, kind))
            }
            _state.update { s ->
                // A page already shown stays if reloading fails; the reason replaces its choices.
                if (loaded is LoadState.Failed && s.page is LoadState.Ready) s.copy(failure = loaded.error) else s.copy(page = loaded)
            }
        }
    }

    /** Saves [option]; [ArtworkState.done] once saved. One at a time: another OK meanwhile does nothing. */
    fun choose(option: ArtworkOption) {
        val s = _state.value
        val item = s.item ?: return
        if (s.choosing != null || s.done) return
        _state.update { it.copy(choosing = option) }
        work.launch {
            suspendRunCatching { provider().editorOrFail().setArtwork(item, kind, option) }
                .onSuccess { _state.update { s -> s.copy(done = true) } }
                .onFailure { e ->
                    val error = e.reported(TAG, "Choosing the $kind of $itemId from ${option.origin}")
                    _state.update { it.copy(choosing = null, failure = error) }
                }
        }
    }

    companion object {
        fun factory(app: AppContainer, itemId: String, kind: ArtworkKind): ViewModelProvider.Factory = viewModelFactory {
            initializer { ArtworkViewModel(itemId, kind, app::provider, app.scope) }
        }
    }
}

data class IdentifyState(
    val item: MediaItem? = null,
    val identity: Identity? = null,
    val query: String = "",
    val year: String = "",
    val searching: Boolean = false,
    /** Null until a search has run. */
    val results: List<MatchCandidate>? = null,
    val identifying: Boolean = false,
    /** The result being matched while [identifying]; null when going back to automatic matching. */
    val chosenId: String? = null,
    /** The title is matched as chosen ([identifying] stays set): the screen can close. */
    val done: Boolean = false,
    val error: UiError? = null,
    /** « Laisser tel quel » asked, until [done]. */
    val keeping: Boolean = false,
    /** [results] are what automatic matching hesitated between, shown at once, no search asked. */
    val suggested: Boolean = false,
    /** In the queue, once [done]: the title to identify after this one; null after the last. */
    val nextId: String? = null,
    /** « Passer » asked, until [done]. */
    val skipping: Boolean = false,
) {
    /** Something is under way that a second OK must not start again. */
    val busy: Boolean get() = identifying || keeping || skipping || done
}

/**
 * Matches a title by hand. The match runs in [work], which outlives the screen, so a series is never left half
 * matched; what was typed is kept in [saved] in case Android kills the process. In the [queue] (opened from the titles
 * to identify), once done the next title opens ([IdentifyState.nextId]).
 */
class IdentifyViewModel(
    private val itemId: String,
    private val provider: () -> MediaProvider,
    private val work: CoroutineScope,
    // An empty handle for a test that gives none: the factory hands the real one.
    //noinspection VisibleForTests
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val queue: Boolean = false,
) : ViewModel() {
    private val _state = MutableStateFlow(IdentifyState())
    val state: StateFlow<IdentifyState> = _state
    private val searches = LatestJob(viewModelScope)
    private val loads = LatestJob(viewModelScope)

    init {
        load()
    }

    /**
     * Loads the title and its current match, then shows what automatic matching hesitated between, asking TMDB nothing,
     * or else runs the search automatic matching would make.
     */
    private fun load() {
        _state.update { it.copy(error = null) }
        loads.launch {
            suspendRunCatching {
                val source = provider()
                val item = source.item(itemId)
                item to source.editorOrFail().identity(item)
            }.onSuccess { (item, identity) ->
                // Text typed before the process was killed, else automatic matching's query.
                val query = saved[QUERY] ?: identity.query
                val year = saved[YEAR] ?: identity.year?.toString().orEmpty()
                _state.update { it.copy(item = item, identity = identity, query = query, year = year) }
                when {
                    // Its suggestions at once, the best first, unless a search was typed before the process died.
                    identity.suggestions.isNotEmpty() && saved.get<String>(QUERY) == null ->
                        _state.update { it.copy(results = identity.suggestions, suggested = true) }
                    // Run automatic matching's search, so the right title is often one press away.
                    identity.canSearch -> search()
                    else -> _state.update { it.copy(error = UiError.NO_TMDB_KEY) }
                }
            }.onFailure { e -> _state.update { it.copy(error = e.reported(TAG, "Identity of $itemId")) } }
        }
    }

    /** After a failure, reloads the title if it did not load, else searches again. */
    fun retry() = if (_state.value.item == null) load() else search()

    fun setQuery(query: String) {
        saved[QUERY] = query
        _state.update { it.copy(query = query) }
    }

    fun setYear(year: String) {
        val digits = year.filter(Char::isDigit).take(YEAR_DIGITS)
        saved[YEAR] = digits
        _state.update { it.copy(year = digits) }
    }

    fun search() {
        val s = _state.value
        val item = s.item ?: return
        _state.update { it.copy(searching = true, error = null) }
        searches.launch {
            suspendRunCatching { provider().editorOrFail().search(item, s.query, s.year.toIntOrNull()) }
                .onSuccess { results -> _state.update { it.copy(searching = false, results = results, suggested = false) } }
                .onFailure { e ->
                    val error = e.reported(TAG, "Search of $itemId for \"${s.query}\" ${s.year}")
                    _state.update { it.copy(searching = false, error = error) }
                }
        }
    }

    /** Matches the title with [candidate] (automatic for null), one at a time; [IdentifyState.done] once done. */
    fun identify(candidate: MatchCandidate?) {
        val s = _state.value
        val item = s.item ?: return
        if (s.busy) return
        _state.update { it.copy(identifying = true, chosenId = candidate?.id, error = null) }
        work.launch {
            suspendRunCatching { afterThis(item) { identify(item, candidate) } }
                // Still shown as matching while the screen closes.
                .onSuccess { next -> _state.update { s -> s.copy(done = true, nextId = next) } }
                .onFailure { e ->
                    val error = e.reported(TAG, "Identification of $itemId as ${candidate?.id ?: "automatic"}")
                    _state.update { it.copy(identifying = false, chosenId = null, error = error) }
                }
        }
    }

    /** « Laisser tel quel »: the title is no longer to identify, nor searched for by its name; the screen can close. */
    fun keep() {
        val s = _state.value
        val item = s.item ?: return
        if (s.busy) return
        _state.update { it.copy(keeping = true, error = null) }
        work.launch {
            suspendRunCatching { afterThis(item) { keepUnidentified(item) } }
                // Still busy while the screen closes.
                .onSuccess { next -> _state.update { s -> s.copy(done = true, nextId = next) } }
                .onFailure { e ->
                    val error = e.reported(TAG, "Leaving $itemId as it is")
                    _state.update { it.copy(keeping = false, error = error) }
                }
        }
    }

    /** « Passer », in the queue: the next title opens, this one as it is. */
    fun skip() {
        val s = _state.value
        val item = s.item ?: return
        if (s.busy) return
        _state.update { it.copy(skipping = true, error = null) }
        viewModelScope.launch {
            suspendRunCatching { provider().editorOrFail().nextToIdentify(item) }
                .onSuccess { next -> _state.update { s -> s.copy(done = true, nextId = next) } }
                .onFailure { e ->
                    val error = e.reported(TAG, "Title to identify after $itemId")
                    _state.update { it.copy(skipping = false, error = error) }
                }
        }
    }

    /** Runs [change] on [item]; in the queue, the title after it, read while it is still to identify; else null. */
    private suspend fun afterThis(item: MediaItem, change: suspend MetadataEditor.() -> Unit): String? {
        val editor = provider().editorOrFail()
        val next = if (queue) editor.nextToIdentify(item) else null
        editor.change()
        return next
    }

    companion object {
        private const val QUERY = "query"
        private const val YEAR = "year"
        private const val YEAR_DIGITS = 4

        fun factory(app: AppContainer, itemId: String, queue: Boolean = false): ViewModelProvider.Factory = viewModelFactory {
            initializer { IdentifyViewModel(itemId, app::provider, app.scope, createSavedStateHandle(), queue) }
        }
    }
}
