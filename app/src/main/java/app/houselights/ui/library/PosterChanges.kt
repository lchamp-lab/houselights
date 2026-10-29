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
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a poster's options change (#47), as the title's page changes it, through the source [source] gives: its mark
 * ([MediaProvider.setPlayed], so Trakt follows it as it does from the page), its favourite ([MediaProvider.setFavorite])
 * and the order of a show's episodes, each saved in [work], which outlives the screen, then told to the other screens
 * ([events]). A change not saved is undone by its screen and said ([notSaved]), logged under [tag]. A mark or a favourite
 * is held ([pending]) from the moment it shows until it is saved and read again: its screen lays it over each of its
 * reads meanwhile, which may answer as the source stood before it.
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

    /** The marks and favourites held until a read after their save, laid over the screen's reads ([PendingChanges]). */
    val pending = PendingChanges()

    /** What the source keeps: a mark, a favourite; nothing without a source. */
    val capabilities: ProviderCapabilities get() = source()?.capabilities ?: ProviderCapabilities.NONE

    /** Whether [item]'s options offer Modifier: its source keeps its pictures and its match. */
    fun canEdit(item: MediaItem): Boolean = source()?.editor?.canEdit(item) == true

    /** Marks [item] [played], or not; not saved, [undo] puts it back as it was, then it is said. */
    fun mark(item: MediaItem, played: Boolean, undo: () -> Unit = {}) {
        val held = pending.hold(item.markedAs(played)) { keepsMarked(it, played) }
        save(item, "Mark of ${item.id} from its poster", undo, held) { it.setPlayed(item, played) }
    }

    /** Makes [item] a [favorite], or not; not saved, [undo] puts it back as it was, then it is said. */
    fun favorite(item: MediaItem, favorite: Boolean, undo: () -> Unit = {}) {
        val held = pending.hold(item.favoredAs(favorite)) { keepsFavorite(it, favorite) }
        save(item, "Favourite of ${item.id} from its poster", undo, held) { it.setFavorite(item, favorite) }
    }

    /** The orders show [item]'s episodes can follow, as its page reads them; null when there is no choice or no reading. */
    suspend fun episodeOrders(item: MediaItem): EpisodeOrderChoices? =
        suspendRunCatching { source()?.editor?.episodeOrders(item) }.onFailure { Log.w(tag, "Orders of ${item.id}", it) }.getOrNull()

    /** Places show [item]'s episodes in the order [id], the automatic one when null, as its page's Modifier does. */
    fun chooseEpisodeOrder(item: MediaItem, id: String?) =
        save(item, "Episode order of ${item.id} from its poster", undo = {}, held = null) { it.editor?.chooseEpisodeOrder(item, id) }

    /** Focus is on [id]'s title: a change not saved on another is no longer said, its line naming it again. */
    fun focusedOn(id: String) = failure.update { it?.takeIf { said -> said.id == id } }

    private fun save(item: MediaItem, what: String, undo: () -> Unit, held: Held?, write: suspend (MediaProvider) -> Unit) {
        // A change clears what the last one said.
        failure.value = null
        work.launch {
            suspendRunCatching { write(checkNotNull(source()) { "No library source configured" }) }
                .onSuccess {
                    // Saved before the other screens are told, so the read they begin then lets it go.
                    held?.let(pending::saved)
                    events?.libraryChanged()
                }
                .onFailure { e ->
                    val error = e.reported(tag, what)
                    held?.let(pending::dropped)
                    undo()
                    failure.value = NotSaved(item.id, error)
                }
        }
    }
}

/**
 * The marks and favourites a poster's options asked, each held from the moment it shows until the source is sure to
 * say it, as the title's page holds its change (DetailViewModel's pending): a read begun before it was saved may answer
 * as the source stood before it, a scan's progress or the read a previous change asked, and bring the title back for a
 * moment. Each read begins with [reading] and lays what [over] gives over its answer; a change saved no longer lies over
 * a read begun after its save, which says it, so a change made again elsewhere then shows as the source says it. Not
 * saved, a change is let go at once ([dropped]), its screen undoing it. The last change asked of each title is kept
 * as long as its screen lives, a few titles at most.
 */
internal class PendingChanges {
    private val begun = AtomicInteger()
    private val held = MutableStateFlow<Map<String, Held>>(emptyMap())

    /** A read begins, now: its answer is to be laid under what [over] gives it. */
    fun reading(): Reading = Reading(begun.incrementAndGet())

    /** Holds [changed] from now, a wall filtered on a status keeping it as long as [stays] says. */
    fun hold(changed: MediaItem, stays: (WatchStatus) -> Boolean): Held =
        Held(changed, stays, madeAfter = begun.get()).also { change -> held.update { it + (changed.id to change) } }

    /** [change] is saved: a read begun from now says it. */
    fun saved(change: Held) {
        change.savedAfter = begun.get()
    }

    /** [change] was not saved: it lies over no read again. */
    fun dropped(change: Held) = held.update { if (it[change.item.id] === change) it - change.item.id else it }

    /** What [read] is to show over its answer: the changes held it may not know, not saved before it began. */
    fun over(read: Reading): List<Laid> = held.value.values
        .filter { change -> change.savedAfter.let { it == null || read.number <= it } }
        .map { change -> Laid(change.item, change.stays, readBefore = read.number <= change.madeAfter) }
}

/** A read of a screen, by the order it began in ([PendingChanges.reading]). */
@JvmInline
internal value class Reading(val number: Int)

/** A change held ([PendingChanges.hold]): [item] as it shows, made after [madeAfter] reads began, saved after [savedAfter]. */
internal class Held(val item: MediaItem, val stays: (WatchStatus) -> Boolean, val madeAfter: Int) {
    @Volatile
    var savedAfter: Int? = null
}

/**
 * A change held, to show over a read: [item] as it now stands, still on a wall filtered on a status while [stays] says
 * so; [readBefore], the read began before it was made, and counts it as it was, even where its answer does not hold it.
 */
internal class Laid(val item: MediaItem, val stays: (WatchStatus) -> Boolean, val readBefore: Boolean)

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
