package app.houselights.ui.library

import android.util.Log
import app.houselights.core.EpisodeOrderChoices
import app.houselights.core.LibraryEvents
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.MediaProvider
import app.houselights.core.ProviderCapabilities
import app.houselights.core.WatchStatus
import app.houselights.core.suspendRunCatching
import app.houselights.ui.util.UiError
import app.houselights.ui.util.reported
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What a poster's options change (#47), as the title's page changes it, through the source [source] gives: its mark
 * ([MediaProvider.setPlayed], so Trakt follows it as it does from the page), its favourite ([MediaProvider.setFavorite])
 * and the order of a show's episodes, each saved in [work], which outlives the screen, then told to the other screens
 * ([events]). A change not saved is undone by its screen and said ([notSaved]), logged under [tag].
 */
internal class PosterChanges(
    private val source: () -> MediaProvider?,
    private val events: LibraryEvents?,
    private val work: CoroutineScope,
    private val tag: String,
) {
    private val failure = MutableStateFlow<NotSaved?>(null)

    /** The last change not saved, said on the line naming its title while it has focus; null once left or changed again. */
    val notSaved: StateFlow<NotSaved?> = failure

    /** What the source keeps: a mark, a favourite; nothing without a source. */
    val capabilities: ProviderCapabilities get() = source()?.capabilities ?: ProviderCapabilities.NONE

    /** Whether [item]'s options offer Modifier: its source keeps its pictures and its match. */
    fun canEdit(item: MediaItem): Boolean = source()?.editor?.canEdit(item) == true

    /** Marks [item] [played], or not; not saved, [undo] puts it back as it was, then it is said. */
    fun mark(item: MediaItem, played: Boolean, undo: () -> Unit = {}) =
        save(item, "Mark of ${item.id} from its poster", undo) { it.setPlayed(item, played) }

    /** Makes [item] a [favorite], or not; not saved, [undo] puts it back as it was, then it is said. */
    fun favorite(item: MediaItem, favorite: Boolean, undo: () -> Unit = {}) =
        save(item, "Favourite of ${item.id} from its poster", undo) { it.setFavorite(item, favorite) }

    /** The orders show [item]'s episodes can follow, as its page reads them; null when there is no choice or no reading. */
    suspend fun episodeOrders(item: MediaItem): EpisodeOrderChoices? =
        suspendRunCatching { source()?.editor?.episodeOrders(item) }.onFailure { Log.w(tag, "Orders of ${item.id}", it) }.getOrNull()

    /** Places show [item]'s episodes in the order [id], the automatic one when null, as its page's Modifier does. */
    fun chooseEpisodeOrder(item: MediaItem, id: String?) =
        save(item, "Episode order of ${item.id} from its poster", undo = {}) { it.editor?.chooseEpisodeOrder(item, id) }

    /** Focus is on [id]'s title: a change not saved on another is no longer said, its line naming it again. */
    fun focusedOn(id: String) = failure.update { it?.takeIf { said -> said.id == id } }

    private fun save(item: MediaItem, what: String, undo: () -> Unit, write: suspend (MediaProvider) -> Unit) {
        // A change clears what the last one said.
        failure.value = null
        work.launch {
            suspendRunCatching { write(checkNotNull(source()) { "No library source configured" }) }
                .onSuccess { events?.libraryChanged() }
                .onFailure { e ->
                    val error = e.reported(tag, what)
                    undo()
                    failure.value = NotSaved(item.id, error)
                }
        }
    }
}

/** A change a poster's options asked that was not saved: the title [id] it was about, and why ([error]). */
data class NotSaved(val id: String, val error: UiError)

/** Whether a poster's options mark [item] ([MediaProvider.setPlayed]): a film, a show, a saga, an episode, a video. */
internal fun marks(item: MediaItem): Boolean = item.kind != MediaKind.SEASON && item.kind != MediaKind.FOLDER

/** Whether a poster's options make [item] a favourite: a film or a show, as their page's heart does, never a saga. */
internal fun favours(item: MediaItem): Boolean = item.kind == MediaKind.MOVIE || item.kind == MediaKind.SERIES

/**
 * [this] as marking it [played] shows it at once, before the source counts it again: its place in it gone, a saga's
 * films all marked with it, a show's episodes all watched; a show marked unwatched keeps its count until read again.
 */
internal fun MediaItem.markedAs(played: Boolean): MediaItem {
    val unplayed = when {
        kind == MediaKind.COLLECTION -> if (played) 0 else childCount ?: user.unplayedCount
        played -> user.unplayedCount?.let { 0 }
        else -> user.unplayedCount
    }
    return copy(user = user.copy(played = played, positionMs = 0, unplayedCount = unplayed))
}

/** [this] made a [favorite], or not. */
internal fun MediaItem.favoredAs(favorite: Boolean): MediaItem = copy(user = user.copy(favorite = favorite))

/**
 * Whether a wall filtered on [status] still shows a title marked [played], as the source filters it: a title marked
 * watched leaves Non vus and En cours, one marked unwatched, back to its start, leaves Vus and En cours.
 */
internal fun keepsMarked(status: WatchStatus, played: Boolean): Boolean = when (status) {
    WatchStatus.UNWATCHED -> !played
    WatchStatus.WATCHED -> played
    WatchStatus.IN_PROGRESS -> false
    WatchStatus.ALL, WatchStatus.FAVORITES -> true
}

/** Whether a wall filtered on [status] still shows a title made a [favorite], or not: one taken out leaves Favoris. */
internal fun keepsFavorite(status: WatchStatus, favorite: Boolean): Boolean = status != WatchStatus.FAVORITES || favorite
