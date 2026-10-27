package app.houselights.providers.local

import java.text.Normalizer
import java.time.LocalDate

/** How films are grouped into collections. */
object CollectionRules {
    /** Words that only mark a name as a collection, in the languages TMDB and media managers use. */
    private val GENERIC = setOf(
        "collection", "saga", "la", "le", "les", "the", "trilogy", "trilogie", "series", "franchise", "films", "movies",
    )

    /**
     * The TMDB id when known, else the name's significant words; a name with none ("Collection", Japanese script)
     * is keyed whole, so two such sagas do not merge.
     */
    fun key(tmdbId: Int?, name: String): String =
        if (tmdbId != null) "tmdb:$tmdbId" else "name:" + words(name).joinToString(" ").ifEmpty { whole(name) }

    /** True for "Top Gun Collection" and TMDB's French "Top Gun - Saga"; names without words compare whole. */
    fun sameName(a: String, b: String): Boolean {
        val words = words(a)
        val others = words(b)
        return if (words.isEmpty()) others.isEmpty() && whole(a) == whole(b) else words == others
    }

    /** Number of films released by [today] (ISO dates); announced films are not counted. */
    fun released(releaseDates: List<String?>, today: String): Int =
        releaseDates.count { !it.isNullOrBlank() && it <= today }

    fun today(): String = LocalDate.now().toString()

    /** Display name without the saga suffix: TMDB's French "Top Gun - Saga" shows as "Top Gun". */
    fun shownName(name: String): String = name.replace(SAGA_SUFFIX, "").ifBlank { name }

    /** TMDB parts as rows, deduplicated, in release order with undated (announced) films last. */
    fun parts(collectionId: Long, collection: TmdbCollection): List<SagaPartEntity> =
        collection.parts.distinctBy { it.id }
            .sortedWith(compareBy<TmdbSearchResult> { it.releaseDate.isNullOrBlank() }.thenBy { it.releaseDate }.thenBy { it.title })
            .mapIndexed { i, part ->
                SagaPartEntity(
                    collectionId = collectionId, tmdbId = part.id, ord = i, title = part.title ?: part.name.orEmpty(),
                    releaseDate = part.releaseDate?.ifBlank { null }, overview = part.overview?.ifBlank { null },
                    posterPath = part.posterPath, backdropPath = part.backdropPath,
                )
            }

    private fun words(name: String): List<String> =
        Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD)
            .replace(MARKS, "")
            .split(NON_WORD)
            .filter { it.isNotEmpty() && it !in GENERIC }

    /** [name] lowercased, NFC-normalized, whitespace collapsed: "Звёздные  войны" gives "звёздные войны". */
    private fun whole(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC).trim().lowercase().replace(SPACES, " ")

    private val SAGA_SUFFIX = Regex("""\s*[-–]\s*[Ss]aga\s*$""")
    private val MARKS = Regex("\\p{M}")
    private val NON_WORD = Regex("[^a-z0-9]+")
    private val SPACES = Regex("\\s+")
}
