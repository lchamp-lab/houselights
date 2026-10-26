package app.houselights.core

import androidx.annotation.Keep

/** The artwork kinds that can be chosen for a title. Kept from R8 because a navigation route carries it. */
@Keep
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
    /** The option used when none was chosen. */
    val automatic: ArtworkOption?,
    /** The option shown: the chosen one, else [automatic]. */
    val selected: ArtworkOption?,
    /** False when online images could not be listed (title not identified, no key, offline). */
    val onlineAvailable: Boolean,
    /** Why online images could not be listed, so the screen can say what fixes it; null if listed or unidentified. */
    val onlineError: FailureKind? = null,
)

/** An image database title that a file can be matched with. */
data class MatchCandidate(
    val id: String,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val overview: String?,
    val poster: ImageRef?,
)

/** A title's current identification and its default search. */
data class Identity(
    /** The video file, or the show's folder. */
    val fileName: String?,
    val query: String,
    val year: Int?,
    val current: MatchCandidate?,
    /** True when the match was chosen by hand; false when automatic matching made it. */
    val manual: Boolean,
    /** False when no image database is configured to search in. */
    val canSearch: Boolean,
    /** Why automatic matching left it unidentified; null once matched, or left as it is ([kept]). */
    val doubt: MatchDoubt? = null,
    /** Left as it is by the user (« Laisser tel quel »): no longer searched for by its name, nor to identify. */
    val kept: Boolean = false,
    /** What automatic matching hesitated between ([MatchDoubt.UNSURE]), the best first: offered without a search. */
    val suggestions: List<MatchCandidate> = emptyList(),
)

/** Why a film or show is to identify: TMDB found nothing for it, or several titles, none clearly ahead. */
enum class MatchDoubt { NOT_FOUND, UNSURE }

/**
 * A film or show to identify, and why ([doubt]): where it lies ([place], its folder then its file, or its show's folder),
 * and the titles automatic matching hesitated between, the best first ([best]).
 */
data class ToIdentify(
    val item: MediaItem,
    val place: String?,
    val doubt: MatchDoubt,
    val suggestions: List<MatchCandidate> = emptyList(),
) {
    val best: MatchCandidate? get() = suggestions.firstOrNull()
}

/** Corrects automatic matching (artwork, identification); absent from a provider that cannot store choices. */
interface MetadataEditor {
    /** Whether this editor can change [item]. */
    fun canEdit(item: MediaItem): Boolean

    suspend fun artwork(item: MediaItem, kind: ArtworkKind): ArtworkChoices

    /** Shows [option] for [item]; choosing the automatic option reverts to automatic. */
    suspend fun setArtwork(item: MediaItem, kind: ArtworkKind, option: ArtworkOption)

    suspend fun identity(item: MediaItem): Identity

    /** Titles matching [query]; an IMDb id ("tt0111161") finds that exact title. */
    suspend fun search(item: MediaItem, query: String, year: Int?): List<MatchCandidate>

    /** Matches [item] with [candidate], or reverts to automatic matching when null; either undoes [keepUnidentified]. */
    suspend fun identify(item: MediaItem, candidate: MatchCandidate?)

    /** The films and shows to identify, the latest added first; none from an editor that matches nothing itself. */
    suspend fun toIdentify(): List<ToIdentify> = emptyList()

    /** The title to identify after [item] in [toIdentify]'s order, the order it had; null after the last. */
    suspend fun nextToIdentify(item: MediaItem): String? = null

    /**
     * Leaves [item] as it is (« Laisser tel quel »): no longer to identify, nor searched for by its name; an id its name
     * or its NFO gives still applies.
     */
    suspend fun keepUnidentified(item: MediaItem) {}
}
