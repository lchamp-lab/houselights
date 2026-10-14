package app.houselights.ui.edit

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
import app.houselights.providers.local.NoTmdbKeyException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** Why an edit could not be done, shown in words the user can act on. */
enum class EditError { OFFLINE, NO_KEY, FAILED }

private fun Throwable.toEditError() = when (this) {
    is NoTmdbKeyException -> EditError.NO_KEY
    is IOException -> EditError.OFFLINE
    else -> EditError.FAILED
}

private fun AppContainer.editor(): MetadataEditor = provider.editor ?: error("This source cannot be edited")

data class ArtworkState(
    val item: MediaItem? = null,
    val choices: ArtworkChoices? = null,
    val error: EditError? = null,
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
                .onFailure { e -> _state.update { it.copy(error = e.toEditError()) } }
        }
    }

    /** True once the choice is saved. */
    suspend fun choose(option: ArtworkOption): Boolean {
        val item = _state.value.item ?: return false
        return suspendRunCatching { app.editor().setArtwork(item, kind, option) }
            .onFailure { e -> _state.update { it.copy(error = e.toEditError()) } }
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
    val error: EditError? = null,
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
                if (identity.canSearch) search() else _state.update { it.copy(error = EditError.NO_KEY) }
            }.onFailure { e -> _state.update { it.copy(error = e.toEditError()) } }
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
                .onFailure { e -> _state.update { it.copy(searching = false, error = e.toEditError()) } }
        }
    }

    /** Matches the title with [candidate], or goes back to automatic matching. True once done. */
    suspend fun identify(candidate: MatchCandidate?): Boolean {
        val item = _state.value.item ?: return false
        _state.update { it.copy(identifying = true, error = null) }
        return suspendRunCatching { app.editor().identify(item, candidate) }
            .onFailure { e -> _state.update { it.copy(identifying = false, error = e.toEditError()) } }
            .isSuccess
    }
}
