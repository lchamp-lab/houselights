package app.houselights.ui.home

import app.houselights.core.HomeSection
import app.houselights.core.Library
import app.houselights.core.MediaItem
import app.houselights.core.NEW_FOR_MS
import app.houselights.core.SectionType
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.components.wearsNewDot

/** What home's case shows: the title last in progress, or a film not seen yet. */
sealed interface CaseShows {
    val item: MediaItem

    data class Resume(override val item: MediaItem) : CaseShows

    data class Unseen(override val item: MediaItem) : CaseShows
}

/** The row of the page « À regarder » a door opens it on. */
enum class WatchRow { CONTINUE, NEXT_UP, LATEST }

/** A door of home's grid: what it shows, and its key in home's focus memory. */
sealed interface Door {
    val key: String

    /** The next episode (or saga film), with its season's episodes watched or not; opens the page on its row. */
    data class Next(val item: MediaItem, val season: List<Boolean>?) : Door {
        override val key get() = NEXT
    }

    /** Without a next episode, the second title in progress; opens the page on its row. */
    data class AlsoResume(val item: MediaItem) : Door {
        override val key get() = NEXT
    }

    /** The latest titles' posters, and how many joined the library this week. */
    data class News(val posters: List<MediaItem>, val thisWeek: Int) : Door {
        override val key get() = NEWS
    }

    /** A library: its count cut in [posters], its [news] (films new to it) or [newEpisodes]. */
    data class Library(val shelf: Shelf, val posters: List<MediaItem>, val news: Int, val newEpisodes: Int?) : Door {
        override val key get() = FocusKey.action("door:library:${shelf.library.id}")
    }

    /** The libraries past the third, gathered behind one door that lists them. */
    data class Libraries(val shelves: List<Shelf>, val posters: List<MediaItem>) : Door {
        override val key get() = LIBRARIES
    }

    data class Sagas(val shelf: Shelf) : Door {
        override val key get() = SAGAS
    }

    data class Search(val posters: List<MediaItem>) : Door {
        override val key get() = SEARCH
    }

    data object Settings : Door {
        override val key get() = SETTINGS
    }
}

/** A row of the grid: what it holds, its doors and the parts of four each takes. */
data class GridRow(val kind: RowKind, val doors: List<Pair<Door, Int>>)

enum class RowKind { WATCH, LIBRARIES, TOOLS }

/** Home's grid: the [case] (none for a library with nothing to propose) and its rows, top to bottom. */
data class HomePlan(val case: CaseShows?, val rows: List<GridRow>) {
    val doors: List<Door> get() = rows.flatMap { row -> row.doors.map { it.first } }

    /** The first library's door, where Right from the case goes. */
    val firstLibrary: Door? get() = rows.firstOrNull { it.kind == RowKind.LIBRARIES }?.doors?.firstOrNull()?.first
}

/**
 * Home's grid from what home read ([sections], the tab [libraries], the source's [sagas], the [doors]), whether it
 * [canSearch], at [now]. The case resumes the title last in progress, else proposes a film not seen; À suivre shows
 * the next episode, else the second title in progress, else the row gives its width to Nouveautés; a library's count
 * is cut in posters shown nowhere else on the screen; without sagas, Rechercher takes their place.
 */
fun homePlan(
    sections: List<HomeSection>,
    libraries: List<Library>,
    sagas: Library?,
    doors: HomeDoors?,
    canSearch: Boolean,
    now: Long = System.currentTimeMillis(),
): HomePlan {
    val resume = sections.firstOrNull { it.type == SectionType.CONTINUE }?.items.orEmpty()
    val next = sections.firstOrNull { it.type == SectionType.NEXT_UP }?.items?.firstOrNull()
    val latest = sections.filter { it.type == SectionType.LATEST }
    val case = resume.firstOrNull()?.let { CaseShows.Resume(it) } ?: doors?.unseen?.let { CaseShows.Unseen(it) }
    // The posters shown so far: a count is cut in none of them.
    val used = mutableSetOf<String>()
    val following = next?.let { Door.Next(it, doors?.season) } ?: resume.getOrNull(1)?.let { Door.AlsoResume(it) }
    val newest = newest(latest)
    val watch = watchDoors(following, newest, used, now)
    val shelves = doors?.shelves?.takeIf { it.isNotEmpty() } ?: libraries.map { Shelf(it, null, emptyList()) }
    val libraryDoors = libraryDoors(shelves, latest, used)
    val wallFrom = (newest + shelves.flatMap { it.posters }).filter { it.poster != null && it.id !in used }.distinctBy { it.id }
    val sagaShelf = doors?.sagas?.takeIf { sagas != null && (it.count ?: 0) > 0 }
    return HomePlan(
        case,
        listOfNotNull(
            GridRow(RowKind.WATCH, watch.map { it to PARTS / watch.size }).takeIf { watch.isNotEmpty() },
            GridRow(RowKind.LIBRARIES, libraryDoors.map { it to 1 }).takeIf { libraryDoors.isNotEmpty() },
            GridRow(RowKind.TOOLS, toolDoors(sagaShelf, canSearch, wallFrom)),
        ),
    )
}

/** What is watched: [following] then Nouveautés, its band of [newest] as wide as the row leaves it, their posters [used]. */
private fun watchDoors(following: Door?, newest: List<MediaItem>, used: MutableSet<String>, now: Long): List<Door> {
    val strip = newest.take(if (following == null) WIDE_STRIP else STRIP)
    used += strip.map { it.id }
    val week = newest.count { (it.addedAt ?: Long.MIN_VALUE) in (now - NEW_FOR_MS)..now }
    return listOfNotNull(following, Door.News(strip, week).takeIf { strip.isNotEmpty() })
}

/** A door per library of [shelves], the fourth gathering the rest past four, each count cut in posters not [used]. */
private fun libraryDoors(shelves: List<Shelf>, latest: List<HomeSection>, used: MutableSet<String>): List<Door> {
    val gathered = shelves.size > SHELF_DOORS
    val shown = if (gathered) shelves.take(SHELF_DOORS - 1) else shelves
    val own = shown.map { shelf ->
        val cut = shelf.count?.let { postersFor(shelf.posters, digitsOf(it), used) }.orEmpty()
        val news = latest.firstOrNull { it.libraryId == shelf.library.id }?.items?.count { it.wearsNewDot() } ?: 0
        Door.Library(shelf, cut, news, shelf.newEpisodes)
    }
    val rest = shelves.drop(SHELF_DOORS - 1).takeIf { gathered }
    return own + listOfNotNull(rest?.let { Door.Libraries(it, postersFor(it.flatMap { shelf -> shelf.posters }, digitsOf(it.size), used)) })
}

/** The tools: Sagas when the source has some, Rechercher when it searches, its wall from [wallFrom], then Réglages. */
private fun toolDoors(sagas: Shelf?, canSearch: Boolean, wallFrom: List<MediaItem>): List<Pair<Door, Int>> = when {
    sagas != null && canSearch -> listOf(Door.Sagas(sagas) to 1, Door.Search(wallFrom.take(WALL)) to 1, Door.Settings to 2)
    sagas != null -> listOf(Door.Sagas(sagas) to 2, Door.Settings to 2)
    canSearch -> listOf(Door.Search(wallFrom.take(WIDE_WALL)) to 2, Door.Settings to 2)
    else -> listOf(Door.Settings to PARTS)
}

/** The latest titles of every library, newest first; titles of unknown date keep their row's order, after the dated. */
internal fun newest(latest: List<HomeSection>): List<MediaItem> {
    val all = latest.flatMap { it.items }.distinctBy { it.id }
    val byDate = compareByDescending<IndexedValue<MediaItem>> { it.value.addedAt ?: Long.MIN_VALUE }.thenBy { it.index }
    return all.withIndex().sortedWith(byDate).map { it.value }
}

/** [count]'s digits, which a door cuts each in a poster. */
internal fun digitsOf(count: Int): Int = count.toString().length

/** Of [posters], the first [digits] with a picture and shown nowhere else ([used]), which they then are. */
private fun postersFor(posters: List<MediaItem>, digits: Int, used: MutableSet<String>): List<MediaItem> =
    posters.filter { it.poster != null && it.id !in used }.distinctBy { it.id }.take(digits).also { picked -> used += picked.map { it.id } }

/** A row's parts: a door of two is half the row, one of one a quarter. */
private const val PARTS = 4

/** Library doors in their row; past it, the last gathers the rest. */
private const val SHELF_DOORS = 4

/** Posters the news door shows across half the row, and across the whole of it. */
private const val STRIP = 5
private const val WIDE_STRIP = 9

/** Small posters the search door shows on a quarter of the row, and on half of it. */
private const val WALL = 8
private const val WIDE_WALL = 16

private val NEXT = FocusKey.action("door:next")
private val NEWS = FocusKey.action("door:news")
private val LIBRARIES = FocusKey.action("door:libraries")
private val SAGAS = FocusKey.action("door:sagas")
private val SEARCH = FocusKey.action("door:search")
private val SETTINGS = FocusKey.action("door:settings")

/** The case's key in home's focus memory. */
val CASE = FocusKey.action("door:case")
