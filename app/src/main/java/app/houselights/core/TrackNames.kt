package app.houselights.core

/**
 * What the tech sheet adds to a track's language and format: its region (French from France or Québec), and its
 * title when it says more ("Commentaire audio…") than the columns ("VFF DD 5.1 384 kbps").
 */
object TrackNames {
    /** Region code from the tag ("fr-CA", "es-419"), else the title's dub (VFQ: CA, VFF: FR); null if unknown. */
    fun region(language: String?, title: String?): String? {
        REGION.find(language.orEmpty())?.let { return it.groupValues[1].uppercase() }
        val words = words(title.orEmpty())
        return when {
            "vfq" in words -> "CA"
            "vff" in words || "truefrench" in words -> "FR"
            else -> null
        }
    }

    /** A track's title when it says more than its [language] (in English, French or itself), format and numbers. */
    fun note(title: String?, language: String? = null): String? {
        val text = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val names = languageNames(language)
        val rest = (PHRASES + NUMBERS).fold(text.lowercase()) { acc, pattern -> pattern.replace(acc, " ") }
            .let { words(it) }
            .filterNot { it in REDUNDANT || it in names }
        return text.takeIf { rest.sumOf { word -> word.count(Char::isLetter) } >= MIN_LETTERS }
    }

    private fun languageNames(language: String?): Set<String> {
        val code = Languages.code(language) ?: return emptySet()
        val locale = java.util.Locale.forLanguageTag(code)
        return listOf(java.util.Locale.ENGLISH, java.util.Locale.FRENCH, locale)
            .map { locale.getDisplayLanguage(it).lowercase() }
            .filter { it.isNotBlank() && it != code }
            .toSet()
    }

    private fun words(text: String) = text.lowercase().split(SEPARATORS).filter { it.isNotBlank() }

    /** A language tag's region: two letters ("fr-CA") or three figures ("es-419"). */
    private val REGION = Regex("""^[a-zA-Z]{2,3}[-_]([a-zA-Z]{2}|\d{3})\b""")

    /** Letters that must remain once redundant words are removed: "GOLD" counts, "VO" does not. */
    private const val MIN_LETTERS = 3

    private val SEPARATORS = Regex("""[\s:|\[\](){}/,;@–—_]+""")

    /** Multi-word format names. */
    private val PHRASES = listOf(Regex("""master\s+audio"""), Regex("""high[\s-]+res(?:olution)?(?:\s+audio)?"""))

    /** Channels, bitrates, sample rates and bare numbers: what the sheet's columns already show. */
    private val NUMBERS = listOf(
        Regex("""\d+(?:[.,]\d+)?\s*(?:k|m)?(?:bits?|b)(?:ps|/s)"""),
        Regex("""\d+(?:[.,]\d+)?\s*k?hz"""),
        Regex("""\d+\s*ch\b"""),
        Regex("""\b\d(?:\.\d){1,2}\b"""),
        Regex("""\b\d+\b"""),
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
