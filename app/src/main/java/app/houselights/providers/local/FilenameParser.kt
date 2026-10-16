package app.houselights.providers.local

import app.houselights.core.Definition
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

data class ParsedMovie(val title: String, val year: Int?)

/** [lastEpisode]: the last episode the file holds when it holds several ("S01E01-E02"), else null. */
data class ParsedEpisode(val showTitle: String?, val season: Int, val episode: Int, val lastEpisode: Int? = null)

/**
 * An episode and its show, as the file's path names them. [dir]: the show's folder, relative to the
 * source, whose tvshow.nfo and artwork describe it; "" when the episodes lie at the source's root, or in
 * season folders there.
 */
data class ShowPath(val title: String, val year: Int?, val dir: String, val episode: ParsedEpisode)

/**
 * A Blu-ray's or a DVD's folder, as one of its streams' path gives it. [root]: the folder holding
 * BDMV or VIDEO_TS ("" at the source's root), which names the title and holds its NFO and artwork;
 * [index]: the disc's own name for its NFO, relative to [root], as Kodi reads it ("BDMV/index.nfo").
 */
data class DiscPath(val root: String, val index: String)

/** Extracts titles, years and episode numbers from file and folder names. */
@Suppress("TooManyFunctions") // One function per question a name answers, in the one place a reader looks for them.
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

    /**
     * "03 - Victor", "1071 - Title" in a season's folder; not a year ("2019 Christmas Special", "1917"), nor a
     * date ("2019-05-12 - Title"). A show numbered from its start names such episodes "S01E1917".
     */
    private val LEADING_EPISODE =
        Regex("""(?i)^(?:e|ep|episode|épisode)?[ ._-]?(?!(?:19|20)\d{2}(?![0-9]))(\d{1,4})(?![0-9]|[-.]\d{2}[-.]\d{2})""")

    /** More episodes than this between the first and the last one a name gives is no range: "S01E05-2019". */
    private const val MAX_RANGE = 50

    private val SEASON_FOLDER = Regex("""(?i)^(?:season|saison|staffel|temporada|s)[ ._-]?(\d{1,2})$""")

    /** Folders of a show's specials, its season 0, lowercase. Not "Special": a show bears that name. */
    private val SPECIALS = setOf("specials", "spécial", "spéciaux", "speciaux", "spéciales", "speciales")

    /** A Blu-ray's streams ("BDMV/STREAM/00800.m2ts"), and a DVD's ("VIDEO_TS/VTS_01_1.VOB"). */
    private val BLU_RAY_STREAM = Regex("""(?i)^(?:(.*)/)?BDMV/STREAM/[^/]+\.(?:m2ts|mts)$""")
    private val DVD_STREAM = Regex("""(?i)^(?:(.*)/)?VIDEO_TS/[^/]+\.vob$""")

    // Android's ICU engine needs closing brackets and braces escaped, unlike the JVM.
    private val BRACKETS = Regex("""\[[^\]]*\]|\{[^\}]*\}""")

    /** Release tags no title holds as a word: definition, source, codecs, French release languages. */
    private const val TECHNICAL_TAGS =
        """2160p|1080p|1080i|720p|576p|480p|uhd|hdr10\+?|hdr|dovi|dolby[ ._-]?vision|""" +
            """blu[ ._-]?ray|bdrip|brrip|bdremux|remux|webrip|web[ ._-]?dl|hdtv|dvdrip|hdrip|hdlight|4klight|xvid|divx|""" +
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

    /** The disc [relativePath] is a stream of, or null for a file of no disc. */
    fun discOf(relativePath: String): DiscPath? {
        BLU_RAY_STREAM.matchEntire(relativePath)?.let { return DiscPath(it.groupValues[1], "BDMV/index") }
        DVD_STREAM.matchEntire(relativePath)?.let { return DiscPath(it.groupValues[1], "VIDEO_TS/VIDEO_TS") }
        return null
    }

    /** Folder names like "Season 2", "Saison 02", "S02"; "Specials", "Spéciaux" and "Season 00" are season 0. */
    fun seasonFromFolder(folder: String): Int? =
        if (folder.trim().lowercase() in SPECIALS) 0 else SEASON_FOLDER.find(folder.trim())?.groupValues?.get(1)?.toInt()

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
     * "The French Connection", "Charlotte's Web", "A Proper Man". In capitals, in a name that has small
     * letters, they are a release's too: "Le.Film.FRENCH", "Inception.PROPER", "Movie.EXTENDED.CUT"; unless
     * nothing but an article comes before them, which no film is named: "The IMAX Experience".
     */
    private fun beforeTags(s: String): String {
        val cased = s.any(Char::isLowerCase)
        var runStart = 0
        var runEnd = -1
        for (tag in JUNK.findAll(s)) {
            val joined = runEnd >= 0 && s.substring(runEnd, tag.range.first).all { it in BETWEEN_TAGS }
            if (!joined) runStart = tag.range.first
            runEnd = tag.range.last + 1
            if (TECHNICAL.matches(tag.value)) return s.substring(0, runStart)
            if (cased && inCapitals(tag.value)) {
                if (!NO_TITLE.matches(s.substring(0, runStart))) return s.substring(0, runStart)
                // The title's word, it starts no run of tags: "The.IMAX.1080p" is "The IMAX".
                runEnd = -1
            }
        }
        return s
    }

    /** A tag written as scene groups write theirs: "FRENCH", "PROPER", "iNTERNAL", but not "4K", a letter alone. */
    private fun inCapitals(tag: String) = tag.count(Char::isLetter) > 1 && tag.drop(1).none(Char::isLowerCase)

    /** What is no title before a tag: nothing, or an article alone ("The ", "L'", "Die "). */
    private val NO_TITLE = Regex("""(?i)^[ ._-]*(?:(?:${MetadataMerge.ARTICLE_WORDS}|der|die|das)[ ._-]*|l['’])?$""")

    /**
     * The episode a file's name gives, and its show's title (see [parseShowPath]).
     * @param folders folder names from the source root down to the file's parent.
     */
    fun parseEpisode(fileName: String, folders: List<String>): ParsedEpisode? = episodeIn(fileName, folders)?.episode

    /**
     * What [fileName] names after its episode's numbers, without the separators around it: the episode's title
     * as Sonarr names it ("Crime-Scene" for "Show - S01E05 - Crime-Scene.mkv"), or a release's tags; "" for
     * "Show.S01E05.mkv". Null when it names no episode by "S01E05" or "1x05".
     */
    fun afterEpisode(fileName: String): String? =
        numbers(normalizeSeparators(dropBrackets(stripExtension(fileName))), null)?.after?.trim(' ', '.', '_', '-')

    /**
     * The episode at [relativePath] and its show: what every rule that names a show from its path
     * reads. Null when the file's name gives no episode, or nothing names its show.
     */
    fun parseShowPath(relativePath: String): ShowPath? {
        // A disc is named by its folder, as a file by its name: "Show/Season 1/Show.S01E01/BDMV/STREAM/00001.m2ts".
        val path = discOf(relativePath)?.root?.ifEmpty { null } ?: relativePath
        val folders = path.split('/').dropLast(1)
        val found = episodeIn(path.substringAfterLast('/'), folders) ?: return null
        val title = found.episode.showTitle ?: return null
        val dir = found.folder?.let { folders.take(it + 1).joinToString("/") }.orEmpty()
        return ShowPath(title, found.year, dir, found.episode)
    }

    /** An episode; its show's folder, by its index among the file's (null for none); its show's year. */
    private class Found(val episode: ParsedEpisode, val folder: Int?, val year: Int?)

    private fun episodeIn(fileName: String, folders: List<String>): Found? {
        val base = normalizeSeparators(dropBrackets(stripExtension(fileName)))
        val numbers = numbers(base, folders.lastOrNull()?.let(::seasonFromFolder)) ?: return null
        val (folder, show) = showIn(folders, parseMovie(numbers.before))
        return Found(ParsedEpisode(show?.title, numbers.season, numbers.episode, numbers.last), folder, show?.year)
    }

    /**
     * The show's folder among [folders], by its index (null for none), and the show's name and year, for a file
     * whose name names the show [named]. The show's folder is the one named as the file names the show, under a
     * folder of kinds ("Animation/Arcane/Saison 1/Arcane.S01E01"); else one that names it before a pack's tags
     * ("Friends.Complete.Series.1080p", "Friends - The Complete Series"), the show named as its files name it;
     * else the first one, above a season's pack ("Show/Show.S01.1080p.WEB/Show.S01E01"). Without one, the
     * file's name names the show. A folder's name is a show's up to a season ([showName]).
     */
    private fun showIn(folders: List<String>, named: ParsedMovie): Pair<Int?, ParsedMovie?> {
        val shows = folders.indices.filter { seasonFromFolder(folders[it]) == null }
        val known = named.title.isNotEmpty()
        val same = shows.firstOrNull { known && sameShow(showName(folders[it]).title, named.title) }
        if (same != null) return same to showName(folders[same])
        val pack = shows.firstOrNull { known && namesThenTags(folders[it], named.title) }
        if (pack != null) return pack to ParsedMovie(named.title, showName(folders[pack]).year)
        val first = shows.firstOrNull()
        return first to (first?.let { showName(folders[it]) } ?: named.takeIf { known })
    }

    /**
     * A folder's name as a show's: up to its season's number, as a pack of a season names it, at the source's
     * root too ("Show.Name.S01.1080p.WEB-GRP", "Show.Name.S01-S03.1080p"), or of an episode ("Show.Name.S01E01.1080p"),
     * so that each season is not a show of its own.
     */
    private fun showName(folder: String): ParsedMovie =
        parseMovie(SEASON_TOKEN.find(folder)?.let { folder.substring(0, it.range.first) }?.takeIf { it.isNotBlank() } ?: folder)

    /** A pack's season or seasons, or an episode's numbers, in a folder's name: "S01", "S01-S03", "S01E01". */
    private val SEASON_TOKEN = Regex("""(?i)(?<![a-z0-9])s\d{1,2}(?:e\d{1,4}|-s?\d{1,2})?(?![a-z0-9])""")

    /**
     * [folder] names the show [title], then from a release tag on what of it it holds: "Friends.Complete.Series.1080p",
     * "Friends - The Complete Series", whose own name, "Friends Complete Series", no show bears.
     */
    private fun namesThenTags(folder: String, title: String): Boolean {
        val base = normalizeSeparators(dropBrackets(folder))
        val tag = JUNK.find(base) ?: return false
        return sameShow(base.substring(0, tag.range.first).replace(TRAILING_THE, ""), title)
    }

    /** "The" ending a name, before "The Complete Series". */
    private val TRAILING_THE = Regex("""(?i)(?<![a-z0-9])the[ ._-]*$""")

    /** Two names of one show, whatever case, spaces and punctuation: "Marvel's Agents of S.H.I.E.L.D." and "Marvels.Agents.of.SHIELD". */
    private fun sameShow(a: String, b: String) = letters(a) == letters(b)

    private fun letters(s: String) = s.lowercase().filter(Char::isLetterOrDigit)

    /** Season and episodes a file's name gives, and the text before and after them. */
    private class Numbers(val season: Int, val episode: Int, val last: Int?, val before: String, val after: String = "")

    /** [base]'s numbers: "S01E02", "1x02", else "02 - Title" in a season's folder ([folderSeason]). */
    private fun numbers(base: String, folderSeason: Int?): Numbers? {
        val named = SXXEYY.find(base)?.let { m ->
            val (season, first, last) = m.destructured
            val range = last.toIntOrNull()?.takeIf { it > first.toInt() && it - first.toInt() <= MAX_RANGE }
            Numbers(season.toInt(), first.toInt(), range, base.substring(0, m.range.first), base.substring(m.range.last + 1))
        } ?: NXNN.find(base)?.let { m ->
            val (season, episode) = m.destructured
            Numbers(season.toInt(), episode.toInt(), null, base.substring(0, m.range.first), base.substring(m.range.last + 1))
        }
        if (named != null || folderSeason == null) return named
        return LEADING_EPISODE.find(base.trim())?.groupValues?.get(1)?.toInt()?.let { Numbers(folderSeason, it, null, "") }
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

    private val SPACES = Regex("""\s+""")

    private fun clean(s: String): String =
        s.replace(SPACES, " ")
            .trim()
            .trim('-', '–', '(', ')', ' ', '.')
            .trim()

    /**
     * Key used to group episodes of the same show, with its year when its folder gives one
     * ("doctor who|2005"): "Doctor Who (2005)" and "Doctor Who (1963)" are two shows.
     */
    fun showKey(title: String, year: Int? = null): String =
        title.lowercase().replace(NOT_LETTERS, " ").trim().let { if (year == null) it else "$it$YEAR_MARK$year" }

    /** What a show's key keeps of its name: letters and digits, whatever stands between them made one space. */
    private val NOT_LETTERS = Regex("""[^\p{L}\p{N}]+""")

    /** Between a show's name and its year, in its key. */
    const val YEAR_MARK = "|"
}
