package app.houselights.core

/**
 * How a technical sheet names a track beyond its language and format: the region of its language
 * (a French dub from France or from Québec), and its title when it adds to what the sheet already
 * says ("Commentaire audio…", "Version restaurée"), never when it only repeats it ("VFF DD 5.1
 * 384 kbps", "ENG : Dolby TrueHD Atmos 7.1").
 */
object TrackNames {
    /**
     * The region of a track's language, as a region code: its tag's ("fr-CA", "es-419"), else the
     * dub its title names (VFQ: Québec, VFF: France). Null when nothing says it.
     */
    fun region(language: String?, title: String?): String? {
        REGION.find(language.orEmpty())?.let { return it.groupValues[1].uppercase() }
        val words = words(title.orEmpty())
        return when {
            "vfq" in words -> "CA"
            "vff" in words || "truefrench" in words -> "FR"
            else -> null
        }
    }

    /**
     * A track's title when it says more than its [language], format, channels and bitrate; else
     * null. The language goes by its English, French and own names ("German", "allemand", "Deutsch").
     */
    fun note(title: String?, language: String? = null): String? {
        val text = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val names = languageNames(language)
        val rest = (PHRASES + NUMBERS).fold(text.lowercase()) { acc, pattern -> pattern.replace(acc, " ") }
            .let { words(it) }
            .filterNot { it in REDUNDANT || it in names }
        return text.takeIf { rest.sumOf { word -> word.count(Char::isLetter) } >= MIN_LETTERS }
    }

    /**
     * A track's language as one code whatever the file wrote: "fre", "fra", "fr" and "fr-CA" are
     * all "fr". Null when none is given.
     */
    fun language(code: String?): String? {
        val primary = code?.substringBefore('-')?.substringBefore('_')?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        // Locale names two-letter codes only: "ger" and "deu" both go by "de".
        return BIBLIOGRAPHIC[primary] ?: TWO_LETTERS[primary] ?: primary
    }

    private fun languageNames(language: String?): Set<String> {
        val code = language(language) ?: return emptySet()
        val locale = java.util.Locale.forLanguageTag(code)
        return listOf(java.util.Locale.ENGLISH, java.util.Locale.FRENCH, locale)
            .map { locale.getDisplayLanguage(it).lowercase() }
            .filter { it.isNotBlank() && it != code }
            .toSet()
    }

    /** Two-letter language codes by their three-letter terminology code ("deu": "de"). */
    private val TWO_LETTERS: Map<String, String> by lazy {
        java.util.Locale.getISOLanguages().associateBy { runCatching { java.util.Locale.forLanguageTag(it).isO3Language }.getOrDefault(it) }
    }

    private fun words(text: String) = text.lowercase().split(SEPARATORS).filter { it.isNotBlank() }

    /** A language tag's region: two letters ("fr-CA") or three figures ("es-419"). */
    private val REGION = Regex("""^[a-zA-Z]{2,3}[-_]([a-zA-Z]{2}|\d{3})\b""")

    /** At least this many letters left once the redundant words are gone: "GOLD", not "VO". */
    private const val MIN_LETTERS = 3

    private val SEPARATORS = Regex("""[\s:|\[\](){}/,;@–—_]+""")

    /** Formats said in several words. */
    private val PHRASES = listOf(Regex("""master\s+audio"""), Regex("""high[\s-]+res(?:olution)?(?:\s+audio)?"""))

    /** Channels, bitrates and sample rates, and figures alone: what the sheet's columns say. */
    private val NUMBERS = listOf(
        Regex("""\d+(?:[.,]\d+)?\s*(?:k|m)?(?:bits?|b)(?:ps|/s)"""),
        Regex("""\d+(?:[.,]\d+)?\s*k?hz"""),
        Regex("""\d+\s*ch\b"""),
        Regex("""\b\d(?:\.\d){1,2}\b"""),
        Regex("""\b\d+\b"""),
    )

    /** ISO 639-2 bibliographic codes, which Locale does not know, as two-letter codes. */
    private val BIBLIOGRAPHIC = mapOf(
        "fre" to "fr", "ger" to "de", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el", "per" to "fa",
        "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu", "ice" to "is", "may" to "ms",
    )

    /** Languages and dubs, formats and their parts, as track titles spell them. */
    private val REDUNDANT = setOf(
        "vf", "vff", "vfq", "vfi", "vo", "vost", "vostfr", "multi", "truefrench",
        "fr", "fre", "fra", "french", "français", "francais", "française", "francaise",
        "en", "eng", "english", "anglais", "anglaise", "original",
        "dd", "dd+", "ddp", "ac3", "ac-3", "eac3", "e-ac3", "e-ac-3", "dolby", "digital", "plus", "true", "truehd",
        "atmos", "joc", "dts", "dts-hd", "dts:x", "dtsx", "hd", "ma", "master", "hra", "hr", "high", "resolution",
        "lpcm", "pcm", "aac", "flac", "opus", "mp3", "mix", "stereo", "mono", "surround", "lossless", "core",
    )
}
