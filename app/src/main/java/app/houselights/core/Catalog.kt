package app.houselights.core

import java.time.LocalDate

/** A credited job: the cast, then the crafts end credits name. Stored by name, so names never change. */
enum class Job {
    CAST,
    DIRECTOR,

    /** A show's creators, the series counterpart of a film's director. */
    CREATOR,
    WRITER,
    COMPOSER,
    CINEMATOGRAPHY,
    EDITING,
}

/** Library titles a person must appear in for their page to open: a page of a single title leads nowhere. */
const val PERSON_MIN_TITLES = 2

/** Whether this person's page opens: they have an id and appear in at least [PERSON_MIN_TITLES] library titles. */
val Person.hasPage: Boolean get() = id != null && (titleCount ?: 0) >= PERSON_MIN_TITLES

/** A title's cast and crew by job, in billing order; [Person.id] and [Person.titleCount] when the source has them. */
data class Credits(val cast: List<Person> = emptyList(), val crew: Map<Job, List<Person>> = emptyMap()) {
    /** People of this title found in at least [min] library titles, crew first: those whose page opens. */
    fun recurring(min: Int = PERSON_MIN_TITLES): List<Person> =
        (crew.values.flatten() + cast).filter { (it.titleCount ?: 0) >= min && it.id != null }.distinctBy { it.id }
}

/** One title on a person's page, with their [jobs] on it and, for cast, their [role]. */
data class PersonTitle(val item: MediaItem, val jobs: List<Job>, val role: String?)

/** A person's page: [person], library title counts per job ([jobs]), and their [titles] by year (unknown last). */
data class PersonPage(val person: Person, val jobs: Map<Job, Int>, val titles: List<PersonTitle>)

/**
 * A saga's film, owned ([item]) or not; a missing one keeps its [title], [year], [released] state, TMDB's [poster]
 * and its [releaseDate] (null when TMDB dates it not, or the source lists only the films it holds).
 */
data class SagaPart(
    val item: MediaItem?,
    val title: String,
    val year: Int?,
    val released: Boolean = true,
    val overview: String? = null,
    val backdrop: ImageRef? = null,
    val poster: ImageRef? = item?.poster,
    val releaseDate: LocalDate? = null,
) {
    val owned: Boolean get() = item != null
}

/** A whole saga in release order: the films the library holds and those it lacks. */
data class Saga(val collection: MediaItem, val parts: List<SagaPart>) {
    /** "4 films sur 6": films held out of those released; only a saga known to TMDB knows the films it lacks. */
    val owned: Int get() = parts.count { it.owned }
    val released: Int get() = parts.count { it.released }
}

/**
 * One copy of a film ([Catalog.versions]): its edition ([Editions]) or source [label], media, size and length.
 * [chosen] is the copy "Lire" plays: the one picked with [Catalog.chooseVersion], else the source's own (a folder's: the
 * copy started and not watched that was played last, else the [best]).
 */
data class Version(
    /** This copy as a title, as [Catalog.playback] plays it. */
    val item: MediaItem,
    val edition: String?,
    val label: String?,
    val media: MediaInfo?,
    val sizeBytes: Long?,
    val runtimeMs: Long?,
    val chosen: Boolean,
    val best: Boolean,
    /** Identifies this copy to its source ([Catalog.playback], [Catalog.chooseVersion]). */
    val key: String,
)

/** Library titles sharing one value ([label]), films and shows apart; [filter] opens them on a wall if supported. */
data class Tally(val label: String, val films: Int, val shows: Int, val filter: LibraryFilter? = null, val personId: String? = null)

/**
 * The library in numbers ([Catalog.numbers]): counts, disk space (every copy), runtime (each title once) and
 * tallies. [sizeBytes] and [runtimeMs] are null, and tallies empty, when the source cannot provide them.
 */
data class LibraryNumbers(
    val films: Int,
    val shows: Int,
    val episodes: Int,
    val sizeBytes: Long?,
    val runtimeMs: Long?,
    /** Definitions, then ranges: "4K", "Dolby Vision"… (badge tokens, [Definition.label] names a definition). */
    val picture: List<Tally> = emptyList(),
    /** Best sound of each title: "TrueHD Atmos", "DTS-HD MA"… (badge tokens, [Formats.soundName] names them). */
    val sound: List<Tally> = emptyList(),
    /** File types: "mkv", "iso", [FileTypes.BLU_RAY]… ([FileTypes.label] names them). */
    val files: List<Tally> = emptyList(),
    /** Directors with the most films, at least [PERSON_MIN_TITLES] each, so their page opens. */
    val directors: List<Tally> = emptyList(),
)

/** What a search found ([Catalog.search]): titles, people, then sagas, each best first. */
data class SearchResults(
    val titles: List<MediaItem> = emptyList(),
    val people: List<Person> = emptyList(),
    val sagas: List<MediaItem> = emptyList(),
) {
    fun isEmpty() = titles.isEmpty() && people.isEmpty() && sagas.isEmpty()
}

/**
 * What a library offers beyond its titles ([MediaProvider.catalog]). Every function is main-safe; what a
 * source cannot provide is declared in [ProviderCapabilities] and returns empty.
 */
interface Catalog {
    /** [item]'s credits; an episode's or a season's are its show's. */
    suspend fun credits(item: MediaItem): Credits

    /** The page of the person [id] ([Person.id]); null when the library holds none of their titles. */
    suspend fun person(id: String): PersonPage?

    /** People of [job] in the most titles of [kind] (any when null), at least [minTitles] each, for home's rows. */
    suspend fun people(job: Job, kind: MediaKind? = MediaKind.MOVIE, minTitles: Int = PERSON_MIN_TITLES, limit: Int = 20): List<Person>

    /** Best-rated titles of [kind], with at least [minVotes] votes where the count is known. */
    suspend fun bestRated(kind: MediaKind = MediaKind.MOVIE, minVotes: Int = BEST_RATED_MIN_VOTES, limit: Int = 24): List<MediaItem>

    /** [collection]'s whole saga, including films the library lacks when the source knows them. */
    suspend fun saga(collection: MediaItem): Saga?

    /** Every copy of [item]'s film, in an order a choice leaves as it is; empty when there is a single copy. */
    suspend fun versions(item: MediaItem): List<Version>

    /** Makes [version] the copy "Lire" plays for [item]'s film; null leaves it to the source again. */
    suspend fun chooseVersion(item: MediaItem, version: Version?)

    /** The library in numbers, computed once, off the main thread. */
    suspend fun numbers(): LibraryNumbers

    /**
     * Titles, people and sagas whose words start with each word of [query], whatever their accents and case
     * ("amel" finds Amélie), best first, [limit] of each at most.
     */
    suspend fun search(query: String, limit: Int = 24): SearchResults
}

/** Votes a title needs to rank among the best rated, where their number is known. */
const val BEST_RATED_MIN_VOTES = 200

/** Stable edition tokens, which screens translate; an unknown edition ("{edition-Fan Cut}") stays as written. */
object Editions {
    const val EXTENDED = "Extended"
    const val DIRECTORS_CUT = "Director's Cut"
    const val THEATRICAL = "Theatrical"
    const val UNRATED = "Unrated"
    const val UNCUT = "Uncut"
    const val REMASTERED = "Remastered"
    const val IMAX = "IMAX"
    const val FINAL_CUT = "Final Cut"
    const val ULTIMATE = "Ultimate"
    const val SPECIAL = "Special Edition"
    const val CRITERION = "Criterion"
    const val ANNIVERSARY = "Anniversary"
    const val OPEN_MATTE = "Open Matte"

    /** Words that name each edition, folded and without punctuation, longest first in each. */
    private val NAMES = listOf(
        DIRECTORS_CUT to listOf("directors cut", "director cut", "montage du realisateur", "version du realisateur"),
        FINAL_CUT to listOf("final cut"),
        EXTENDED to listOf("extended cut", "extended edition", "extended version", "version longue", "extended"),
        THEATRICAL to listOf("theatrical cut", "theatrical edition", "theatrical version", "version cinema", "theatrical"),
        ULTIMATE to listOf("ultimate cut", "ultimate edition"),
        SPECIAL to listOf("special edition", "edition speciale"),
        ANNIVERSARY to listOf("anniversary edition", "anniversary"),
        UNRATED to listOf("unrated"),
        UNCUT to listOf("uncut", "version integrale"),
        REMASTERED to listOf("remastered", "remaster", "restored", "version restauree"),
        IMAX to listOf("imax enhanced", "imax edition", "imax"),
        CRITERION to listOf("criterion collection", "criterion"),
        OPEN_MATTE to listOf("open matte"),
    )

    /** The edition [text] names among its words ("Aliens 1986 Special Edition 1080p"); null for none. */
    fun find(text: String): String? {
        val words = " " + plain(text) + " "
        return NAMES.firstOrNull { (_, names) -> names.any { " $it " in words } }?.first
    }

    /** Token for an edition as written (an NFO's, Plex's "{edition-…}"); an unknown one is returned trimmed. */
    fun of(raw: String): String? {
        val trimmed = raw.trim().ifEmpty { return null }
        val plain = plain(trimmed)
        return NAMES.firstOrNull { (_, names) -> plain in names }?.first ?: find(trimmed) ?: trimmed
    }

    /** Lower-cases, strips accents and apostrophes, and turns other punctuation into spaces. */
    private fun plain(text: String): String =
        java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(MARKS, "").replace(APOSTROPHES, "")
            .replace(NOT_WORDS, " ").trim()

    private val MARKS = Regex("""\p{M}+""")
    private val APOSTROPHES = Regex("""['’]""")
    private val NOT_WORDS = Regex("""[^\p{L}\p{N}]+""")
}

/** File types: a lower-case extension ("mkv", "iso") or a disc folder ([BLU_RAY], [DVD]). */
object FileTypes {
    /** A Blu-ray folder (BDMV), played as its main stream. */
    const val BLU_RAY = "bdmv"

    /** A DVD folder (VIDEO_TS). */
    const val DVD = "video_ts"

    /** Display name of a file type: "MKV", "Blu-ray", "DVD". */
    fun label(type: String): String = when (type) {
        BLU_RAY -> "Blu-ray"
        DVD -> "DVD"
        else -> type.uppercase()
    }
}
