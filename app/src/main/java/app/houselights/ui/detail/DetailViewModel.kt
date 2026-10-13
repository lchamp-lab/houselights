package app.houselights.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.houselights.AppContainer
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.ProviderCapabilities
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailState(
    val item: MediaItem? = null,
    val error: String? = null,
    val capabilities: ProviderCapabilities? = null,
    /** For a series: the episode "Play" starts. */
    val nextUp: MediaItem? = null,
    val seasons: List<MediaItem> = emptyList(),
    val selectedSeasonId: String? = null,
    val episodes: List<MediaItem> = emptyList(),
    val similar: List<MediaItem> = emptyList(),
    /** Artwork and identification can be changed. */
    val canEdit: Boolean = false,
)

class DetailViewModel(private val app: AppContainer, private val itemId: String) : ViewModel() {
    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state

    init {
        load()
        viewModelScope.launch { app.libraryChanged.collect { load() } }
    }

    fun load() {
        viewModelScope.launch {
            runCatching {
                val provider = app.provider
                val item = provider.item(itemId)
                val similar = async { if (provider.capabilities.similar) provider.similar(item) else emptyList() }
                if (item.kind == MediaKind.SERIES) {
                    val nextUp = async { provider.nextUp(item) }
                    val seasons = provider.seasons(item)
                    val next = nextUp.await()
                    val seasonId = _state.value.selectedSeasonId?.takeIf { id -> seasons.any { it.id == id } }
                        ?: next?.seasonId
                        ?: seasons.firstOrNull { (it.seasonNumber ?: 0) > 0 }?.id
                        ?: seasons.firstOrNull()?.id
                    val episodes = seasons.firstOrNull { it.id == seasonId }?.let { provider.episodes(item, it) } ?: emptyList()
                    DetailState(item, null, provider.capabilities, next, seasons, seasonId, episodes, similar.await())
                } else {
                    DetailState(item = item, capabilities = provider.capabilities, similar = similar.await())
                }
            }.onSuccess { loaded -> _state.value = loaded.copy(canEdit = loaded.item?.let { app.provider.editor?.canEdit(it) } == true) }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) } }
        }
    }

    fun selectSeason(seasonId: String) {
        val s = _state.value
        val series = s.item ?: return
        if (seasonId == s.selectedSeasonId) return
        val season = s.seasons.firstOrNull { it.id == seasonId } ?: return
        _state.update { it.copy(selectedSeasonId = seasonId) }
        viewModelScope.launch {
            runCatching { app.provider.episodes(series, season) }
                .onSuccess { eps -> _state.update { it.copy(episodes = eps) } }
        }
    }

    fun togglePlayed() {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            runCatching { app.provider.setPlayed(item, !item.user.played) }.onSuccess { load() }
        }
    }

    fun toggleFavorite() {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            runCatching { app.provider.setFavorite(item, !item.user.favorite) }.onSuccess { load() }
        }
    }
}
