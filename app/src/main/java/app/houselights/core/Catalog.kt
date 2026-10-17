package app.houselights.core

/**
 * A job of a title's credits: its cast, then the crafts its end credits name. Kept by name in the
 * library: the names never change.
 */
enum class Job {
    CAST,
    DIRECTOR,

    /** A show's creators: what its direction is to a film. */
    CREATOR,
    WRITER,
    COMPOSER,
    CINEMATOGRAPHY,
    EDITING,
}

/** A person opens a page once found in this many titles of the library: one of a single title leads nowhere. */
const val PERSON_MIN_TITLES = 2

/**
 * A title's credits: its cast in billing order, and its crew by job, each in the order the title bills
 * them. Each [Person] has its id and how many titles of the library hold it ([Person.titleCount]), when
 * the source tells them.
 */
data class Credits(val cast: List<Person> = emptyList(), val crew: Map<Job, List<Person>> = emptyMap()) {
    /** Everyone of the title found in at least [min] titles of the library, the crew first: those whose page opens. */
    fun recurring(min: Int = PERSON_MIN_TITLES): List<Person> =
        (crew.values.flatten() + cast).filter { (it.titleCount ?: 0) >= min && it.id != null }.distinctBy { it.id }
}

/** One title of a person's page, and what they did on it: [jobs], and as cast, the [role]. */
data class PersonTitle(val item: MediaItem, val jobs: List<Job>, val role: String?)

/**
 * A person's page: who ([person], with their title count), how many titles of the library each of their
 * [jobs] counts, and their [titles], before and behind the camera, in the order of their years (unknown last).
 */
data class PersonPage(val person: Person, val jobs: Map<Job, Int>, val titles: List<PersonTitle>)

/**
 * A film of a saga, in the library ([item]) or not. One the library lacks keeps its place: its [title], its
 * [year], whether it is [released] yet; never its poster.
 */
data class SagaPart(
    val item: MediaItem?,
    val title: String,
    val year: Int?,
    val released: Boolean = true,
    val overview: String? = null,
    val backdrop: ImageRef? = null,
) {
    val owned: Boolean get() = item != null
}

/** A saga whole, in the order its films came out: those the library holds and those it lacks. */
data class Saga(val collection: MediaItem, val parts: List<SagaPart>) {
    /** "4 films sur 6": the films held, out of those released, which a saga known to TMDB alone can tell. */
    val owned: Int get() = parts.count { it.owned }
    val released: Int get() = parts.count { it.released }
}

/**
 * A copy of a film among several ([Catalog.versions]): an edition ("Extended", see [Editions]) or a name its
 * source gives it ([label]), what its file holds, its size and length. [chosen]: the one "Lire" plays, the
 * user's choice ([Catalog.chooseVersion]), else the [best].
 */
data class Version(
    /** This copy as a title: what [Catalog.playback] plays. */
    val item: MediaItem,
    val edition: String?,
    val label: String?,
    val media: MediaInfo?,
    val sizeBytes: Long?,
    val runtimeMs: Long?,
    val chosen: Boolean,
    val best: Boolean,
    /** What names this copy to its source ([Catalog.playback], [Catalog.chooseVersion]). */
    val key: String,
)

/**
 * How many titles of the library share one value ([label]): films and shows apart; [filter] opens them on a
 * wall, when the source can filter by it.
 */
data class Tally(val label: String, val films: Int, val shows: Int, val filter: LibraryFilter? = null, val personId: String? = null)

/**
 * The library in numbers ([Catalog.numbers]): its titles, the room its files take (every copy), the hours
 * they last (each title once), and how many titles have each picture, sound and kind of file, and are by
 * each director. [sizeBytes] and [runtimeMs] are null when the source does not tell them; tallies empty.
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
    /** Kinds of file: "mkv", "iso", [FileTypes.BLU_RAY]… ([FileTypes.label] names them). */
    val files: List<Tally> = emptyList(),
    /** Directors of the most films, at least [PERSON_MIN_TITLES] each: their page opens. */
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
 * What a library tells beyond its titles ([MediaProvider.catalog]): the people in them, its sagas whole, the
 * copies of a film, its numbers, and a search. Every function is called off the main thread's work; what a
 * source cannot tell, its capabilities say ([ProviderCapabilities]), and the function answers empty.
 */
interface Catalog {
    /** [item]'s credits; an episode's or a season's are its show's. */
    suspend fun credits(item: MediaItem): Credits

    /** The page of the person [id] ([Person.id]); null when the library holds none of their titles. */
    suspend fun person(id: String): PersonPage?

    /**
     * The people of [job] found in the most titles of [kind] (any kind when null), at least [minTitles] each,
     * most first: the directors that write home's rows.
     */
    suspend fun people(job: Job, kind: MediaKind? = MediaKind.MOVIE, minTitles: Int = PERSON_MIN_TITLES, limit: Int = 20): List<Person>

    /** The best rated titles of [kind], with [minVotes] votes at least where their number is known. */
    suspend fun bestRated(kind: MediaKind = MediaKind.MOVIE, minVotes: Int = BEST_RATED_MIN_VOTES, limit: Int = 24): List<MediaItem>

    /** [collection]'s saga whole, with the films the library lacks when the source knows them. */
    suspend fun saga(collection: MediaItem): Saga?

    /** The copies of the film [item] is one of, the chosen first; empty for a title of a single copy. */
    suspend fun versions(item: MediaItem): List<Version>

    /** Makes [version] the copy "Lire" plays for [item]'s film; null goes back to the best. */
    suspend fun chooseVersion(item: MediaItem, version: Version?)

    /** Plays [version], one of [versions]. */
    suspend fun playback(version: Version, resume: Boolean, options: PlaybackOptions): PlaybackPlan

    /** The library in numbers, counted once, off the main thread. */
    suspend fun numbers(): LibraryNumbers

    /**
     * Titles, people and sagas whose words start with each word of [query], whatever their accents and case
     * ("amel" finds Amélie), best first, [limit] of each at most.
     */
    suspend fun search(query: String, limit: Int = 24): SearchResults
}

/** Votes a title needs to rank among the best rated, where their number is known. */
const val BEST_RATED_MIN_VOTES = 200

/**
 * Editions of a film, as files and NFOs name them: tokens kept with each copy, which never change; screens
 * name them in their language. One no token knows ("{edition-Fan Cut}") is kept as written.
 */
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

    /** [raw], an edition as written (an NFO's, Plex's "{edition-…}"), as its token; else as written, trimmed. */
    fun of(raw: String): String? {
        val trimmed = raw.trim().ifEmpty { return null }
        val plain = plain(trimmed)
        return NAMES.firstOrNull { (_, names) -> plain in names }?.first ?: find(trimmed) ?: trimmed
    }

    /** Lower case, accents and apostrophes off, any other mark a space. */
    private fun plain(text: String): String =
        java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(MARKS, "").replace(APOSTROPHES, "")
            .replace(NOT_WORDS, " ").trim()

    private val MARKS = Regex("""\p{M}+""")
    private val APOSTROPHES = Regex("""['’]""")
    private val NOT_WORDS = Regex("""[^\p{L}\p{N}]+""")
}

/**
 * Kinds of file, as the library in numbers and the file filter name them: an extension in lower case
 * ("mkv", "iso"), or a disc's folder ([BLU_RAY], [DVD]).
 */
object FileTypes {
    /** A Blu-ray's folder (BDMV), played as its main stream. */
    const val BLU_RAY = "bdmv"

    /** A DVD's folder (VIDEO_TS). */
    const val DVD = "video_ts"

    /** How a kind of file is shown: "MKV", "Blu-ray", "DVD". */
    fun label(type: String): String = when (type) {
        BLU_RAY -> "Blu-ray"
        DVD -> "DVD"
        else -> type.uppercase()
    }
}
