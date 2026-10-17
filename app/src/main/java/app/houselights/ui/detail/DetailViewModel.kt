package app.houselights.ui.detail

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.AppContainer
import app.houselights.LibraryChanges
import app.houselights.core.LibraryEvents
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.MediaProvider
import app.houselights.core.ProviderCapabilities
import app.houselights.core.UserState
import app.houselights.core.nextUpIn
import app.houselights.core.suspendRunCatching
import app.houselights.ui.design.HlMotion
import app.houselights.ui.util.LatestJob
import app.houselights.ui.util.UiError
import app.houselights.ui.util.reported
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
    /**
     * The episodes of [selectedSeasonId]; empty while they load ([episodesLoading]) or failed to
     * ([episodesError]). Tried again, the error stays while they load: its row stays, Réessayer at work.
     */
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
    /**
     * Only the title is in, its rows (seasons, saga, similar titles) still coming: the page shows
     * what it says of the title at once, and focus waits for the rows it may come back to.
     */
    val partial: Boolean = false,
)

/**
 * A title's page, from the [provider] of the source in use; what it changes, it tells [events]. The
 * season chosen is kept in [saved]: Android may stop the app while an episode plays. [now] is the
 * time since boot, which a change of the clock never moves.
 */
class DetailViewModel(
    private val itemId: String,
    private val provider: () -> MediaProvider,
    private val events: LibraryEvents,
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {
    private val _state = MutableStateFlow(DetailState(selectedSeasonId = saved[SEASON]))
    val state: StateFlow<DetailState> = _state

    private val loads = LatestJob(viewModelScope)

    /** The episodes of the season chosen last: one at a time, so a slower answer never shows under another tab. */
    private val seasonLoads = LatestJob(viewModelScope)

    init {
        load()
    }

    fun load() {
        // A newer load replaces an older one: two loads never finish out of order. It loads the
        // episodes of the season chosen itself.
        seasonLoads.cancel()
        // With no title shown yet, trying again shows: the error gives way to the load.
        if (_state.value.item == null) _state.update { it.copy(error = null) }
        loads.launch {
            // In a scope of its own, a failed parallel request ends up here instead of crashing the app.
            suspendRunCatching { coroutineScope {
                val source = provider()
                val item = source.item(itemId)
                // A first load says the title at once, on the picture the cut kept, once what Lire
                // starts is known: its rows follow.
                fun sayFirst(nextUp: MediaItem? = null) {
                    if (_state.value.item != null) return
                    val canEdit = source.editor?.canEdit(item) == true
                    _state.update {
                        it.copy(item = item, capabilities = source.capabilities, nextUp = nextUp, canEdit = canEdit, partial = true)
                    }
                }
                if (item.kind != MediaKind.SERIES && item.kind != MediaKind.COLLECTION) sayFirst()
                val similar = async { if (source.capabilities.similar) source.similar(item) else emptyList() }
                if (item.kind == MediaKind.SERIES) {
                    val nextUp = async { source.nextUp(item) }
                    val seasonsLoad = async { source.seasons(item) }
                    val next = nextUp.await()
                    sayFirst(next)
                    val seasons = seasonsLoad.await()
                    val seasonId = _state.value.selectedSeasonId?.takeIf { id -> seasons.any { it.id == id } }
                        ?: next?.seasonId
                        ?: seasons.firstOrNull { (it.seasonNumber ?: 0) > 0 }?.id
                        ?: seasons.firstOrNull()?.id
                    val episodes = seasons.firstOrNull { it.id == seasonId }?.let { source.episodes(item, it) }.orEmpty()
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
                    // What Lire starts is picked from the films already here: the source is asked for them once.
                    val films = source.collectionItems(item)
                    DetailState(item = item, capabilities = source.capabilities, nextUp = nextUpIn(films), collectionFilms = films)
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
                    val error = e.reported(TAG, "Detail of $itemId")
                    // A season this load took over is not left loading: its row says why, with Réessayer.
                    _state.update { s ->
                        val rowError = s.episodesError ?: error.takeIf { s.episodesLoading }
                        s.copy(error = error, episodesLoading = false, episodesError = rowError, partial = false)
                    }
                }
        }
    }

    /**
     * The library changed: the page loads again, unless the change is its own and it already did. Only
     * a signal soon after that change can be its own: one later (the page left meanwhile for Identify,
     * a change of artwork merged into it) is another change, and loads the page.
     */
    fun onLibraryChanged() {
        val own = ownChangeAt
        ownChangeAt = null
        if (own != null && now() - own <= OWN_CHANGE_MS) return
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
        saved[SEASON] = seasonId
        _state.update { it.copy(selectedSeasonId = seasonId, episodes = emptyList(), episodesLoading = true, episodesError = null) }
        loadSeason(series, seasonId, settle = true)
    }

    /** After the episodes of the season chosen failed to load: tries again, the row's error kept until the answer. */
    fun retrySeason() {
        val s = _state.value
        val series = s.item ?: return
        val seasonId = s.selectedSeasonId ?: return
        if (s.episodesLoading) return
        _state.update { it.copy(episodesLoading = true) }
        loadSeason(series, seasonId, settle = false)
    }

    private fun loadSeason(series: MediaItem, seasonId: String, settle: Boolean) {
        seasonLoads.launch {
            if (settle) delay(HlMotion.SETTLE)
            val season = _state.value.seasons.firstOrNull { it.id == seasonId } ?: return@launch
            val result = suspendRunCatching { provider().episodes(series, season) }
            val error = result.exceptionOrNull()?.reported(TAG, "Episodes of $seasonId")
            _state.update {
                if (it.selectedSeasonId != seasonId) return@update it
                it.copy(episodes = result.getOrDefault(emptyList()), episodesLoading = false, episodesError = error)
            }
        }
    }

    private var toggleJob: Job? = null

    /**
     * When this page changed the library itself (watched, a favourite) and loaded again at once: the
     * library's signal of that change, which follows within [OWN_CHANGE_MS], finds the page already
     * showing it. Null when there is none to wait for.
     */
    private var ownChangeAt: Long? = null

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
                    ownChangeAt = now()
                    events.libraryChanged()
                    load()
                }
                .onFailure { e ->
                    val error = e.reported(TAG, "$what of $itemId")
                    _state.update { it.copy(actionError = error) }
                }
        }
    }

    companion object {
        fun factory(app: AppContainer, itemId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { DetailViewModel(itemId, app::provider, app, createSavedStateHandle()) }
        }
    }
}

/** The season chosen, in the saved state. */
private const val SEASON = "season"

/**
 * How long after a change of its own the library's signal can still be that change's: the library
 * moves its version at most once per [LibraryChanges.LIBRARY_CHANGE_MS], plus a margin for a busy
 * main thread.
 */
internal const val OWN_CHANGE_MS = LibraryChanges.LIBRARY_CHANGE_MS + 1_000L

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
