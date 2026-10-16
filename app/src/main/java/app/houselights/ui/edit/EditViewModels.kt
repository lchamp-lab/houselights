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
import app.houselights.ui.util.valueOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "Edit"

private fun MediaProvider.editorOrFail(): MetadataEditor = editor ?: error("This source cannot be edited")

/** A title, and the choices for one piece of its artwork: loaded together. */
data class ArtworkPage(val item: MediaItem, val choices: ArtworkChoices)

data class ArtworkState(
    val page: LoadState<ArtworkPage> = LoadState.Loading,
    /**
     * Why the choices shown could not be saved, or loaded again: said in place of them, the title
     * kept, until they load again.
     */
    val failure: UiError? = null,
    /** The choice being saved (its image fetched, then kept); the others wait. */
    val choosing: ArtworkOption? = null,
    /** The choice is saved ([choosing] stays): the screen can close. */
    val done: Boolean = false,
) {
    val item: MediaItem? get() = page.valueOrNull?.item
    val choices: ArtworkChoices? get() = page.valueOrNull?.choices

    /** What takes the place of the choices: why they could not load, or the one chosen be saved. */
    val error: UiError? get() = failure ?: page.errorOrNull
}

/**
 * The choices for one piece of a title's artwork, from the [provider] of the source in use. The choice
 * itself is saved in [work], which outlives the screen: Back while its image is fetched never leaves
 * it half saved.
 */
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

    /** Loads the title and its choices; tried again, the latest load is the one shown. */
    fun load() {
        _state.update { it.copy(page = it.page.reloading(), failure = null) }
        loads.launch {
            val loaded = loadState(TAG, "Artwork ($kind) of $itemId") {
                val source = provider()
                val item = source.item(itemId)
                ArtworkPage(item, source.editorOrFail().artwork(item, kind))
            }
            _state.update { s ->
                // A page shown stays when it could not load again: why is said in place of its choices.
                if (loaded is LoadState.Failed && s.page is LoadState.Ready) s.copy(failure = loaded.error) else s.copy(page = loaded)
            }
        }
    }

    /** Saves [option]; [ArtworkState.done] once saved. One at a time: another OK meanwhile changes nothing. */
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
    /** The title is matched as chosen ([identifying] stays on): the screen can close. */
    val done: Boolean = false,
    val error: UiError? = null,
)

/**
 * Matching a title by hand, from the [provider] of the source in use. The match itself runs in [work],
 * which outlives the screen: leaving while a series is matched never leaves it half done. What was
 * typed is kept in [saved], for a process Android stopped meanwhile.
 */
class IdentifyViewModel(
    private val itemId: String,
    private val provider: () -> MediaProvider,
    private val work: CoroutineScope,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val _state = MutableStateFlow(IdentifyState())
    val state: StateFlow<IdentifyState> = _state
    private val searches = LatestJob(viewModelScope)
    private val loads = LatestJob(viewModelScope)

    init {
        load()
    }

    /** The title and how it is matched now, then the search automatic matching would make. */
    private fun load() {
        _state.update { it.copy(error = null) }
        loads.launch {
            suspendRunCatching {
                val source = provider()
                val item = source.item(itemId)
                item to source.editorOrFail().identity(item)
            }.onSuccess { (item, identity) ->
                // What was typed before the process was stopped, else what automatic matching searched.
                val query = saved[QUERY] ?: identity.query
                val year = saved[YEAR] ?: identity.year?.toString().orEmpty()
                _state.update { it.copy(item = item, identity = identity, query = query, year = year) }
                // What automatic matching would search, so the right title is often one press away.
                if (identity.canSearch) search() else _state.update { it.copy(error = UiError.NO_TMDB_KEY) }
            }.onFailure { e -> _state.update { it.copy(error = e.reported(TAG, "Identity of $itemId")) } }
        }
    }

    /** After a failure: loads the title again if it could not be, else searches again. */
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
                .onSuccess { results -> _state.update { it.copy(searching = false, results = results) } }
                .onFailure { e ->
                    val error = e.reported(TAG, "Search of $itemId for \"${s.query}\" ${s.year}")
                    _state.update { it.copy(searching = false, error = error) }
                }
        }
    }

    /**
     * Matches the title with [candidate], or goes back to automatic matching; [IdentifyState.done] once
     * done. One at a time: a second OK while matching changes nothing. The results stay as they are.
     */
    fun identify(candidate: MatchCandidate?) {
        val s = _state.value
        val item = s.item ?: return
        if (s.identifying || s.done) return
        _state.update { it.copy(identifying = true, chosenId = candidate?.id, error = null) }
        work.launch {
            suspendRunCatching { provider().editorOrFail().identify(item, candidate) }
                // Still shown as matching while the screen closes.
                .onSuccess { _state.update { s -> s.copy(done = true) } }
                .onFailure { e ->
                    val error = e.reported(TAG, "Identification of $itemId as ${candidate?.id ?: "automatic"}")
                    _state.update { it.copy(identifying = false, chosenId = null, error = error) }
                }
        }
    }

    companion object {
        private const val QUERY = "query"
        private const val YEAR = "year"
        private const val YEAR_DIGITS = 4

        fun factory(app: AppContainer, itemId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { IdentifyViewModel(itemId, app::provider, app.scope, createSavedStateHandle()) }
        }
    }
}
