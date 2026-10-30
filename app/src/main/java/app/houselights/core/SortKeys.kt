package app.houselights.core

import java.text.Normalizer

/** Title sorting: one rule for the library's sort keys and for the letter screens file a title under. */
object SortKeys {
    /** Leading articles that sorting skips, besides l' and l’, which attach to their word. */
    const val ARTICLE_WORDS = "the|a|an|le|la|les|un|une|des"

    /** A leading article, l' and l’ glued to the word ("L'Arnaque") as well. */
    private val ARTICLES = Regex("""(?i)^(?:(?:$ARTICLE_WORDS)\s+|l['’]\s*)""")
    private val MARKS = Regex("""\p{M}+""")

    /** Letters that NFD does not take apart into a base letter and its accent. */
    private val LIGATURES = mapOf('œ' to "oe", 'æ' to "ae", 'ß' to "ss", 'ø' to "o", 'ł' to "l", 'đ' to "d", 'ð' to "d", 'þ' to "th")

    /** Folded, without a leading article: "The Batman" sorts with B, "Œdipe" with O, not after Z as UTF-8 would. */
    fun sortKey(title: String): String = fold(ARTICLES.replace(title.trim(), ""))

    /** [text] in lower case, accents dropped and ligatures spelled out: [sortKey] without the article rule. */
    fun fold(text: String): String {
        val bare = foldAccents(text)
        return if (bare.none { it in LIGATURES }) bare else buildString { bare.forEach { append(LIGATURES[it] ?: it) } }
    }

    /**
     * [text] in lower case, accents dropped, ligatures kept as written: [fold] for what is stored from it (a saga's
     * key, a copy's edition), which must not move without a migration.
     */
    fun foldAccents(text: String): String = MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")

    /** The letter a title is filed under in a wall sorted by title ("B" for "The Batman"), "#" for a digit. */
    fun initial(title: String): String? =
        sortKey(title).firstOrNull { it.isLetterOrDigit() }?.let { if (it.isDigit()) "#" else it.uppercase() }
}
