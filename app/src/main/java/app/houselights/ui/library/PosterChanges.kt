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
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a poster's options change (#47), as the title's page changes it, through the source [source] gives: its mark
 * ([MediaProvider.setPlayed], so Trakt follows it as it does from the page), its favourite ([MediaProvider.setFavorite])
 * and the order of a show's episodes, each saved in [work], which outlives the screen, then told to the other screens
 * ([events]). A mark or a favourite is held ([pending]) from the moment it shows until it is saved and read again: a
 * wall, « À regarder », a person's page and search lay it over each of their reads meanwhile, which may answer as the
 * source stood before it. Not saved, a change is undone by its screen, its mark or its favourite alone ([Undone]), over
 * what the screen then shows, and said ([notSaved]), logged under [tag].
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

    /** Marks [item], as it shows, [played], or not; not saved, [undo] shows its title as the source has it, then it is said. */
    fun mark(item: MediaItem, played: Boolean, undo: (Undone) -> Unit = {}) {
        val held = pending.hold(item, item.markedAs(played), Facet.MARK) { keepsMarked(it, played) }
        save(item, "Mark of ${item.id} from its poster", held, undo) { it.setPlayed(item, played) }
    }

    /** Makes [item], as it shows, a [favorite], or not; not saved, [undo] shows its title as the source has it, then it is said. */
    fun favorite(item: MediaItem, favorite: Boolean, undo: (Undone) -> Unit = {}) {
        val held = pending.hold(item, item.favoredAs(favorite), Facet.FAVORITE) { keepsFavorite(it, favorite) }
        save(item, "Favourite of ${item.id} from its poster", held, undo) { it.setFavorite(item, favorite) }
    }

    /** The orders show [item]'s episodes can follow, as its page reads them; null when there is no choice or no reading. */
    suspend fun episodeOrders(item: MediaItem): EpisodeOrderChoices? =
        suspendRunCatching { source()?.editor?.episodeOrders(item) }.onFailure { Log.w(tag, "Orders of ${item.id}", it) }.getOrNull()

    /** Places show [item]'s episodes in the order [id], the automatic one when null, as its page's Modifier does. */
    fun chooseEpisodeOrder(item: MediaItem, id: String?) =
        save(item, "Episode order of ${item.id} from its poster", held = null) { it.editor?.chooseEpisodeOrder(item, id) }

    /** Focus is on [id]'s title: a change not saved on another is no longer said, its line naming it again. */
    fun focusedOn(id: String) = failure.update { it?.takeIf { said -> said.id == id } }

    private fun save(
        item: MediaItem,
        what: String,
        held: Held?,
        undo: (Undone) -> Unit = {},
        write: suspend (MediaProvider) -> Unit,
    ) {
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
                    held?.let { undo(pending.dropped(it)) }
                    failure.value = NotSaved(item.id, error)
                }
        }
    }
}

/**
 * The marks and favourites a poster's options asked, each held from the moment it shows until the source is sure to
 * say it, as the title's page holds its change (DetailViewModel's pending): a read begun before it was saved may answer
 * as the source stood before it, a scan's progress or the read a previous change asked, and bring the title back for a
 * moment. Each read begins with [reading] and lays what [over] gives over its answer, each title's last mark and last
 * favourite not let go; a change saved no longer lies over a read begun after its save, which says it, so a change made
 * again elsewhere then shows as the source says it. Not saved, a change is let go at once ([dropped]), its screen
 * undoing it alone. A title's changes asked while one of them is still saving are kept together until one is asked
 * with none saving, then only the last saved of its mark and of its favourite: a few titles, a few changes each.
 */
internal class PendingChanges {
    private val begun = AtomicInteger()
    private val bursts = AtomicInteger()
    private val held = MutableStateFlow<Map<String, List<Held>>>(emptyMap())

    /** A read begins, now: its answer is to be laid under what [over] gives it. */
    fun reading(): Reading = Reading(begun.incrementAndGet())

    /**
     * Holds [facet] of [changed] from now, shown [before] as it was, a wall filtered on a status keeping it as long as
     * [stays] says: with the changes of its title still saving, if any, and those asked with them.
     */
    fun hold(before: MediaItem, changed: MediaItem, facet: Facet, stays: (WatchStatus) -> Boolean): Held {
        val made = begun.get()
        return held.updateAndGet { all ->
            val title = all[changed.id].orEmpty()
            val burst = title.firstOrNull { it.saving }?.burst
            // None saving, a read begun before the last save of each facet may not say it: the rest is let go.
            val kept = if (burst == null) title.lastSaved() else title
            all + (changed.id to kept + Held(before, changed, facet, stays, made, burst ?: bursts.incrementAndGet()))
        }.getValue(changed.id).last()
    }

    /** [change] is saved: a read begun from now says it. */
    fun saved(change: Held) {
        change.savedAfter = begun.get()
    }

    /** [change] was not saved: it lies over no read again, and its title is to show as [Undone] says. */
    fun dropped(change: Held): Undone {
        change.failed = true
        return Undone(change, held.value[change.item.id].orEmpty().filter { it.burst == change.burst })
    }

    /** What [read] is to show over its answer: of each title, its last mark and favourite held, not saved before it began. */
    fun over(read: Reading): List<Laid> = held.value.values.mapNotNull { title ->
        Facet.entries
            .mapNotNull { facet -> title.lastOrNull { it.facet == facet && !it.failed } }
            .filter { change -> change.savedAfter.let { it == null || read.number <= it } }
            .takeIf { it.isNotEmpty() }
            ?.let { changes -> Laid(changes, read) }
    }
}

/** What a poster's options change of a title: its mark, or whether it is a favourite. */
internal enum class Facet {
    /** Its mark: watched or not, where it was left, what a show or a saga has left to watch. */
    MARK {
        override fun copied(from: MediaItem, into: MediaItem): MediaItem = into.copy(
            user = into.user.copy(played = from.user.played, positionMs = from.user.positionMs, unplayedCount = from.user.unplayedCount),
        )

        override fun laid(changed: MediaItem, read: MediaItem): MediaItem = read.markedAs(changed.user.played)
    },

    /** Whether it is a favourite. */
    FAVORITE {
        override fun copied(from: MediaItem, into: MediaItem): MediaItem = into.copy(user = into.user.copy(favorite = from.user.favorite))

        override fun laid(changed: MediaItem, read: MediaItem): MediaItem = read.favoredAs(changed.user.favorite)
    },
    ;

    /** [into] with this facet as [from] has it, all else as [into] has it. */
    abstract fun copied(from: MediaItem, into: MediaItem): MediaItem

    /**
     * [read], a title as a read answered it, with this facet changed as [changed] shows it: a mark made anew of it, so
     * that what a show or a saga has left to watch follows what the read counts of it, a film a scan added to a saga.
     */
    abstract fun laid(changed: MediaItem, read: MediaItem): MediaItem
}

/** A read of a screen, by the order it began in ([PendingChanges.reading]). */
@JvmInline
internal value class Reading(val number: Int)

/**
 * A change held ([PendingChanges.hold]): [facet] of [item] as it shows, [before] as it showed, made after [madeAfter]
 * reads began, in the [burst] of the changes of its title asked while one was saving; saved after [savedAfter], or
 * [failed].
 */
internal class Held(
    val before: MediaItem,
    val item: MediaItem,
    val facet: Facet,
    val stays: (WatchStatus) -> Boolean,
    val madeAfter: Int,
    val burst: Int,
) {
    @Volatile
    var savedAfter: Int? = null

    @Volatile
    var failed = false

    /** Neither saved yet nor failed. */
    val saving: Boolean get() = savedAfter == null && !failed
}

/**
 * The changes held of a title, to show over [read], its last mark and its last favourite ([changes]): its [item] as one
 * of them shows it, for what it is; on a wall filtered on a status while each [stays].
 */
internal class Laid(private val changes: List<Held>, private val read: Reading) {
    val item: MediaItem get() = changes.last().item

    /** [read], the title as the read answered it, with what these changes show of it. */
    fun over(read: MediaItem): MediaItem = changes.fold(read) { shown, change -> change.facet.laid(change.item, shown) }

    /** Whether a wall filtered on [status] shows the title so changed. */
    fun stays(status: WatchStatus): Boolean = changes.all { it.stays(status) }

    /**
     * Whether the read began before the changes that take the title off a wall filtered on [status], and so counts it,
     * even where its answer does not hold it; the other change, which keeps it there, says nothing of its count.
     */
    fun readBefore(status: WatchStatus): Boolean =
        changes.filterNot { it.stays(status) }.let { away -> away.isNotEmpty() && away.all { read.number <= it.madeAfter } }
}

/**
 * A change not saved ([PendingChanges.dropped]), as its screen undoes it, of the title [id]: its mark or its favourite
 * back as the source has it, without it nor a change of it asked meanwhile that was not saved either, one asked after
 * it still saving or saved showing still ([over]); its other changes, of [burst], showing as they stand.
 */
internal class Undone(change: Held, burst: List<Held>) {
    val id: String = change.item.id

    private val facet = change.facet

    /** The title's [facet] as the source has it; null while a change of it asked after this one is saving or saved, and shows. */
    private val back: MediaItem? = burst.notSaved(facet)

    /** The changes of the title still saving or saved, the last of its mark and of its favourite. */
    private val kept = Facet.entries.mapNotNull { f -> burst.lastOrNull { it.facet == f && !it.failed } }

    /** The title as it now stands, to show again where this change had taken it away. */
    val item: MediaItem = Facet.entries.fold(change.before) { shown, f ->
        (burst.notSaved(f) ?: burst.lastOrNull { it.facet == f && !it.failed }?.item)?.let { f.copied(it, shown) } ?: shown
    }

    /** [shown], the title as its screen shows it, with this change undone. */
    fun over(shown: MediaItem): MediaItem = back?.let { facet.copied(it, shown) } ?: shown

    /** Whether a wall filtered on [status] shows the title as it now stands, a change of it still saving or saved keeping it away. */
    fun stays(status: WatchStatus): Boolean = kept.all { it.stays(status) }
}

/** Of a title's changes, the last saved of its mark and of its favourite. */
private fun List<Held>.lastSaved(): List<Held> =
    Facet.entries.mapNotNull { facet -> lastOrNull { it.facet == facet && it.savedAfter != null } }

/**
 * [facet] of the title as it stood before the last changes of it, of one burst, that were not saved: the source has
 * it so, as no change of it since is saving or saved; null when the last is saving or saved, or none was asked.
 */
private fun List<Held>.notSaved(facet: Facet): MediaItem? {
    val asked = filter { it.facet == facet }
    if (asked.lastOrNull()?.failed != true) return null
    return asked.takeLastWhile { it.failed }.first().before
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
