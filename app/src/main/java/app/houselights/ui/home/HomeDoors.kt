package app.houselights.ui.home

import android.util.Log
import app.houselights.core.HomeSection
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.MediaProvider
import app.houselights.core.NEW_FOR_MS
import app.houselights.core.SectionType
import app.houselights.core.SortOption
import app.houselights.core.WatchStatus
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.random.Random

/** What home's doors show beyond its rows, read with them. */
data class HomeDoors(
    /** What the case proposes when nothing is in progress ([UnseenPick]); read even while something is. */
    val proposed: MediaItem? = null,
    /** Each library's door, in the source's order, the sagas apart. */
    val shelves: List<Shelf> = emptyList(),
    val sagas: Shelf? = null,
    /** The episodes of the next episode's season in order, whether each is watched; read for the episode [seasonOf]. */
    val season: List<Boolean>? = null,
    val seasonOf: String? = null,
    /** False while only [proposed] is read, the rest to come. */
    val shelvesRead: Boolean = true,
)

/** A library's door: how many titles it holds ([count], null when unread), its best rated posters, its new episodes. */
data class Shelf(val library: Library, val count: Int?, val posters: List<MediaItem>, val newEpisodes: Int? = null)

/** Neither watched nor started; a show, while one of its episodes is not watched, as a wall's filter says. */
internal val MediaItem.notSeen: Boolean get() = !user.played && user.positionMs == 0L

/**
 * What the case proposes when nothing is in progress: a film not seen drawn at random, else a show not seen, else any
 * title of the library. Kept all session: one not seen while it stays so, any other while nothing not seen comes.
 */
class UnseenPick(private val random: Random = Random.Default) {
    /** The title kept, and whether it was proposed as not seen. */
    private class Kept(val id: String, val notSeen: Boolean)

    @Volatile
    private var kept: Kept? = null

    /** The title to propose among [libraries]; null when they hold none. */
    suspend fun of(source: MediaProvider, libraries: List<Library>): MediaItem? {
        val last = kept
        // One proposed as not seen goes once seen; any other stays while nothing not seen comes.
        val held = last?.let { k -> suspendRunCatching { source.item(k.id) }.getOrNull()?.takeIf { it.notSeen || !k.notSeen } }
        if (held != null && last.notSeen) return held
        val shelves = libraries.filter { it.kind != LibraryKind.COLLECTIONS }
        val unwatched = LibraryFilter(status = WatchStatus.UNWATCHED)
        val drawn = draw(source, shelves.filter { it.kind == LibraryKind.MOVIES }, unwatched)
            ?: draw(source, shelves.filter { it.kind == LibraryKind.SHOWS }, unwatched)
            ?: held?.let { return it }
            ?: draw(source, shelves, LibraryFilter())
        // The whole title: a wall's page may leave out what the case shows (genres, logo).
        return drawn?.let { suspendRunCatching { source.item(it.id) }.getOrDefault(it) }?.also { kept = Kept(it.id, it.notSeen) }
    }

    /** A title drawn at random from the first of [libraries] holding any by [filter]; null when none does. */
    private suspend fun draw(source: MediaProvider, libraries: List<Library>, filter: LibraryFilter): MediaItem? {
        libraries.forEach { library ->
            val first = source.libraryPage(library, SortOption.TITLE, filter, 0, 1)
            if (first.total > 0) {
                val at = random.nextInt(first.total)
                return (if (at == 0) first else source.libraryPage(library, SortOption.TITLE, filter, at, 1)).items.firstOrNull()
            }
        }
        return null
    }
}

/**
 * Reads the doors of [libraries] and what [sections] leave out, each part on its own: a failure leaves it out. What the
 * case proposes is asked first, read beside the rest and handed to [onProposed] as it comes, so the case shows before
 * the rest.
 */
suspend fun readDoors(
    source: MediaProvider,
    libraries: List<Library>,
    sections: List<HomeSection>,
    pick: UnseenPick,
    now: Long = System.currentTimeMillis(),
    onProposed: (MediaItem?) -> Unit = {},
): HomeDoors = coroutineScope {
    // Read even while a title is in progress: the case proposes it as soon as nothing is. Its reads follow one another,
    // so it asks first: a source answering a few at a time (OkHttp's five a host) answers it before the shelves.
    val proposed = async { part("Proposed") { pick.of(source, libraries) }.also(onProposed) }
    val next = nextUp(sections)
    val season = next?.let { async { part("Season") { seasonOf(source, it) } } }
    val tabs = libraries.filter { it.kind != LibraryKind.COLLECTIONS }.take(SHELVES)
    val sagas = libraries.firstOrNull { it.kind == LibraryKind.COLLECTIONS }
    val shelves = tabs.map { library -> async { shelf(source, library, SortOption.RATING, SHELF_POSTERS, now) } }
    val sagaShelf = sagas?.let { async { shelf(source, it, SortOption.ADDED, SAGA_POSTERS, now) } }
    HomeDoors(proposed.await(), shelves.awaitAll(), sagaShelf?.await(), season?.await(), next?.id.takeIf { season != null })
}

/** A read of home's doors under way: what the case proposes as soon as it is read ([first]), and the doors whole. */
class DoorsRead(val first: Deferred<HomeDoors>, val whole: Deferred<HomeDoors>)

/** Starts reading home's doors in this scope ([readDoors]); [DoorsRead.first] is cancelled if the read ends without it. */
fun CoroutineScope.readDoorsAsync(
    source: MediaProvider,
    libraries: List<Library>,
    sections: List<HomeSection>,
    pick: UnseenPick,
): DoorsRead {
    val first = CompletableDeferred<HomeDoors>()
    val whole = async { readDoors(source, libraries, sections, pick) { first.complete(HomeDoors(it, shelvesRead = false)) } }
    whole.invokeOnCompletion { first.cancel() }
    return DoorsRead(first, whole)
}

/** What follows: the first next episode (or saga film) that is not already in progress, as a server may list it too. */
internal fun nextUp(sections: List<HomeSection>): MediaItem? {
    val resuming = sections.filter { it.type == SectionType.CONTINUE }.flatMap { it.items }.map { it.id }.toSet()
    return sections.firstOrNull { it.type == SectionType.NEXT_UP }?.items?.firstOrNull { it.id !in resuming }
}

private suspend fun shelf(source: MediaProvider, library: Library, sort: SortOption, posters: Int, now: Long): Shelf = coroutineScope {
    val episodes = async {
        if (library.kind == LibraryKind.SHOWS) part("New episodes") { source.newEpisodes(library, now - NEW_FOR_MS) } else null
    }
    val page = part("Shelf ${library.kind}") { source.libraryPage(library, sort, LibraryFilter(), 0, posters) }
    Shelf(library, page?.total, page?.items.orEmpty(), episodes.await())
}

/** Whether each episode of [next]'s season is watched, in order; none for a film or a season too long to read so. */
private suspend fun seasonOf(source: MediaProvider, next: MediaItem): List<Boolean>? {
    if (next.kind != MediaKind.EPISODE) return null
    val show = next.seriesId ?: return null
    val season = next.seasonId ?: return null
    val episodes = source.episodes(MediaItem(show, MediaKind.SERIES, next.seriesTitle.orEmpty()), MediaItem(season, MediaKind.SEASON, ""))
    return episodes.map { it.user.played }.takeIf { it.size in 2..SEASON_SEGMENTS }
}

private suspend fun <T> part(what: String, read: suspend () -> T): T? =
    suspendRunCatching { read() }.onFailure { Log.w(TAG, "$what for home's doors", it) }.getOrNull()

/** Libraries read for doors: four show, a fifth and more gather behind one. */
private const val SHELVES = 12

/** Posters read per library: enough for a count of four digits once those shown elsewhere are left out. */
private const val SHELF_POSTERS = 12
private const val SAGA_POSTERS = 2

/** Past this many episodes, a season's segments would be too thin to read. */
private const val SEASON_SEGMENTS = 24

private const val TAG = "Home"
