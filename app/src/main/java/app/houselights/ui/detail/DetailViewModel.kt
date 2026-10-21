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

    /** The chosen season when the page shows a season track (two seasons or more): what the watched tile marks. */
    val shownSeason: MediaItem?
        get() = seasons.takeIf { item?.kind == MediaKind.SERIES && it.size > 1 }?.firstOrNull { it.id == selectedSeasonId }
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

    /** Marks the title watched or not; a show, all its episodes. */
    fun togglePlayed() {
        val item = _state.value.item ?: return
        markPlayed(item, !item.user.played)
    }

    /** Marks the season shown ([DetailState.shownSeason]) watched, or unwatched once all of it is. */
    fun toggleSeasonPlayed() {
        val season = _state.value.shownSeason ?: return
        markPlayed(season, !seasonWatched(season))
    }

    /** Marks an episode of the season shown watched, or unwatched once it is. */
    fun toggleEpisodePlayed(episode: MediaItem) {
        val shown = _state.value.episodes.firstOrNull { it.id == episode.id } ?: return
        markPlayed(shown, !shown.user.played)
    }

    private fun markPlayed(target: MediaItem, played: Boolean) =
        change("Watched of ${target.id}", { provider().setPlayed(target, played) }) { it.marked(target, played) }

    fun toggleFavorite() {
        val item = _state.value.item ?: return
        val favorite = !item.user.favorite
        change("Favorite of ${item.id}", { provider().setFavorite(item, favorite) }) { s ->
            val shown = s.item?.takeIf { it.id == item.id } ?: return@change s
            s.copy(item = shown.copy(user = shown.user.copy(favorite = favorite)))
        }
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

    /**
     * Saves a change of user state, one at a time, shows it at once ([shown]), then reloads; a failure shows beside
     * the actions.
     */
    private fun change(what: String, save: suspend () -> Unit, shown: (DetailState) -> DetailState) {
        if (_state.value.item == null || toggleJob?.isActive == true) return
        _state.update { it.copy(actionError = null) }
        toggleJob = viewModelScope.launch {
            suspendRunCatching { save() }
                .onSuccess {
                    _state.update(shown)
                    // Signal at once, as the library's may lag behind a scan's; it then skips the reload.
                    ownChangeAt = now()
                    events.libraryChanged()
                    load()
                }
                .onFailure { e ->
                    val error = e.reported(TAG, what)
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

/** A season is watched once all its episodes are, as its tab's check says ([seasonDetail]). */
internal fun seasonWatched(season: MediaItem): Boolean =
    season.user.played || ((season.childCount ?: 0) > 0 && (season.user.unplayedCount ?: 1) <= 0)

/** This page once [target] (the title, one of its seasons or episodes) is marked [played], as its source marks it. */
internal fun DetailState.marked(target: MediaItem, played: Boolean): DetailState {
    val title = item ?: return this
    return when {
        target.id == title.id -> markedTitle(title, played)
        target.kind == MediaKind.SEASON -> markedSeason(target, played)
        target.kind == MediaKind.EPISODE -> markedEpisode(target, played)
        else -> this
    }
}

private fun DetailState.markedTitle(title: MediaItem, played: Boolean): DetailState = when {
    title.kind == MediaKind.SERIES -> copy(
        item = title.markedWhole(played, episodeCount(seasons)),
        seasons = seasons.map { it.markedWhole(played, it.childCount) },
        episodes = episodes.map { it.markedFile(played) },
    )
    title.isPlayable -> copy(item = title.markedFile(played))
    else -> copy(item = title.markedWhole(played, title.childCount))
}

private fun DetailState.markedSeason(target: MediaItem, played: Boolean): DetailState {
    val season = seasons.firstOrNull { it.id == target.id } ?: return this
    val shown = selectedSeasonId == season.id
    val count = season.childCount ?: episodes.size.takeIf { shown && it > 0 }
    val before = season.user.unplayedCount ?: if (seasonWatched(season)) 0 else count
    val marked = season.markedWhole(played, count)
    val after = marked.user.unplayedCount
    // Counts unknown: the show is at least one episode short of watched, or as it was.
    val delta = if (before != null && after != null) after - before else if (played) 0 else 1
    return copy(
        seasons = seasons.map { if (it.id == season.id) marked else it },
        episodes = if (shown) episodes.map { it.markedFile(played) } else episodes,
        item = item?.unwatchedBy(delta),
    )
}

private fun DetailState.markedEpisode(target: MediaItem, played: Boolean): DetailState {
    val episode = episodes.firstOrNull { it.id == target.id } ?: return this
    val delta = when {
        episode.user.played == played -> 0
        played -> -1
        else -> 1
    }
    val seasonId = episode.seasonId ?: selectedSeasonId
    return copy(
        episodes = episodes.map { if (it.id == episode.id) it.markedFile(played) else it },
        seasons = seasons.map { if (it.id == seasonId) it.unwatchedBy(delta) else it },
        item = item?.unwatchedBy(delta),
    )
}

/** A show's episodes, from its [seasons]' counts; null when one is unknown. */
private fun episodeCount(seasons: List<MediaItem>): Int? =
    seasons.takeIf { all -> all.isNotEmpty() && all.all { it.childCount != null } }?.sumOf { it.childCount ?: 0 }

/** A file marked [played]: back to its start either way, as sources clear its resume point. */
private fun MediaItem.markedFile(played: Boolean) = copy(user = user.copy(played = played, positionMs = 0))

/** A show or season marked [played] whole, its [episodes] all unwatched otherwise (unknown when null). */
private fun MediaItem.markedWhole(played: Boolean, episodes: Int?) =
    copy(user = user.copy(played = played, unplayedCount = if (played) 0 else episodes))

/** A show or season [delta] episodes more unwatched; watched once none is left, unwatched once one is. */
private fun MediaItem.unwatchedBy(delta: Int): MediaItem {
    if (delta == 0) return this
    val left = user.unplayedCount?.let { (it + delta).coerceAtLeast(0) }
    return copy(user = user.copy(played = left?.let { it == 0 } ?: (user.played && delta < 0), unplayedCount = left))
}

private const val TAG = "Detail"
