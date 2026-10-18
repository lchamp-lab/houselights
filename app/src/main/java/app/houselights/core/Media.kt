package app.houselights.core

import androidx.annotation.Keep

/** Kind of a library entry, independent of the backend it comes from. */
enum class MediaKind { MOVIE, SERIES, SEASON, EPISODE, COLLECTION, FOLDER, VIDEO }

/** Kept from R8 because a navigation route carries it. */
@Keep
enum class LibraryKind { MOVIES, SHOWS, COLLECTIONS, MIXED }

/** An image backends can request at any pixel width (server resize, CDN buckets); equality uses [key] only. */
class ImageRef(private val key: String, private val build: (widthPx: Int) -> String) {
    fun url(widthPx: Int): String = build(widthPx)
    override fun equals(other: Any?) = other is ImageRef && other.key == key
    override fun hashCode() = key.hashCode()
    override fun toString() = "ImageRef($key)"
}

/** Someone credited, with their [role] if cast; [id] opens their page, [titleCount] counts their library titles. */
data class Person(val name: String, val role: String?, val image: ImageRef?, val id: String? = null, val titleCount: Int? = null)

data class UserState(
    val played: Boolean = false,
    val favorite: Boolean = false,
    val positionMs: Long = 0,
    /** For series and seasons. */
    val unplayedCount: Int? = null,
)

/** How long a title added to a library is marked new: a week. */
const val NEW_FOR_MS = 7L * 24 * 60 * 60 * 1000

data class MediaItem(
    val id: String,
    val kind: MediaKind,
    val title: String,
    val originalTitle: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val year: Int? = null,
    val endYear: Int? = null,
    val continuing: Boolean = false,
    val runtimeMs: Long? = null,
    val officialRating: String? = null,
    val communityRating: Double? = null,
    val genres: List<String> = emptyList(),
    val cast: List<Person> = emptyList(),
    val directors: List<String> = emptyList(),
    /** Short technical labels: 4K, Dolby Vision, TrueHD Atmos... */
    val badges: List<String> = emptyList(),
    /** What the file contains, when known (playable items only). */
    val media: MediaInfo? = null,
    /** A Kodi-style NFO describes this item ([MediaProvider.nfo] returns it). */
    val hasNfo: Boolean = false,
    /** Name of the video file, when the backend knows it. */
    val fileName: String? = null,
    /** A multi-part film's files (CD1, CD2…) in order, the first included, each played on its own; else empty. */
    val parts: List<MediaItem> = emptyList(),
    val poster: ImageRef? = null,
    val backdrop: ImageRef? = null,
    val logo: ImageRef? = null,
    /** 16:9 artwork (episode still, thumb). */
    val thumb: ImageRef? = null,
    val user: UserState = UserState(),
    val seriesId: String? = null,
    val seriesTitle: String? = null,
    val seasonId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    /** The last episode a file holds when it holds several ("S01E01-E02"); null for one. */
    val lastEpisodeNumber: Int? = null,
    val childCount: Int? = null,
    /** For a collection: its films released so far, owned or not, when known. */
    val totalCount: Int? = null,
    /** When it joined its library (epoch ms); unset for what a local folder's first scan found. */
    val addedAt: Long? = null,
    /** This copy's edition ([Editions]: "Extended"…), when its file or NFO names one. */
    val edition: String? = null,
) {
    val isPlayable get() = kind == MediaKind.MOVIE || kind == MediaKind.EPISODE || kind == MediaKind.VIDEO

    /** Added to a library in the last [NEW_FOR_MS], and not watched yet. */
    fun isNew(now: Long = System.currentTimeMillis()): Boolean = addedAt != null && !user.played && now - addedAt in 0 until NEW_FOR_MS

    val progress: Float
        get() {
            val total = runtimeMs ?: return 0f
            return if (user.positionMs > 0 && total > 0) (user.positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f
        }

    /** "S1 E3" for an episode, "S1 E1-E2" for a file holding two. */
    val episodeLabel: String?
        get() = episodeNumbers("E")?.let { "S${seasonNumber ?: 0} E$it" }

    /** "3" for an episode, "1-2" for a file holding two, as its card numbers it. */
    val episodeNumbers: String?
        get() = episodeNumbers("")

    private fun episodeNumbers(prefix: String): String? {
        val first = episodeNumber?.takeIf { kind == MediaKind.EPISODE } ?: return null
        return lastEpisodeNumber?.takeIf { it > first }?.let { "$first-$prefix$it" } ?: "$first"
    }

    /** Best 16:9 artwork for landscape cards. */
    val landscape: ImageRef? get() = thumb ?: backdrop
}

data class Library(val id: String, val kind: LibraryKind, val title: String?)

/** Home's rows: the standard ones, then the library's own below them ([DIRECTOR], [BEST_RATED]). */
enum class SectionType { CONTINUE, NEXT_UP, LATEST, DIRECTOR, BEST_RATED }

data class HomeSection(
    val type: SectionType,
    val items: List<MediaItem>,
    val landscape: Boolean,
    /** Library of a [SectionType.LATEST] section: its name when the backend has one, else its kind. */
    val libraryName: String? = null,
    val libraryKind: LibraryKind? = null,
    /** Library of a [SectionType.LATEST] section; with [type], identifies the row. */
    val libraryId: String? = null,
    /** Whose films a [SectionType.DIRECTOR] row holds. */
    val person: Person? = null,
)

enum class SortOption { TITLE, ADDED, YEAR, RATING }

enum class WatchStatus { ALL, UNWATCHED, IN_PROGRESS, WATCHED, FAVORITES }

enum class Quality { UHD_4K, HDR, DOLBY_VISION }

/** A grid's filters, all optional and combined; a film matches what any copy has, a show what any episode has. */
data class LibraryFilter(
    val status: WatchStatus = WatchStatus.ALL,
    val genre: String? = null,
    /** First year of the decade, e.g. 2010. */
    val decade: Int? = null,
    val quality: Quality? = null,
    /** A picture format as badges keep it ("1080p", "HDR10+", see [Definition] and [HdrFormat]): finer than [quality]. */
    val picture: String? = null,
    /** A sound format as badges keep it: the best track of a file ("TrueHD Atmos", "DTS-HD MA", "E-AC3"). */
    val sound: String? = null,
    /** A file type ([FileTypes]): "mkv", "iso", a Blu-ray folder. */
    val fileType: String? = null,
    /** A person ([Person.id]): the titles they are in, in front of or behind the camera. */
    val person: String? = null,
) {
    val activeCount: Int
        get() = listOf(
            status != WatchStatus.ALL, genre != null, decade != null, quality != null, picture != null, sound != null,
            fileType != null, person != null,
        ).count { it }
}

/** Values actually present in a library, so filters never offer an empty choice. */
data class LibraryFacets(
    val genres: List<String> = emptyList(),
    val decades: List<Int> = emptyList(),
    val qualities: List<Quality> = emptyList(),
    /** Picture formats of its files ([LibraryFilter.picture]), best first. */
    val pictures: List<String> = emptyList(),
    /** Sound formats of its files ([LibraryFilter.sound]), most common first. */
    val sounds: List<String> = emptyList(),
    /** File types ([LibraryFilter.fileType]), most common first. */
    val fileTypes: List<String> = emptyList(),
)

data class Page(val items: List<MediaItem>, val total: Int)
