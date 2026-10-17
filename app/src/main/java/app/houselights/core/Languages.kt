package app.houselights.core

import java.util.Locale

/**
 * Language codes as files, NFO files and servers write them: two letters ("fr"), three by the
 * terminology ("fra") or by the bibliographic form ("fre"), with a region or not ("fr-CA"). One table
 * and one rule, for the sheet that tells tracks apart and the screen that names them.
 */
object Languages {
    /**
     * A language as one code whatever wrote it: "fre", "fra", "fr" and "fr-CA" are all "fr"; a code
     * with no two-letter form stays as it is, in lower case ("und"). Null when none is given.
     */
    fun code(code: String?): String? {
        val primary = code?.substringBefore('-')?.substringBefore('_')?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        // Locale names two-letter codes only: "ger" and "deu" both go by "de".
        return BIBLIOGRAPHIC[primary] ?: TWO_LETTERS[primary] ?: primary
    }

    /** Two-letter language codes by their three-letter terminology code ("deu": "de"). */
    private val TWO_LETTERS: Map<String, String> by lazy {
        Locale.getISOLanguages().associateBy { runCatching { Locale.forLanguageTag(it).isO3Language }.getOrDefault(it) }
    }

    /** ISO 639-2 bibliographic codes, which Locale does not know, as two-letter codes. */
    private val BIBLIOGRAPHIC = mapOf(
        "fre" to "fr", "ger" to "de", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el", "per" to "fa",
        "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "geo" to "ka",
        "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms", "tib" to "bo", "wel" to "cy",
    )
}
