package app.houselights.providers.local

/**
 * Who a credit names, across the library: "tmdb:525" when TMDB names the person, else "name:christopher nolan",
 * their name folded ([fold]). The key is the person's id on screens ([app.houselights.core.Person.id]).
 */
object PersonKeys {
    const val TMDB = "tmdb:"
    const val NAME = "name:"

    fun tmdb(id: Int): String = "$TMDB$id"

    fun name(folded: String): String = "$NAME$folded"

    /** A name as keys and searches compare it: folded ([MetadataMerge.fold]), its spaces made one. */
    fun fold(name: String): String = MetadataMerge.fold(name).trim().replace(SPACES, " ")

    private val SPACES = Regex("""\s+""")
}

/**
 * The words a search finds titles, people and sagas by ([SearchWordEntity]): their names folded as sort keys are
 * ([MetadataMerge.fold]), cut at whatever is no letter nor digit. A word written with marks inside counts joined
 * too: "Spider-Man" is "spider", "man" and "spiderman"; "S.H.I.E.L.D." is "shield".
 */
object SearchWords {
    /** The words of [names], each once. */
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

    /** What is typed, as words a search reads the start of: folded, each once, in order. */
    fun query(text: String): List<String> =
        MetadataMerge.fold(text).split(NOT_WORDS).filter { it.isNotEmpty() }.distinct()

    /** The rows of [kind] for [ref], one per word of [names]. */
    fun rows(kind: SearchKind, ref: String, vararg names: String?): List<SearchWordEntity> =
        of(*names).map { SearchWordEntity(kind, it, ref) }

    /**
     * The first text after every word [prefix] starts: "amem" for "amel". Words compare as SQLite compares
     * text, by their UTF-8 bytes, whose order is that of their characters.
     */
    fun upperBound(prefix: String): String = prefix.dropLast(1) + (prefix.last() + 1)

    private val SPACES = Regex("""\s+""")
    private val NOT_WORDS = Regex("""[^\p{L}\p{N}]+""")
}
