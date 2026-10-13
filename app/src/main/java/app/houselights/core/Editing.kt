package app.houselights.core

/** The pieces of artwork a user can choose for a title. */
enum class ArtworkKind { POSTER, BACKDROP, LOGO }

/** Where an artwork option comes from. */
enum class ArtworkOrigin {
    /** Image database (TMDB). */
    ONLINE,

    /** A file next to the video, copied into the app. */
    LOCAL_FILE,

    /** A link written in the NFO. */
    NFO,

    /** No image: the title is set in type instead (logos only). */
    NONE,
}

data class ArtworkOption(
    /** What the provider stores when this option is chosen. */
    val value: String,
    val image: ImageRef?,
    val origin: ArtworkOrigin,
    /** ISO 639-1 language of the text on the image; null when it has none or is unknown. */
    val language: String? = null,
    val width: Int = 0,
    val height: Int = 0,
)

data class ArtworkChoices(
    /** The automatic option first, then the others by relevance. */
    val options: List<ArtworkOption>,
    /** What automatic matching uses when the user has not chosen. */
    val automatic: ArtworkOption?,
    /** What is shown now: the user's choice, else [automatic]. */
    val selected: ArtworkOption?,
    /** False when online images could not be listed (title not identified, no key, offline). */
    val onlineAvailable: Boolean,
)

/** A title of the image database the user can match a file with. */
data class MatchCandidate(
    val id: String,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val overview: String?,
    val poster: ImageRef?,
)

/** How a title is identified now, and what to search for by default. */
data class Identity(
    /** The video file, or the show's folder. */
    val fileName: String?,
    val query: String,
    val year: Int?,
    val current: MatchCandidate?,
    /** True when the user chose the match; false when automatic matching did. */
    val manual: Boolean,
    /** False when no image database is configured to search in. */
    val canSearch: Boolean,
)

/**
 * Lets the user correct what automatic matching chose: artwork and identification.
 * Optional: a provider that cannot store such choices returns no editor.
 */
interface MetadataEditor {
    /** Kinds of items this editor can change. */
    fun canEdit(item: MediaItem): Boolean

    suspend fun artwork(item: MediaItem, kind: ArtworkKind): ArtworkChoices

    /** Shows [option] for [item]; choosing the automatic option goes back to automatic. */
    suspend fun setArtwork(item: MediaItem, kind: ArtworkKind, option: ArtworkOption)

    suspend fun identity(item: MediaItem): Identity

    /** Titles matching [query]; an IMDb id ("tt0111161") finds that exact title. */
    suspend fun search(item: MediaItem, query: String, year: Int?): List<MatchCandidate>

    /** Matches [item] with [candidate], or goes back to automatic matching when null. */
    suspend fun identify(item: MediaItem, candidate: MatchCandidate?)
}
