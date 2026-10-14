package app.houselights.ui.edit

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.houselights.AppContainer
import app.houselights.core.ArtworkChoices
import app.houselights.core.ArtworkKind
import app.houselights.core.ArtworkOption
import app.houselights.core.Identity
import app.houselights.core.MatchCandidate
import app.houselights.core.MediaItem
import app.houselights.core.MetadataEditor
import app.houselights.core.suspendRunCatching
import app.houselights.ui.util.UiError
import app.houselights.ui.util.toUiError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private fun Throwable.reported(): UiError {
    Log.w("Edit", "Edit failed", this)
    return toUiError()
}

private fun AppContainer.editor(): MetadataEditor = provider.editor ?: error("This source cannot be edited")

data class ArtworkState(
    val item: MediaItem? = null,
    val choices: ArtworkChoices? = null,
    val error: UiError? = null,
)

class ArtworkViewModel(private val app: AppContainer, private val itemId: String, private val kind: ArtworkKind) : ViewModel() {
    private val _state = MutableStateFlow(ArtworkState())
    val state: StateFlow<ArtworkState> = _state

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(error = null) }
        viewModelScope.launch {
            suspendRunCatching {
                val item = app.provider.item(itemId)
                ArtworkState(item, app.editor().artwork(item, kind))
            }.onSuccess { _state.value = it }
                .onFailure { e -> _state.update { it.copy(error = e.reported()) } }
        }
    }

    /** True once the choice is saved. */
    suspend fun choose(option: ArtworkOption): Boolean {
        val item = _state.value.item ?: return false
        return suspendRunCatching { app.editor().setArtwork(item, kind, option) }
            .onFailure { e -> _state.update { it.copy(error = e.reported()) } }
            .isSuccess
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
    val error: UiError? = null,
)

class IdentifyViewModel(private val app: AppContainer, private val itemId: String) : ViewModel() {
    private val _state = MutableStateFlow(IdentifyState())
    val state: StateFlow<IdentifyState> = _state
    private var search: Job? = null

    init {
        viewModelScope.launch {
            suspendRunCatching {
                val item = app.provider.item(itemId)
                item to app.editor().identity(item)
            }.onSuccess { (item, identity) ->
                _state.update { it.copy(item = item, identity = identity, query = identity.query, year = identity.year?.toString() ?: "") }
                // What automatic matching would search, so the right title is often one press away.
                if (identity.canSearch) search() else _state.update { it.copy(error = UiError.NO_TMDB_KEY) }
            }.onFailure { e -> _state.update { it.copy(error = e.reported()) } }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setYear(year: String) = _state.update { it.copy(year = year.filter(Char::isDigit).take(4)) }

    fun search() {
        val s = _state.value
        val item = s.item ?: return
        search?.cancel()
        _state.update { it.copy(searching = true, error = null) }
        search = viewModelScope.launch {
            suspendRunCatching { app.editor().search(item, s.query, s.year.toIntOrNull()) }
                .onSuccess { results -> _state.update { it.copy(searching = false, results = results) } }
                .onFailure { e -> _state.update { it.copy(searching = false, error = e.reported()) } }
        }
    }

    /**
     * Matches the title with [candidate], or goes back to automatic matching. True once done. One at
     * a time: a second OK while matching changes nothing. The results stay as they are.
     */
    suspend fun identify(candidate: MatchCandidate?): Boolean {
        val s = _state.value
        val item = s.item ?: return false
        if (s.identifying) return false
        _state.update { it.copy(identifying = true, chosenId = candidate?.id, error = null) }
        return suspendRunCatching { app.editor().identify(item, candidate) }
            .onFailure { e -> _state.update { it.copy(identifying = false, chosenId = null, error = e.reported()) } }
            .isSuccess
    }
}
