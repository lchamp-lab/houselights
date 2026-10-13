package app.houselights.core

/** Kind of a library entry, independent of the backend it comes from. */
enum class MediaKind { MOVIE, SERIES, SEASON, EPISODE, COLLECTION, FOLDER, VIDEO }

enum class LibraryKind { MOVIES, SHOWS, COLLECTIONS, MIXED }

/**
 * Lazily sized image: backends know how to request an image at a given pixel width
 * (server-side resize, CDN size buckets...). Equality is based on [key] only.
 */
class ImageRef(private val key: String, private val build: (widthPx: Int) -> String) {
    fun url(widthPx: Int): String = build(widthPx)
    override fun equals(other: Any?) = other is ImageRef && other.key == key
    override fun hashCode() = key.hashCode()
    override fun toString() = "ImageRef($key)"
}

data class Person(val name: String, val role: String?, val image: ImageRef?)

data class UserState(
    val played: Boolean = false,
    val favorite: Boolean = false,
    val positionMs: Long = 0,
    /** For series and seasons. */
    val unplayedCount: Int? = null,
)

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
    /** Short technical labels: 4K, DOLBY VISION, ATMOS... */
    val badges: List<String> = emptyList(),
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
    val childCount: Int? = null,
    /** True when built from a lightweight list response; [MediaProvider.item] returns the full entry. */
    val partial: Boolean = false,
) {
    val isPlayable get() = kind == MediaKind.MOVIE || kind == MediaKind.EPISODE || kind == MediaKind.VIDEO

    val progress: Float
        get() {
            val total = runtimeMs ?: return 0f
            return if (user.positionMs > 0 && total > 0) (user.positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f
        }

    /** "S1 E3" for an episode. */
    val episodeLabel: String?
        get() = if (kind == MediaKind.EPISODE && episodeNumber != null) "S${seasonNumber ?: 0} E$episodeNumber" else null

    /** Best 16:9 artwork for landscape cards. */
    val landscape: ImageRef? get() = thumb ?: backdrop
}

data class Library(val id: String, val kind: LibraryKind, val title: String?)

enum class SectionType { CONTINUE, NEXT_UP, LATEST, RECENT, FAVORITES, UNWATCHED }

data class HomeSection(
    val type: SectionType,
    val items: List<MediaItem>,
    val landscape: Boolean,
    /** Library of a [SectionType.LATEST] section: its name when the backend has one, else its kind. */
    val libraryName: String? = null,
    val libraryKind: LibraryKind? = null,
)

enum class SortOption { TITLE, ADDED, YEAR, RATING }

data class Page(val items: List<MediaItem>, val total: Int)
