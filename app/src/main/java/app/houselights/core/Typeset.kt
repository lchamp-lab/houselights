package app.houselights.core

/**
 * Keeps punctuation with its word in text written elsewhere (TMDB, NFO, servers): a plain space
 * before « » : ; ! ? or after « becomes a no-break space, so a line never starts with "»" or ":".
 */
object Typeset {
    private val BEFORE = Regex(""" (?=[»:;!?])""")
    private val AFTER = Regex("""(?<=«) """)

    fun text(value: String?): String? = value?.replace(BEFORE, Typography.nbsp.toString())?.replace(AFTER, Typography.nbsp.toString())
}
