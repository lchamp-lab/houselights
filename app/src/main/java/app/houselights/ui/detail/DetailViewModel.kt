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
import kotlinx.coroutines.Deferred
import android.util.Log
import app.houselights.core.Credits
import app.houselights.core.Saga
import app.houselights.core.Version

data class DetailState(
    val item: MediaItem? = null,
    val error: UiError? = null,
    val capabilities: ProviderCapabilities? = null,
    /** For a series: the episode Lire starts. */
    val nextUp: MediaItem? = null,
    val seasons: List<MediaItem> = emptyList(),
    val selectedSeasonId: String? = null,
    /** Episodes of [selectedSeasonId]; empty while loading or failed; on retry the error row stays, busy. */
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
    /** Why marking watched or favourite failed, shown beside the actions until the next one. */
    val actionError: UiError? = null,
    /** Only the title is loaded, its rows still coming: the page shows the title, and focus waits for the rows. */
    val partial: Boolean = false,
    /** People by job ([app.houselights.core.Catalog.credits]); null when the source reports only the cast. */
    val credits: Credits? = null,
    /** For a collection: the whole saga, missing films in place; null when the source does not know it. */
    val saga: Saga? = null,
    /** For a film in several copies: each one, the one Lire plays marked ([Version.chosen]); empty for one copy. */
    val versions: List<Version> = emptyList(),
) {
    /** The copy Lire plays, for a film in several; null for one copy. */
    val playedCopy: Version? get() = versions.takeIf { it.size > 1 }?.let { all -> all.firstOrNull { it.chosen } ?: all.first() }

    /** Index of [playedCopy], to name a copy without an edition ("Copie 2"). */
    val playedCopyIndex: Int get() = versions.indexOfFirst { it.chosen }.coerceAtLeast(0)
}

/** A title's page; the chosen season is kept in [saved], and [now] is time since boot, immune to clock changes. */
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

    /** Episodes of the last chosen season, one load at a time, so a slow answer never shows under another tab. */
    private val seasonLoads = LatestJob(viewModelScope)

    init {
        load()
    }

    fun load() {
        // A newer load replaces an older one, season load included: it loads the chosen season itself.
        seasonLoads.cancel()
        // With no title shown, a retry clears the error to show the load.
        if (_state.value.item == null) _state.update { it.copy(error = null) }
        loads.launch {
            // In a scope of its own, a failed parallel request ends up here instead of crashing the app.
            suspendRunCatching { coroutineScope {
                val source = provider()
                val item = source.item(itemId)
                val similar = async { if (source.capabilities.similar) source.similar(item) else emptyList() }
                // Optional: on failure the page keeps its cast.
                val credits = async {
                    suspendRunCatching { source.catalog?.credits(item) }.onFailure { Log.w(TAG, "Credits of $itemId", it) }.getOrNull()
                }
                when (item.kind) {
                    MediaKind.SERIES -> seriesPage(source, item, similar)
                    MediaKind.COLLECTION -> collectionPage(source, item)
                    else -> {
                        sayFirst(source, item)
                        filmPage(source, item, similar)
                    }
                }.copy(credits = credits.await())
            } }.onSuccess { loaded ->
                val canEdit = loaded.item?.let { provider().editor?.canEdit(it) } == true
                // Keep the action error until the next action, and any season chosen during the reload.
                _state.update { loaded.copy(canEdit = canEdit, actionError = it.actionError).keepingSeasonOf(it) }
            }
                .onFailure { e ->
                    val error = e.reported(TAG, "Detail of $itemId")
                    // A season load this one superseded is not left busy: its row shows the error, with Réessayer.
                    _state.update { s ->
                        val rowError = s.episodesError ?: error.takeIf { s.episodesLoading }
                        s.copy(error = error, episodesLoading = false, episodesError = rowError, partial = false)
                    }
                }
        }
    }

    /** On a first load, shows the title once what Lire starts is known; rows follow ([DetailState.partial]). */
    private fun sayFirst(source: MediaProvider, item: MediaItem, nextUp: MediaItem? = null) {
        if (_state.value.item != null) return
        val canEdit = source.editor?.canEdit(item) == true
        _state.update { it.copy(item = item, capabilities = source.capabilities, nextUp = nextUp, canEdit = canEdit, partial = true) }
    }

    /** A series' page: what Lire starts, its seasons, the chosen season's episodes, then [similar] titles. */
    private suspend fun seriesPage(source: MediaProvider, item: MediaItem, similar: Deferred<List<MediaItem>>): DetailState =
        coroutineScope {
            val nextUp = async { source.nextUp(item) }
            val seasonsLoad = async { source.seasons(item) }
            val next = nextUp.await()
            sayFirst(source, item, next)
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
        }

    /** A collection's page: its films (requested once; Lire picks from them) and the whole saga if known. */
    private suspend fun collectionPage(source: MediaProvider, item: MediaItem): DetailState {
        val films = source.collectionItems(item)
        val saga = suspendRunCatching { source.catalog?.saga(item) }.onFailure { Log.w(TAG, "Saga of $itemId", it) }.getOrNull()
        return DetailState(item = item, capabilities = source.capabilities, nextUp = nextUpIn(films), collectionFilms = films, saga = saga)
    }

    /** A film's page: its saga, then [similar] titles outside it. */
    private suspend fun filmPage(source: MediaProvider, item: MediaItem, similar: Deferred<List<MediaItem>>): DetailState {
        val collection = source.collectionOf(item)
        // Includes the film itself, showing its place in the saga.
        val saga = collection?.let { c -> source.collectionItems(c) }.orEmpty()
        // Films of the same saga are shown as such, not again as "similar".
        val similarFilms = similar.await().filter { s -> saga.none { it.id == s.id } }
        return DetailState(
            item = item,
            capabilities = source.capabilities,
            similar = similarFilms,
            collection = collection,
            collectionFilms = saga,
            versions = versionsOf(source, item),
        )
    }

    /** Reloads after a library change, unless it is this page's own change, signalled within [OWN_CHANGE_MS]. */
    fun onLibraryChanged() {
        val own = ownChangeAt
        ownChangeAt = null
        if (own != null && now() - own <= OWN_CHANGE_MS) return
        load()
    }

    /** Shows the season's tab at once, row emptied; episodes load once focus settles (tabs select on focus). */
    fun selectSeason(seasonId: String) {
        val s = _state.value
        val series = s.item ?: return
        if (seasonId == s.selectedSeasonId || s.seasons.none { it.id == seasonId }) return
        saved[SEASON] = seasonId
        _state.update { it.copy(selectedSeasonId = seasonId, episodes = emptyList(), episodesLoading = true, episodesError = null) }
        loadSeason(series, seasonId, settle = true)
    }

    /** Retries the chosen season's episodes, keeping the row's error until the result. */
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

    /** When this page last changed the library itself, so that change's signal is skipped; null if none. */
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

    /** Makes [version] the copy Lire plays (null for the best) and rereads the copies; failures show by the actions. */
    fun chooseVersion(version: Version?) {
        val item = _state.value.item ?: return
        _state.update { it.copy(actionError = null) }
        viewModelScope.launch {
            suspendRunCatching {
                val source = provider()
                source.catalog?.chooseVersion(item, version)
                versionsOf(source, item)
            }.onSuccess { versions -> _state.update { it.copy(versions = versions) } }
                .onFailure { e -> _state.update { it.copy(actionError = e.reported(TAG, "Version of $itemId")) } }
        }
    }

    /** The copies of [item]'s film, when the source groups them as one title; none on failure. */
    private suspend fun versionsOf(source: MediaProvider, item: MediaItem): List<Version> {
        if (!source.capabilities.versions) return emptyList()
        return suspendRunCatching { source.catalog?.versions(item) }
            .onFailure { Log.w(TAG, "Versions of $itemId", it) }
            .getOrNull()
            .orEmpty()
    }

    /** Dismisses the error shown beside the actions. */
    fun clearActionError() = _state.update { it.copy(actionError = null) }

    /** Changes the title's user state, one change at a time, then reloads; a failure shows beside the actions. */
    private fun toggle(what: String, change: suspend (MediaItem) -> UserState) {
        val item = _state.value.item ?: return
        if (toggleJob?.isActive == true) return
        _state.update { it.copy(actionError = null) }
        toggleJob = viewModelScope.launch {
            suspendRunCatching { change(item) }
                .onSuccess { user ->
                    _state.update { s -> s.copy(item = s.item?.takeIf { it.id == item.id }?.copy(user = user) ?: s.item) }
                    // Signal at once, as the library's may lag behind a scan's; it then skips the reload.
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

private const val SEASON = "season"

/** How late the signal of the page's own change may come: [LibraryChanges.LIBRARY_CHANGE_MS] plus a margin. */
internal const val OWN_CHANGE_MS = LibraryChanges.LIBRARY_CHANGE_MS + 1_000L

/** This page, keeping a season chosen during the load in [current] (if it still exists) for the focused tab. */
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
