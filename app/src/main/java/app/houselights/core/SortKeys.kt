package app.houselights.core

import java.text.Normalizer

/** How titles sort: one rule for the library's sort keys and for what the screens say of a title's place. */
object SortKeys {
    /** Articles titles start with, which sorting passes over; l' and l’ besides, glued to their word. */
    const val ARTICLE_WORDS = "the|a|an|le|la|les|un|une|des"

    /** A leading article, l' and l’ glued to the word ("L'Arnaque") as well. */
    private val ARTICLES = Regex("""(?i)^(?:(?:$ARTICLE_WORDS)\s+|l['’]\s*)""")
    private val MARKS = Regex("""\p{M}+""")

    /** Letters that NFD does not take apart into a base letter and its accent. */
    private val LIGATURES = mapOf('œ' to "oe", 'æ' to "ae", 'ß' to "ss", 'ø' to "o", 'ł' to "l", 'đ' to "d", 'ð' to "d", 'þ' to "th")

    /**
     * Lower case, without a leading article, accents and ligatures folded: "The Batman" sorts with B,
     * "L'Arnaque" with A, "Ça" with C and "Œdipe" with O, not after Z as their UTF-8 bytes would.
     */
    fun sortKey(title: String): String = fold(ARTICLES.replace(title.trim(), ""))

    /** [text] in lower case, accents dropped and ligatures spelled out: what [sortKey] does past the article, alone. */
    fun fold(text: String): String {
        val bare = MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")
        return if (bare.none { it in LIGATURES }) bare else buildString { bare.forEach { append(LIGATURES[it] ?: it) } }
    }

    /** The letter a title is filed under in a wall sorted by title ("B" for "The Batman"), "#" for a figure. */
    fun initial(title: String): String? =
        sortKey(title).firstOrNull { it.isLetterOrDigit() }?.let { if (it.isDigit()) "#" else it.uppercase() }
}
