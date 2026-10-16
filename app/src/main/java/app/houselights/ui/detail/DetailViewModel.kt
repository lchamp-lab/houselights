package app.houselights.ui.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.core.LibraryEvents
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.MediaProvider
import app.houselights.core.ProviderCapabilities
import app.houselights.core.UserState
import app.houselights.core.suspendRunCatching
import app.houselights.ui.design.HlMotion
import app.houselights.ui.util.UiError
import app.houselights.ui.util.toUiError
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailState(
    val item: MediaItem? = null,
    val error: UiError? = null,
    val capabilities: ProviderCapabilities? = null,
    /** For a series: the episode "Play" starts. */
    val nextUp: MediaItem? = null,
    val seasons: List<MediaItem> = emptyList(),
    val selectedSeasonId: String? = null,
    /** The episodes of [selectedSeasonId]; empty while they load ([episodesLoading]) or failed to ([episodesError]). */
    val episodes: List<MediaItem> = emptyList(),
    val episodesLoading: Boolean = false,
    val episodesError: UiError? = null,
    val similar: List<MediaItem> = emptyList(),
    /** Artwork and identification can be changed. */
    val canEdit: Boolean = false,
    /** For a film: the collection it belongs to. */
    val collection: MediaItem? = null,
    /** For a collection: its films; for a film: the other films of its collection. */
    val collectionFilms: List<MediaItem> = emptyList(),
    /** Why marking the title watched or a favourite did not take, shown beside the actions until the next one. */
    val actionError: UiError? = null,
)

/** A title's page, from the [provider] of the source in use; what it changes, it tells [events]. */
class DetailViewModel(
    private val itemId: String,
    private val provider: () -> MediaProvider,
    private val events: LibraryEvents,
) : ViewModel() {
    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state

    init {
        load()
    }

    private var loadJob: Job? = null

    /** The episodes of the season chosen last: one at a time, so a slower answer never shows under another tab. */
    private var seasonJob: Job? = null

    fun load() {
        // A newer load replaces an older one: two loads never finish out of order. It loads the
        // episodes of the season chosen itself.
        loadJob?.cancel()
        seasonJob?.cancel()
        // With no title shown yet, trying again shows: the error gives way to the load.
        if (_state.value.item == null) _state.update { it.copy(error = null) }
        loadJob = viewModelScope.launch {
            // In a scope of its own, a failed parallel request ends up here instead of crashing the app.
            suspendRunCatching { coroutineScope {
                val source = provider()
                val item = source.item(itemId)
                val similar = async { if (source.capabilities.similar) source.similar(item) else emptyList() }
                if (item.kind == MediaKind.SERIES) {
                    val nextUp = async { source.nextUp(item) }
                    val seasons = source.seasons(item)
                    val next = nextUp.await()
                    val seasonId = _state.value.selectedSeasonId?.takeIf { id -> seasons.any { it.id == id } }
                        ?: next?.seasonId
                        ?: seasons.firstOrNull { (it.seasonNumber ?: 0) > 0 }?.id
                        ?: seasons.firstOrNull()?.id
                    val episodes = seasons.firstOrNull { it.id == seasonId }?.let { source.episodes(item, it) } ?: emptyList()
                    DetailState(
                        item = item,
                        capabilities = source.capabilities,
                        nextUp = next,
                        seasons = seasons,
                        selectedSeasonId = seasonId,
                        episodes = episodes,
                        similar = similar.await(),
                    )
                } else if (item.kind == MediaKind.COLLECTION) {
                    val films = source.collectionItems(item)
                    DetailState(item = item, capabilities = source.capabilities, nextUp = source.nextUp(item), collectionFilms = films)
                } else {
                    val collection = source.collectionOf(item)
                    val saga = collection?.let { c -> source.collectionItems(c).filter { it.id != item.id } }.orEmpty()
                    // Films of the same saga are shown as such, not again as "similar".
                    val similarFilms = similar.await().filter { s -> saga.none { it.id == s.id } }
                    DetailState(
                        item = item,
                        capabilities = source.capabilities,
                        similar = similarFilms,
                        collection = collection,
                        collectionFilms = saga,
                    )
                }
            } }.onSuccess { loaded ->
                val canEdit = loaded.item?.let { provider().editor?.canEdit(it) } == true
                // A failure said beside the actions stays until the next action; a season chosen while
                // the page loaded again stays chosen.
                _state.update { loaded.copy(canEdit = canEdit, actionError = it.actionError).keepingSeasonOf(it) }
            }
                .onFailure { e ->
                    Log.w(TAG, "Detail of $itemId failed", e)
                    val error = e.toUiError()
                    // A season this load took over is not left loading: its row says why, with Réessayer.
                    _state.update { s ->
                        val rowError = s.episodesError ?: error.takeIf { s.episodesLoading }
                        s.copy(error = error, episodesLoading = false, episodesError = rowError)
                    }
                }
        }
    }

    /** The library changed: the page loads again, unless the change is its own and it already did. */
    fun onLibraryChanged() {
        if (ownChange) {
            ownChange = false
            return
        }
        load()
    }

    /**
     * Shows the season's tab at once, its row emptied; its episodes load once focus has stopped on the
     * tab (tabs are chosen as focus crosses them), unless another season was chosen meanwhile.
     */
    fun selectSeason(seasonId: String) {
        val s = _state.value
        val series = s.item ?: return
        if (seasonId == s.selectedSeasonId || s.seasons.none { it.id == seasonId }) return
        _state.update { it.copy(selectedSeasonId = seasonId, episodes = emptyList(), episodesLoading = true, episodesError = null) }
        loadSeason(series, seasonId, settle = true)
    }

    /** After the episodes of the season chosen failed to load: tries again. */
    fun retrySeason() {
        val s = _state.value
        val series = s.item ?: return
        val seasonId = s.selectedSeasonId ?: return
        _state.update { it.copy(episodesLoading = true, episodesError = null) }
        loadSeason(series, seasonId, settle = false)
    }

    private fun loadSeason(series: MediaItem, seasonId: String, settle: Boolean) {
        seasonJob?.cancel()
        seasonJob = viewModelScope.launch {
            if (settle) delay(HlMotion.SETTLE)
            val season = _state.value.seasons.firstOrNull { it.id == seasonId } ?: return@launch
            val result = suspendRunCatching { provider().episodes(series, season) }
            result.exceptionOrNull()?.let { Log.w(TAG, "Episodes of $seasonId failed", it) }
            _state.update {
                if (it.selectedSeasonId != seasonId) return@update it
                it.copy(
                    episodes = result.getOrDefault(emptyList()),
                    episodesLoading = false,
                    episodesError = result.exceptionOrNull()?.toUiError(),
                )
            }
        }
    }

    private var toggleJob: Job? = null

    /**
     * This page changed the library itself (watched, a favourite) and loaded again at once: the
     * library's signal of that change, which follows, finds the page already showing it.
     */
    private var ownChange = false

    fun togglePlayed() = toggle("Watched") { item ->
        val played = !item.user.played
        provider().setPlayed(item, played)
        item.user.copy(played = played)
    }

    fun toggleFavorite() = toggle("Favorite") { item ->
        val favorite = !item.user.favorite
        provider().setFavorite(item, favorite)
        item.user.copy(favorite = favorite)
    }

    /** A failure beside the actions is read; the next action clears it. */
    fun clearActionError() = _state.update { it.copy(actionError = null) }

    /**
     * Changes where the title stands for the user, one change at a time: a second press while one is
     * saved does nothing. Once saved, the page shows it at once and loads again; the other screens
     * reload when they come back. A failure is said beside the actions.
     */
    private fun toggle(what: String, change: suspend (MediaItem) -> UserState) {
        val item = _state.value.item ?: return
        if (toggleJob?.isActive == true) return
        _state.update { it.copy(actionError = null) }
        toggleJob = viewModelScope.launch {
            suspendRunCatching { change(item) }
                .onSuccess { user ->
                    _state.update { s -> s.copy(item = s.item?.takeIf { it.id == item.id }?.copy(user = user) ?: s.item) }
                    // At once: the library's signal may wait a few seconds behind a scan's. That
                    // signal, once here, does not load the page a second time.
                    ownChange = true
                    events.libraryChanged()
                    load()
                }
                .onFailure { e ->
                    Log.w(TAG, "$what of $itemId failed", e)
                    _state.update { it.copy(actionError = e.toUiError()) }
                }
        }
    }

    companion object {
        fun factory(app: AppContainer, itemId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { DetailViewModel(itemId, app::provider, app) }
        }
    }
}

/**
 * This page as loaded, with the season chosen in [current] when one was chosen while it loaded (and
 * the series still has it): the tab focus is on keeps its row.
 */
internal fun DetailState.keepingSeasonOf(current: DetailState): DetailState {
    val chosen = current.selectedSeasonId
    if (chosen == null || chosen == selectedSeasonId || seasons.none { it.id == chosen }) return this
    return copy(
        selectedSeasonId = chosen,
        episodes = current.episodes,
        episodesLoading = current.episodesLoading,
        episodesError = current.episodesError,
    )
}

private const val TAG = "Detail"
