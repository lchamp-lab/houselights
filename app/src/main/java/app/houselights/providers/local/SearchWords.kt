package app.houselights.providers.local

/**
 * Library-wide person keys: "tmdb:525" when TMDB knows the person, else "name:christopher nolan" (folded, [fold]).
 * Screens use the key as [app.houselights.core.Person.id].
 */
object PersonKeys {
    const val TMDB = "tmdb:"
    const val NAME = "name:"

    fun tmdb(id: Int): String = "$TMDB$id"

    fun name(folded: String): String = "$NAME$folded"

    /** [MetadataMerge.fold] with whitespace collapsed, as keys and searches compare names. */
    fun fold(name: String): String = MetadataMerge.fold(name).trim().replace(SPACES, " ")

    private val SPACES = Regex("""\s+""")
}

/**
 * Search words of titles, people and sagas ([SearchWordEntity]): names folded ([MetadataMerge.fold]) and split on
 * non-alphanumerics, plus the joined form: "Spider-Man" gives "spider", "man", "spiderman"; "S.H.I.E.L.D." "shield".
 */
object SearchWords {
    fun of(vararg names: String?): Set<String> = buildSet {
        for (name in names) {
            if (name.isNullOrBlank()) continue
            for (token in MetadataMerge.fold(name).split(SPACES)) {
                val parts = token.split(NOT_WORDS).filter { it.isNotEmpty() }
                addAll(parts)
                if (parts.size > 1) add(parts.joinToString(""))
            }
        }
    }

    /** Query words to prefix-match: folded, deduplicated, in order. */
    fun query(text: String): List<String> =
        MetadataMerge.fold(text).split(NOT_WORDS).filter { it.isNotEmpty() }.distinct()

    fun rows(kind: SearchKind, ref: String, vararg names: String?): List<SearchWordEntity> =
        of(*names).map { SearchWordEntity(kind, it, ref) }

    /** Exclusive upper bound for [prefix] ("amem" for "amel"); SQLite's UTF-8 order matches char order. */
    fun upperBound(prefix: String): String = prefix.dropLast(1) + (prefix.last() + 1)

    private val SPACES = Regex("""\s+""")
    private val NOT_WORDS = Regex("""[^\p{L}\p{N}]+""")
}
