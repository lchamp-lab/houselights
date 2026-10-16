package app.houselights.providers.local

import app.houselights.core.Definition
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

data class ParsedMovie(val title: String, val year: Int?)

/** [lastEpisode]: the last episode the file holds when it holds several ("S01E01-E02"), else null. */
data class ParsedEpisode(val showTitle: String?, val season: Int, val episode: Int, val lastEpisode: Int? = null)

/** Extracts titles, years and episode numbers from file and folder names. */
object FilenameParser {
    val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "wmv", "ts", "m2ts", "mts", "iso",
        "webm", "mpg", "mpeg", "vob", "flv", "rmvb", "divx",
    )

    private val YEAR = Regex("""(?<![0-9])(19[0-9]{2}|20[0-9]{2})(?![0-9])""")

    /**
     * "S01E02", "S01E1071" (numbered from a show's start); a file holding several episodes names the
     * last one too: "S01E01-E02", "S01E01E02", "S01E01-02", "S01E01.S01E02", the last group read.
     */
    private val SXXEYY = Regex(
        """(?i)(?<![a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,4})(?![0-9])(?:(?:[ ._-]?(?:s\1[ ._-]?)?e|-)(\d{1,4})(?![0-9a-z]))*"""
    )
    private val NXNN = Regex("""(?i)(?<![a-z0-9])(\d{1,2})x(\d{2,3})(?![0-9])""")

    /** "03 - Victor", "1071 - Title" in a season's folder; not a date ("2019-05-12 - Title"). */
    private val LEADING_EPISODE = Regex("""(?i)^(?:e|ep|episode|épisode)?[ ._-]?(\d{1,4})(?![0-9]|[-.]\d{2}[-.]\d{2})""")

    /** More episodes than this between the first and the last one a name gives is no range: "S01E05-2019". */
    private const val MAX_RANGE = 50

    private val SEASON_FOLDER = Regex("""(?i)^(?:season|saison|staffel|temporada|s)[ ._-]?(\d{1,2})$""")
    // Android's ICU engine needs closing brackets and braces escaped, unlike the JVM.
    private val BRACKETS = Regex("""\[[^\]]*\]|\{[^\}]*\}""")

    /** Release tags no title holds as a word: definition, source, codecs, French release languages. */
    private const val TECHNICAL_TAGS =
        """2160p|1080p|1080i|720p|576p|480p|uhd|hdr10\+?|hdr|dovi|dolby[ ._-]?vision|""" +
            """blu[ ._-]?ray|bdrip|brrip|bdremux|remux|webrip|web[ ._-]?dl|hdtv|dvdrip|hdrip|""" +
            """x264|x265|h[ ._]?264|h[ ._]?265|hevc|avc|av1|10bit|aac|ac3|eac3|dts|truehd|atmos|""" +
            """multi|truefrench|vff|vfq|vfi|vf2|vostfr|subfrench"""

    /** Release tags that are words of titles too: "The French Connection", "Charlotte's Web", "A Complete Unknown". */
    private const val WORD_TAGS = """4k|dv|web|french|vf|proper|repack|extended|unrated|imax|hybrid|complete|internal"""

    /** Release tags, technical ones first, so that "WEB-DL" is read whole rather than as "WEB". */
    private val JUNK = Regex("""(?i)(?<![a-z0-9])($TECHNICAL_TAGS|$WORD_TAGS)(?![a-z0-9])""")
    private val TECHNICAL = Regex("""(?i)(?:$TECHNICAL_TAGS)""")

    /** What may lie between two tags of one run: "MULTi.VFF.2160p", "(FRENCH) 1080p". */
    private const val BETWEEN_TAGS = " ._-+()[]{}"

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isVideo(name: String): Boolean = extension(name) in VIDEO_EXTENSIONS

    fun stripExtension(name: String): String =
        if (isVideo(name)) name.substringBeforeLast('.') else name

    /** Folder names like "Season 2", "Saison 02", "S02". */
    fun seasonFromFolder(folder: String): Int? = SEASON_FOLDER.find(folder.trim())?.groupValues?.get(1)?.toInt()

    fun parseMovie(fileName: String): ParsedMovie {
        val base = normalizeSeparators(dropBrackets(stripExtension(fileName)))

        // The year is the last plausible one that is not the whole title ("1917", "2001 A Space Odyssey 1968"),
        // nor one to come ("Blade Runner 2049").
        val latest = LocalDate.now().year + 1
        val year = YEAR.findAll(base).lastOrNull { it.range.first > 0 && it.value.toInt() <= latest }
        val title = beforeTags(if (year != null) base.substring(0, year.range.first) else base)
        return ParsedMovie(clean(title).ifBlank { clean(base) }, year?.value?.toInt())
    }

    /**
     * [s] up to its release tags: from the run of tags that leads to the first one no title holds as a
     * word ("MULTi.2160p", "FRENCH.1080p"). Without such a tag, words that are tags at times stay:
     * "The French Connection", "Charlotte's Web", "A Proper Man".
     */
    private fun beforeTags(s: String): String {
        var runStart = 0
        var runEnd = -1
        for (tag in JUNK.findAll(s)) {
            val joined = runEnd >= 0 && s.substring(runEnd, tag.range.first).all { it in BETWEEN_TAGS }
            if (!joined) runStart = tag.range.first
            runEnd = tag.range.last + 1
            if (TECHNICAL.matches(tag.value)) return s.substring(0, runStart)
        }
        return s
    }

    /**
     * @param folders folder names from the source root down to the file's parent.
     */
    fun parseEpisode(fileName: String, folders: List<String>): ParsedEpisode? {
        val base = normalizeSeparators(dropBrackets(stripExtension(fileName)))
        val numbers = numbers(base, folders.lastOrNull()?.let(::seasonFromFolder)) ?: return null

        // Show title: first folder under the source root, else the text before SxxEyy.
        val showFolder = folders.firstOrNull { seasonFromFolder(it) == null }
        val fromName = clean(beforeTags(numbers.before))
        val show = showFolder?.let { parseMovie(it).title } ?: fromName.ifBlank { null }
        return ParsedEpisode(show, numbers.season, numbers.episode, numbers.last)
    }

    /** Season and episodes a file's name gives, and the text before them. */
    private class Numbers(val season: Int, val episode: Int, val last: Int?, val before: String)

    /** [base]'s numbers: "S01E02", "1x02", else "02 - Title" in a season's folder ([folderSeason]). */
    private fun numbers(base: String, folderSeason: Int?): Numbers? {
        SXXEYY.find(base)?.let { m ->
            val first = m.groupValues[2].toInt()
            val last = m.groups[3]?.value?.toInt()?.takeIf { it > first && it - first <= MAX_RANGE }
            return Numbers(m.groupValues[1].toInt(), first, last, base.substring(0, m.range.first))
        }
        NXNN.find(base)?.let { m ->
            return Numbers(m.groupValues[1].toInt(), m.groupValues[2].toInt(), null, base.substring(0, m.range.first))
        }
        val season = folderSeason ?: return null
        val episode = LEADING_EPISODE.find(base.trim())?.groupValues?.get(1)?.toInt() ?: return null
        return Numbers(season, episode, null, "")
    }

    /** A release-name token as a whole word ("hdr" but not "hdrip"), compiled once per token. */
    private val tokenRegexes = ConcurrentHashMap<String, Regex>()

    private fun tokenRegex(token: String) = tokenRegexes.getOrPut(token) { Regex("""(?<![a-z0-9])$token(?![a-z0-9])""") }

    /** Technical hints readable from release names. */
    fun badges(name: String): List<String> {
        val n = name.lowercase()
        fun has(vararg tokens: String) = tokens.any { tokenRegex(it).containsMatchIn(n) }
        return buildList {
            when {
                has("2160p", "4k", "uhd") -> add(Definition.ULTRA_HD.token)
                has("1080p", "1080i") -> add(Definition.FULL_HD.token)
                has("720p") -> add(Definition.HD.token)
            }
            when {
                has("dv", "dovi", """dolby[ ._-]?vision""") -> add("Dolby Vision")
                has("""hdr10\+""", "hdr10plus") -> add("HDR10+")
                has("hdr10", "hdr") -> add("HDR")
            }
            if (has("atmos")) add("Atmos")
            when {
                has("truehd") -> add("TrueHD")
                has("""dts[ ._-]?hd([ ._-]?ma)?""") -> add("DTS-HD MA")
                has("""dts[ ._-]?x""") -> add("DTS:X")
            }
            if (has("remux")) add("Remux")
        }
    }

    /** Removes [tags] and {tags}, but keeps a bracketed year: "Title [2024]". */
    private fun dropBrackets(s: String): String =
        BRACKETS.replace(s) { m -> YEAR.matchEntire(m.value.trim('[', ']', '{', '}').trim())?.let { " ${it.value} " } ?: " " }

    /** Dots and underscores separate words in scene names, unless the name already has spaces. */
    private fun normalizeSeparators(s: String): String =
        if (s.contains(' ')) s.replace('_', ' ') else s.replace('.', ' ').replace('_', ' ')

    private fun clean(s: String): String =
        s.replace(Regex("""[\s]+"""), " ")
            .trim()
            .trim('-', '–', '(', ')', ' ', '.')
            .trim()

    /** Key used to group episodes of the same show. */
    fun showKey(title: String): String =
        title.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
}
