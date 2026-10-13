package app.houselights.providers.local

data class ParsedMovie(val title: String, val year: Int?)

data class ParsedEpisode(val showTitle: String?, val season: Int, val episode: Int)

/** Extracts titles, years and episode numbers from file and folder names. */
object FilenameParser {
    val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "wmv", "ts", "m2ts", "mts", "iso",
        "webm", "mpg", "mpeg", "vob", "flv", "rmvb", "divx",
    )

    private val YEAR = Regex("""(?<![0-9])(19[0-9]{2}|20[0-9]{2})(?![0-9])""")
    private val SXXEYY = Regex("""(?i)(?<![a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,3})""")
    private val NXNN = Regex("""(?i)(?<![a-z0-9])(\d{1,2})x(\d{2,3})(?![0-9])""")
    private val LEADING_EPISODE = Regex("""(?i)^(?:e|ep|episode|épisode)?[ ._-]?(\d{1,3})(?![0-9])""")
    private val SEASON_FOLDER = Regex("""(?i)^(?:season|saison|staffel|temporada|s)[ ._-]?(\d{1,2})$""")
    // Android's ICU engine needs closing brackets and braces escaped, unlike the JVM.
    private val BRACKETS = Regex("""\[[^\]]*\]|\{[^\}]*\}""")

    /** Release tags: everything from the first one on is noise. */
    private val JUNK = Regex(
        """(?i)(?<![a-z0-9])(2160p|1080p|1080i|720p|576p|480p|4k|uhd|hdr10\+?|hdr|dv|dovi|dolby[ ._-]?vision|""" +
            """blu[ ._-]?ray|bdrip|brrip|bdremux|remux|webrip|web[ ._-]?dl|web|hdtv|dvdrip|hdrip|""" +
            """x264|x265|h[ ._]?264|h[ ._]?265|hevc|avc|av1|10bit|aac|ac3|eac3|dts|truehd|atmos|""" +
            """multi|truefrench|french|vff|vfq|vfi|vf2|vf|vostfr|subfrench|proper|repack|extended|unrated|""" +
            """imax|hybrid|complete|internal)(?![a-z0-9])"""
    )

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isVideo(name: String): Boolean = extension(name) in VIDEO_EXTENSIONS

    fun stripExtension(name: String): String =
        if (isVideo(name)) name.substringBeforeLast('.') else name

    /** Folder names like "Season 2", "Saison 02", "S02". */
    fun seasonFromFolder(folder: String): Int? = SEASON_FOLDER.find(folder.trim())?.groupValues?.get(1)?.toInt()

    fun parseMovie(fileName: String): ParsedMovie {
        val base = normalizeSeparators(dropBrackets(stripExtension(fileName)))

        // The year is the last plausible one that is not the whole title ("1917", "2001 A Space Odyssey 1968").
        val year = YEAR.findAll(base).lastOrNull { it.range.first > 0 }
        var title = if (year != null) base.substring(0, year.range.first) else base
        JUNK.find(title)?.let { title = title.substring(0, it.range.first) }
        return ParsedMovie(clean(title).ifBlank { clean(base) }, year?.value?.toInt())
    }

    /**
     * @param folders folder names from the source root down to the file's parent.
     */
    fun parseEpisode(fileName: String, folders: List<String>): ParsedEpisode? {
        val base = normalizeSeparators(dropBrackets(stripExtension(fileName)))
        val folderSeason = folders.lastOrNull()?.let(::seasonFromFolder)

        val match = SXXEYY.find(base) ?: NXNN.find(base)
        val (season, episode, before) = when {
            match != null -> Triple(match.groupValues[1].toInt(), match.groupValues[2].toInt(), base.substring(0, match.range.first))
            folderSeason != null -> {
                val ep = LEADING_EPISODE.find(base.trim())?.groupValues?.get(1)?.toInt() ?: return null
                Triple(folderSeason, ep, "")
            }
            else -> return null
        }

        // Show title: first folder under the source root, else the text before SxxEyy.
        val showFolder = folders.firstOrNull { seasonFromFolder(it) == null }
        val fromName = clean(JUNK.find(before)?.let { before.substring(0, it.range.first) } ?: before)
        val show = showFolder?.let { parseMovie(it).title } ?: fromName.ifBlank { null }
        return ParsedEpisode(show, season, episode)
    }

    /** Technical hints readable from release names. */
    fun badges(name: String): List<String> {
        val n = name.lowercase()
        fun has(vararg tokens: String) = tokens.any { Regex("""(?<![a-z0-9])$it(?![a-z0-9])""").containsMatchIn(n) }
        return buildList {
            when {
                has("2160p", "4k", "uhd") -> add("4K")
                has("1080p", "1080i") -> add("1080p")
                has("720p") -> add("720p")
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
