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

    private fun words(name: String): List<String> =
        Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD)
            .replace(MARKS, "")
            .split(NON_WORD)
            .filter { it.isNotEmpty() && it !in GENERIC }

    /** [name] in lower case, composed (NFC), its spaces run together: "Звёздные  войны" as "звёздные войны". */
    private fun whole(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC).trim().lowercase().replace(SPACES, " ")

    private val MARKS = Regex("\\p{M}")
    private val NON_WORD = Regex("[^a-z0-9]+")
    private val SPACES = Regex("\\s+")
}
