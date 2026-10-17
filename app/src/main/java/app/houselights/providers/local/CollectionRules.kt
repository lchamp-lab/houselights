package app.houselights.providers.local

import java.text.Normalizer
import java.time.LocalDate

/** How films are grouped into collections. */
object CollectionRules {
    /** Words that only say "this is a collection", in the languages TMDB and media managers use. */
    private val GENERIC = setOf("collection", "saga", "la", "le", "les", "the", "trilogy", "trilogie", "series", "franchise", "films", "movies")

    /**
     * One collection, whatever names the NFO and TMDB give it: TMDB's id when known, else its name. A
     * name that leaves no word ("Collection", one in Japanese or Russian) stands whole, normalized: two
     * such sagas would otherwise be one.
     */
    fun key(tmdbId: Int?, name: String): String =
        if (tmdbId != null) "tmdb:$tmdbId" else "name:" + words(name).joinToString(" ").ifEmpty { whole(name) }

    /**
     * "Top Gun Collection" (a media manager) and "Top Gun - Saga" (TMDB in French) are one collection.
     * Names that leave no word ("攻殻機動隊", "Звёздные войны") are compared whole, as [key] keys them.
     */
    fun sameName(a: String, b: String): Boolean {
        val words = words(a)
        val others = words(b)
        return if (words.isEmpty()) others.isEmpty() && whole(a) == whole(b) else words == others
    }

    /** Films of a collection released by [today]; announced ones do not count yet. */
    fun released(releaseDates: List<String?>, today: String): Int =
        releaseDates.count { !it.isNullOrBlank() && it <= today }

    fun today(): String = LocalDate.now().toString()

    /** A saga's name as screens show it: TMDB's French "Top Gun - Saga" is "Top Gun". */
    fun shownName(name: String): String = name.replace(SAGA_SUFFIX, "").ifBlank { name }

    /**
     * The films TMDB lists of a [collection] stored for the library's [collectionId], in the order they came out
     * (those without a date last, announced), each once.
     */
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

    /** [name] in lower case, composed (NFC), its spaces run together: "Звёздные  войны" as "звёздные войны". */
    private fun whole(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC).trim().lowercase().replace(SPACES, " ")

    private val SAGA_SUFFIX = Regex("""\s*[-–]\s*[Ss]aga\s*$""")
    private val MARKS = Regex("\\p{M}")
    private val NON_WORD = Regex("[^a-z0-9]+")
    private val SPACES = Regex("\\s+")
}
