package app.houselights.core

import java.util.Locale

/** Language codes as files, NFOs and servers write them (ISO 639-1, 639-2/T or /B, with a region or not). */
object Languages {
    /** "fre", "fra", "fr" and "fr-CA" all become "fr"; a code with no two-letter form stays, in lower case ("und"). */
    fun code(code: String?): String? {
        val primary = code?.substringBefore('-')?.substringBefore('_')?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        // Locale knows two-letter and terminology codes only: "ger" and "deu" both map to "de".
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
