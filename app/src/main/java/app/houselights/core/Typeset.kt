package app.houselights.core

import java.util.Locale

/**
 * Sets text written elsewhere (TMDB, NFO, servers) the way the interface writes its own, for
 * display only: what is sorted or searched stays as written.
 * - An apostrophe between two letters is a typographic one: l'homme, O'Toole, don't.
 * - Three dots are an ellipsis.
 * - In French, a balanced pair of straight double quotes becomes « … », a no-break space inside.
 * - A plain space before » : ; ! ? or after « becomes a no-break space, so a line never starts
 *   with "»" or ":".
 *
 * Single quotes are never paired: they elide as often as they quote (Rock 'n' Roll, 'til).
 */
object Typeset {
    private const val NBSP = Typography.nbsp
    private val APOSTROPHE = Regex("""(?<=\p{L})'(?=\p{L})""")
    private val ELLIPSIS = Regex("""\.\.\.""")

    /** A quote opening after a space or punctuation and closing before one, never 12" or 5'10". */
    private val QUOTED = Regex("""(?<![\p{L}\p{N}])"\s*([^"\s](?:[^"]*[^"\s])?)\s*"(?![\p{L}\p{N}])""")
    private val BEFORE = Regex(""" (?=[»:;!?])""")
    private val AFTER = Regex("""(?<=«) """)

    fun text(value: String, french: Boolean = isFrench()): String {
        var s = value.replace(APOSTROPHE, "’").replace(ELLIPSIS, "…")
        if (french && s.count { it == '"' } % 2 == 0) s = s.replace(QUOTED) { "«$NBSP${it.groupValues[1]}$NBSP»" }
        return s.replace(BEFORE, NBSP.toString()).replace(AFTER, NBSP.toString())
    }

    @JvmName("textOrNull")
    fun text(value: String?, french: Boolean = isFrench()): String? = value?.let { text(it, french) }

    /** Text from the services is in the device language: they are asked for it. */
    private fun isFrench() = Locale.getDefault().language == Locale.FRENCH.language
}
