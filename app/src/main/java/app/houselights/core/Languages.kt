package app.houselights.core

import java.text.Normalizer
import java.util.Locale

/** Language codes as files, NFOs and servers write them (ISO 639-1, 639-2/T or /B, with a region or not). */
object Languages {
    /** "fre", "fra", "fr" and "fr-CA" all become "fr"; a code with no two-letter form stays, in lower case ("und"). */
    fun code(code: String?): String? {
        val primary = code?.substringBefore('-')?.substringBefore('_')?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        // Locale knows two-letter and terminology codes only: "ger" and "deu" both map to "de".
        return BIBLIOGRAPHIC[primary] ?: TWO_LETTERS[primary] ?: primary
    }

    /**
     * The language a file's name gives by its last word before its kind and format ("Film.fr.forced.srt",
     * "Film.pt-BR.srt", "Film.french.srt"), as a two-letter code; null when that word is no language.
     */
    fun named(name: String?): String? {
        val words = name?.let { WORD.findAll(it).map { word -> fold(word.value) }.toList() } ?: return null
        val last = words.dropLastWhile { it in NOT_A_LANGUAGE }.lastOrNull() ?: return null
        return code(last)?.takeIf { it in ISO } ?: NAMES[last]
    }

    /** Lower case, without accents: "Français" is "francais". */
    private fun fold(word: String) = Normalizer.normalize(word, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT)

    private val ISO: Set<String> by lazy { Locale.getISOLanguages().toSet() }

    /** Two-letter language codes by their three-letter terminology code ("deu": "de"). */
    private val TWO_LETTERS: Map<String, String> by lazy {
        Locale.getISOLanguages().associateBy { runCatching { Locale.forLanguageTag(it).isO3Language }.getOrDefault(it) }
    }

    /** Two-letter codes by the languages' names in English, in French and in themselves ("francais": "fr"). */
    private val NAMES: Map<String, String> by lazy {
        ISO.flatMap { code ->
            val language = Locale.forLanguageTag(code)
            listOf(Locale.ENGLISH, Locale.FRENCH, language).map { fold(language.getDisplayLanguage(it)) to code }
        }.filter { (name, code) -> name != code && name.all(Char::isLetter) }.distinctBy { it.first }.toMap()
    }

    /** ISO 639-2 bibliographic codes, which Locale does not know, as two-letter codes. */
    private val BIBLIOGRAPHIC = mapOf(
        "fre" to "fr", "ger" to "de", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el", "per" to "fa",
        "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "geo" to "ka",
        "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms", "tib" to "bo", "wel" to "cy",
    )

    /** A word, with its region if it has one ("pt-BR"). */
    private val WORD = Regex("""\p{L}+(?:[-_]\p{L}{2}(?!\p{L}))?""")
    private val MARKS = Regex("""\p{M}+""")

    /** What a subtitle file's name ends with besides its language: its kind, its format, the player's own words. */
    private val NOT_A_LANGUAGE = setOf(
        "forced", "force", "forces", "sdh", "hi", "cc", "full", "complet", "default", "external", "internal",
        "srt", "ass", "ssa", "sub", "sup", "idx", "vtt", "smi", "txt", "subs", "subtitles", "pgs", "vobsub", "dvdsub",
        "vo", "vf", "vff", "vfq", "vost", "vostfr",
    )
}
