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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.random.Random

/** What home's doors show beyond its rows, read with them. */
data class HomeDoors(
    /** A film not seen yet, which the case proposes when nothing is in progress; the same all session. */
    val unseen: MediaItem? = null,
    /** Each library's door, in the source's order, the sagas apart. */
    val shelves: List<Shelf> = emptyList(),
    val sagas: Shelf? = null,
    /** The episodes of the next episode's season in order, whether each is watched. */
    val season: List<Boolean>? = null,
)

/** A library's door: how many titles it holds ([count], null when unread), its best rated posters, its new episodes. */
data class Shelf(val library: Library, val count: Int?, val posters: List<MediaItem>, val newEpisodes: Int? = null)

/** The film the case proposes when nothing is in progress: drawn at random among those not seen, kept all session. */
class UnseenPick(private val random: Random = Random.Default) {
    @Volatile
    private var kept: String? = null

    /** The film kept while it is still not seen, else a new one drawn from [films]; null when every film is seen. */
    suspend fun of(source: MediaProvider, films: Library?): MediaItem? {
        kept?.let { id -> suspendRunCatching { source.item(id) }.getOrNull()?.takeIf { it.unseen }?.let { return it } }
        val library = films ?: return null
        val unwatched = LibraryFilter(status = WatchStatus.UNWATCHED)
        val first = source.libraryPage(library, SortOption.TITLE, unwatched, 0, 1)
        if (first.total <= 0) return null
        val at = random.nextInt(first.total)
        val drawn = (if (at == 0) first else source.libraryPage(library, SortOption.TITLE, unwatched, at, 1)).items.firstOrNull()
        // The whole title: a wall's page may leave out what the case shows (genres, logo).
        return drawn?.let { suspendRunCatching { source.item(it.id) }.getOrDefault(it) }?.also { kept = it.id }
    }

    private val MediaItem.unseen get() = !user.played && user.positionMs == 0L
}

/** Reads the doors of [libraries] and what [sections] leave out, each part on its own: a failure leaves it out. */
suspend fun readDoors(
    source: MediaProvider,
    libraries: List<Library>,
    sections: List<HomeSection>,
    pick: UnseenPick,
    now: Long = System.currentTimeMillis(),
): HomeDoors = coroutineScope {
    val tabs = libraries.filter { it.kind != LibraryKind.COLLECTIONS }.take(SHELVES)
    val sagas = libraries.firstOrNull { it.kind == LibraryKind.COLLECTIONS }
    val shelves = tabs.map { library -> async { shelf(source, library, SortOption.RATING, SHELF_POSTERS, now) } }
    val sagaShelf = sagas?.let { async { shelf(source, it, SortOption.ADDED, SAGA_POSTERS, now) } }
    val resuming = sections.any { it.type == SectionType.CONTINUE && it.items.isNotEmpty() }
    val unseen = if (resuming) null else async { part("Unseen") { pick.of(source, tabs.firstOrNull { it.kind == LibraryKind.MOVIES }) } }
    val next = sections.firstOrNull { it.type == SectionType.NEXT_UP }?.items?.firstOrNull()
    val season = next?.let { async { part("Season") { seasonOf(source, it) } } }
    HomeDoors(unseen?.await(), shelves.awaitAll(), sagaShelf?.await(), season?.await())
}

private suspend fun shelf(source: MediaProvider, library: Library, sort: SortOption, posters: Int, now: Long): Shelf {
    val page = part("Shelf ${library.kind}") { source.libraryPage(library, sort, LibraryFilter(), 0, posters) }
    val episodes = if (library.kind == LibraryKind.SHOWS) part("New episodes") { source.newEpisodes(library, now - NEW_FOR_MS) } else null
    return Shelf(library, page?.total, page?.items.orEmpty(), episodes)
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
