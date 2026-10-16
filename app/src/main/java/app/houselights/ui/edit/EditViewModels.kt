package app.houselights.ui.edit

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
import app.houselights.ui.util.UiError
import app.houselights.ui.util.toUiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private fun Throwable.reported(): UiError {
    Log.w("Edit", "Edit failed", this)
    return toUiError()
}

private fun MediaProvider.editorOrFail(): MetadataEditor = editor ?: error("This source cannot be edited")

data class ArtworkState(
    val item: MediaItem? = null,
    val choices: ArtworkChoices? = null,
    val error: UiError? = null,
)

/** The choices for one piece of a title's artwork, from the [provider] of the source in use. */
class ArtworkViewModel(
    private val itemId: String,
    private val kind: ArtworkKind,
    private val provider: () -> MediaProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(ArtworkState())
    val state: StateFlow<ArtworkState> = _state

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(error = null) }
        viewModelScope.launch {
            suspendRunCatching {
                val source = provider()
                val item = source.item(itemId)
                ArtworkState(item, source.editorOrFail().artwork(item, kind))
            }.onSuccess { _state.value = it }
                .onFailure { e -> _state.update { it.copy(error = e.reported()) } }
        }
    }

    /** True once the choice is saved. */
    suspend fun choose(option: ArtworkOption): Boolean {
        val item = _state.value.item ?: return false
        return suspendRunCatching { provider().editorOrFail().setArtwork(item, kind, option) }
            .onFailure { e -> _state.update { it.copy(error = e.reported()) } }
            .isSuccess
    }

    companion object {
        fun factory(app: AppContainer, itemId: String, kind: ArtworkKind): ViewModelProvider.Factory = viewModelFactory {
            initializer { ArtworkViewModel(itemId, kind, app::provider) }
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
 * which outlives the screen: leaving while a series is matched never leaves it half done.
 */
class IdentifyViewModel(
    private val itemId: String,
    private val provider: () -> MediaProvider,
    private val work: CoroutineScope,
) : ViewModel() {
    private val _state = MutableStateFlow(IdentifyState())
    val state: StateFlow<IdentifyState> = _state
    private var search: Job? = null

    private var loading: Job? = null

    init {
        load()
    }

    /** The title and how it is matched now, then the search automatic matching would make. */
    private fun load() {
        loading?.cancel()
        _state.update { it.copy(error = null) }
        loading = viewModelScope.launch {
            suspendRunCatching {
                val source = provider()
                val item = source.item(itemId)
                item to source.editorOrFail().identity(item)
            }.onSuccess { (item, identity) ->
                _state.update { it.copy(item = item, identity = identity, query = identity.query, year = identity.year?.toString() ?: "") }
                // What automatic matching would search, so the right title is often one press away.
                if (identity.canSearch) search() else _state.update { it.copy(error = UiError.NO_TMDB_KEY) }
            }.onFailure { e -> _state.update { it.copy(error = e.reported()) } }
        }
    }

    /** After a failure: loads the title again if it could not be, else searches again. */
    fun retry() = if (_state.value.item == null) load() else search()

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setYear(year: String) = _state.update { it.copy(year = year.filter(Char::isDigit).take(4)) }

    fun search() {
        val s = _state.value
        val item = s.item ?: return
        search?.cancel()
        _state.update { it.copy(searching = true, error = null) }
        search = viewModelScope.launch {
            suspendRunCatching { provider().editorOrFail().search(item, s.query, s.year.toIntOrNull()) }
                .onSuccess { results -> _state.update { it.copy(searching = false, results = results) } }
                .onFailure { e -> _state.update { it.copy(searching = false, error = e.reported()) } }
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
                .onSuccess { _state.update { it.copy(done = true) } }
                .onFailure { e -> _state.update { it.copy(identifying = false, chosenId = null, error = e.reported()) } }
        }
    }

    companion object {
        fun factory(app: AppContainer, itemId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { IdentifyViewModel(itemId, app::provider, app.scope) }
        }
    }
}
