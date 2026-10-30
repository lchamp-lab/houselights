package app.houselights.providers.local

import app.houselights.core.BadgeTokens
import app.houselights.core.Codecs
import app.houselights.core.Definition
import app.houselights.core.Editions
import app.houselights.core.HdrFormat
import java.text.Normalizer
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

data class ParsedMovie(val title: String, val year: Int?)

/** How a file's name numbers its episode ([ParsedEpisode.numbering]). */
enum class Numbering {
    /** By its season and episode: "S01E05", "1x05", "Season 1 Episode 5", "Livre I - 05". */
    SEASON,

    /** By a number alone in its season's folder: "Saison 1/05 - Titre.mkv". */
    FOLDER,

    /** By its season then a dash, as anime sequels are: "Mushoku Tensei S2 - 01". */
    SEQUEL,

    /** From its show's first episode, as most anime are: "One Piece - 1071". */
    ABSOLUTE,
}

/**
 * [lastEpisode] is the last episode of a multi-episode file ("S01E01-E02"), else null. A file numbered from its show's
 * start ([Numbering.ABSOLUTE]) gives that number as [episode], in season 1 until it is placed ([EpisodeNumbering]).
 */
data class ParsedEpisode(
    val showTitle: String?,
    val season: Int,
    val episode: Int,
    val lastEpisode: Int? = null,
    val numbering: Numbering = Numbering.SEASON,
) {
    /**
     * Its numbering as the file names it, whatever its place: "1:5-5", "1:5-6" for a file holding two episodes,
     * "a1071-1071" for one numbered from its show's start, by which a removed episode is kept ([KeptKeys.spanOf]).
     */
    fun key(): String {
        val numbers = "$episode-${lastEpisode ?: episode}"
        return if (numbering == Numbering.ABSOLUTE) "a$numbers" else "$season:$numbers"
    }
}

/** An episode and its show; [dir] is the show folder (tvshow.nfo, artwork), "" at the source root. */
data class ShowPath(val title: String, val year: Int?, val dir: String, val episode: ParsedEpisode)

/**
 * A disc from one of its stream paths: [root] holds BDMV or VIDEO_TS ("" at the source root) and names the title;
 * [index] is the disc's own NFO base name under [root], as Kodi reads it ("BDMV/index").
 */
data class DiscPath(val root: String, val index: String)

/**
 * Extracts titles, years and episode numbers from file and folder names, read composed ([nfc]): a name copied from a Mac,
 * its accents apart (NFD), reads as the same name written on the NAS. Paths it gives back stay as listed, the bytes SMB
 * and NFS name the files by.
 */
@Suppress("TooManyFunctions") // All name-parsing rules live in one place.
object FilenameParser {
    val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "wmv", "ts", "m2ts", "mts", "iso",
        "webm", "mpg", "mpeg", "vob", "flv", "rmvb", "divx",
    )

    private val YEAR = Regex("""(?<![0-9])(19[0-9]{2}|20[0-9]{2})(?![0-9])""")

    /**
     * "S01E02", "S01E1071", a year's season "S2023E01"; multi-episode "S01E01-E02", "S01E01E02", "S01E01-02",
     * "S01E01.S01E02".
     */
    private val SXXEYY = Regex(
        """(?i)(?<![a-z0-9])s(\d{1,2}|$YEAR_SEASON)[ ._-]?e(\d{1,4})(?![0-9])""" +
            """(?:(?:[ ._-]?(?:s\1[ ._-]?)?e|-)(\d{1,4})(?![0-9a-z]))*""",
    )
    private val NXNN = Regex("""(?i)(?<![a-z0-9])(\d{1,2})x(\d{2,3})(?![0-9])""")

    /** "03 - Victor", "1071 - Title" in a season folder; never a year ("1917") or a date ("2019-05-12 - Title"). */
    private val LEADING_EPISODE =
        Regex("""(?i)^(?:e|ep|episode|épisode)?[ ._-]?(?!(?:19|20)\d{2}(?![0-9]))(\d{1,4})(?![0-9]|[-.]\d{2}[-.]\d{2})""")

    /** A wider span between first and last episode is not a range: "S01E05-2019". */
    private const val MAX_RANGE = 50

    /** A season numbered by its year: "S2023E01", "Season 2023". */
    private const val YEAR_SEASON = """(?:19|20)\d{2}"""

    /** Not a year standing alone ("2019"), which is never an episode's number. */
    private const val NOT_YEAR = """(?!(?:19|20)\d{2}(?![0-9]))"""

    private val SEASON_FOLDER = Regex("""(?i)^(?:season|saison|staffel|temporada|s)[ ._-]?(\d{1,2}|$YEAR_SEASON)$""")

    /** Kaamelott's "Livre I", "Book 2", "Partie 3", "Part IV", "Volume 1", "Vol. 2": a season's folder. */
    private val BOOK_FOLDER = Regex("""(?i)^(?:livre|book|partie|part|volume|vol\.?)[ ._-]*(\d{1,2}|[ivx]{1,5})$""")

    /** A Roman numeral from I to XXIX; empty matches too, and counts as none. */
    private val ROMAN = Regex("""(?i)^(x{0,2})(ix|iv|v?i{0,3})$""")

    /**
     * A book's episode in a file's name, "Kaamelott - Livre I - 01 - Heat", only after "Livre" or "Book", or a volume in
     * Roman numerals: never "Kill Bill - Volume 1" nor "Part 2", which films are named by.
     */
    private val BOOK_EPISODE = Regex(
        """(?i)(?<![\p{L}0-9])(?:(?:livre|book)[ ._-]*(\d{1,2}|[ivx]{1,5})|(?:volume|vol\.?)[ ._-]*([ivx]{1,5}))""" +
            """[ ._-]+(?:-[ ._-]*)?(?:(?:e|ep\.?|episode|[éÉ]pisode)[ ._-]*)?(\d{1,3})(?![0-9\p{L}])""",
    )

    /** "Season 1 Episode 5", "Saison 2 - Épisode 3": the season and episode in words. */
    private val SEASON_EPISODE_WORDS = Regex(
        """(?i)(?<![\p{L}0-9])(?:season|saison|staffel|temporada)[ ._-]*(\d{1,2})[ ._-]*(?:-[ ._-]*)?""" +
            """(?:episode|[éÉ]pisode|episodio|folge|ep\.?|e)[ ._-]*(\d{1,4})(?![0-9])""",
    )

    /** An anime sequel's season, then its episode: "Mushoku Tensei S2 - 01", "Spy x Family Season 2 - 01", "2nd Season - 01". */
    private val SEQUEL_DASH = Regex(
        """(?i)(?<![\p{L}0-9])(?:s(\d{1,2})|(?:season|saison|staffel|temporada)[ ._-]*(\d{1,2})|(\d)(?:nd|rd|th)[ ._-]+season)""" +
            """\s+-\s+$NOT_YEAR(\d{1,4})(?:v\d)?(?:-(\d{1,4}))?(?![0-9\p{L}])""",
    )

    /**
     * A number after a dash, as anime are named from their show's start: "One Piece - 1071", "- 1071v2", "- 1071-1072",
     * then the name's end, another dash or a parenthesis; never a year ("Show - 2019"), a resolution ("- 1080p") nor a
     * title's first word ("Show - 100 Days").
     */
    private val DASH_NUMBER = Regex("""(?<=\S)\s+-\s+$NOT_YEAR(\d{1,4})(?:v\d)?(?:-(\d{1,4}))?(?=\s*(?:$|-\s|\())""")

    /** A number after a word that says it: "One Piece E1071", "Ep 12", "Ep.12", "Episode 1071", "Épisode 12", "Folge 3". */
    private val WORD_NUMBER = Regex(
        """(?i)(?<![\p{L}0-9])(?:e(?=\d)|ep\.?[ ._-]?|(?:episode|[éÉ]pisode|episodio|folge)[ ._-]*)$NOT_YEAR(\d{1,4})(?:v\d)?""" +
            """(?![0-9\p{L}])""",
    )

    /** A batch's range ending a folder's name, cut as a season is: "One Piece (1001-1100)", "One Piece 1001-1100"; never years. */
    private val NUMBER_RANGE = Regex(
        """(?<![0-9])(?:\($NOT_YEAR\d{1,4}\s?[-~]\s?\d{1,4}\)|(?<=\s)$NOT_YEAR\d{1,4}\s?[-~]\s?\d{1,4}(?=\s*$))""",
    )

    /** Lowercase folder names for season 0. Not "Special", which is also a show's title. */
    private val SPECIALS = setOf("specials", "spécial", "spéciaux", "speciaux", "spéciales", "speciales")

    private val BLU_RAY_STREAM = Regex("""(?i)^(?:(.*)/)?BDMV/STREAM/[^/]+\.(?:m2ts|mts)$""")
    private val DVD_STREAM = Regex("""(?i)^(?:(.*)/)?VIDEO_TS/[^/]+\.vob$""")

    // Android's ICU engine needs closing brackets and braces escaped, unlike the JVM.
    private val BRACKETS = Regex("""\[[^\]]*\]|\{[^\}]*\}""")

    /** Release tags that never occur as title words: definition, source, codecs, French release languages. */
    private const val TECHNICAL_TAGS =
        """2160p|1080p|1080i|720p|576p|480p|uhd|hdr10\+?|hdr|dovi|dolby[ ._-]?vision|""" +
            """blu[ ._-]?ray|bdrip|brrip|bdremux|remux|webrip|web[ ._-]?dl|hdtv|dvdrip|hdrip|hdlight|4klight|xvid|divx|""" +
            """x264|x265|h[ ._]?264|h[ ._]?265|hevc|avc|av1|10bit|aac|ac3|eac3|dts|truehd|atmos|""" +
            """multi|truefrench|vff|vfq|vfi|vf2|vostfr|subfrench"""

    /**
     * Tags that can be title words ("The French Connection", "A Complete Unknown"). "Intégrale" marks a French series
     * pack ("Kaamelott.Integrale.1080p"), but not after L' ("Florence Foresti - L'Intégrale 1080p").
     */
    private const val WORD_TAGS = "4k|dv|web|french|vf|proper|repack|extended|unrated|imax|hybrid|complete|internal|" +
        """(?<!^l['’ ._])(?<![^a-zA-Z0-9]l['’ ._])int[eéÉ]grale"""

    /** All release tags, technical first so that "WEB-DL" matches whole rather than as "WEB". */
    private val JUNK = Regex("""(?i)(?<![a-z0-9])($TECHNICAL_TAGS|$WORD_TAGS)(?![a-z0-9])""")
    private val TECHNICAL = Regex("""(?i)(?:$TECHNICAL_TAGS)""")

    /** Characters allowed between tags of one run: "MULTi.VFF.2160p", "(FRENCH) 1080p". */
    private const val BETWEEN_TAGS = " ._-+()[]{}"

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isVideo(name: String): Boolean = extension(name) in VIDEO_EXTENSIONS

    fun stripExtension(name: String): String =
        if (isVideo(name)) name.substringBeforeLast('.') else name

    fun discOf(relativePath: String): DiscPath? {
        BLU_RAY_STREAM.matchEntire(relativePath)?.let { return DiscPath(it.groupValues[1], "BDMV/index") }
        DVD_STREAM.matchEntire(relativePath)?.let { return DiscPath(it.groupValues[1], "VIDEO_TS/VIDEO_TS") }
        return null
    }

    /**
     * Folder names like "Season 2", "Saison 02", "S02", "Season 2023", a book's "Livre I" or "Volume 2"; "Specials",
     * "Spéciaux" and "Season 00" are season 0.
     */
    fun seasonFromFolder(folder: String): Int? {
        val name = folder.trim()
        if (name.lowercase() in SPECIALS) return 0
        SEASON_FOLDER.find(name)?.let { return it.groupValues[1].toInt() }
        return BOOK_FOLDER.find(name)?.let { numberOf(it.groupValues[1]) }
    }

    /** "2" or a Roman numeral ("II", "XIV"); null for one that is none ("IIII"). */
    private fun numberOf(numeral: String): Int? = numeral.toIntOrNull() ?: roman(numeral)

    private fun roman(numeral: String): Int? {
        val m = ROMAN.matchEntire(numeral) ?: return null
        val (tens, units) = m.destructured
        val unit = when (units.lowercase()) {
            "ix" -> ROMAN_NINE
            "iv" -> ROMAN_FOUR
            else -> units.count { it.lowercaseChar() == 'i' } + if (units.startsWith('v', ignoreCase = true)) ROMAN_FIVE else 0
        }
        return (tens.length * ROMAN_TEN + unit).takeIf { it > 0 }
    }

    private const val ROMAN_FOUR = 4
    private const val ROMAN_FIVE = 5
    private const val ROMAN_NINE = 9
    private const val ROMAN_TEN = 10

    fun parseMovie(fileName: String): ParsedMovie {
        val base = normalizeSeparators(dropBrackets(stripExtension(nfc(fileName))))
        val year = yearIn(base)
        val title = beforeTags(if (year != null) base.substring(0, year.range.first) else base)
        return ParsedMovie(clean(title).ifBlank { clean(base) }, year?.value?.toInt())
    }

    /**
     * Whether the year [parseMovie] reads in [fileName] stands bare, neither in parentheses nor in brackets: it may be
     * the title's ("Wonder Woman 1984", "Death Race 2000"), unlike "Dune (2021)" or "Dune [2021]".
     */
    fun bareYear(fileName: String): Boolean {
        val name = stripExtension(fileName)
        val base = normalizeSeparators(dropBrackets(name))
        val year = yearIn(base) ?: return false
        val inParentheses = base.getOrNull(year.range.first - 1) == '(' && base.getOrNull(year.range.last + 1) == ')'
        return !inParentheses && !Regex("""[\[\{]\s*${year.value}\s*[\]\}]""").containsMatchIn(name)
    }

    /** The last plausible year in [base], not at the start ("1917") nor in the future ("Blade Runner 2049"). */
    private fun yearIn(base: String): MatchResult? {
        val latest = LocalDate.now().year + 1
        return YEAR.findAll(base).lastOrNull { it.range.first > 0 && it.value.toInt() <= latest }
    }

    /**
     * The edition ([Editions]) of a file or disc folder: Plex's "{edition-Final Cut}", "[Extended]", or words after the
     * year ("Blade Runner (1982) - Director's Cut"). Null without a year, as "The Final Cut" is also a film.
     */
    fun edition(relativePath: String): String? {
        val path = discOf(relativePath)?.root?.ifEmpty { null } ?: relativePath
        val name = stripExtension(nfc(path.substringAfterLast('/')))
        PLEX_EDITION.find(name)?.let { return Editions.of(it.groupValues[1]) }
        BRACKETED.findAll(name).firstNotNullOfOrNull { Editions.find(it.groupValues[1]) }?.let { return it }
        val base = normalizeSeparators(dropBrackets(name))
        val year = yearIn(base) ?: return null
        return Editions.find(base.substring(year.range.last + 1))
    }

    private val PLEX_EDITION = Regex("""(?i)\{edition-([^\}]+)\}""")
    private val BRACKETED = Regex("""\[([^\]]*)\]""")

    /**
     * [s] cut at the tag run containing a technical tag ("FRENCH.1080p"); word tags alone stay ("The French
     * Connection"). In mixed case an all-caps word tag also cuts ("Inception.PROPER"), unless only an article
     * precedes it ("The IMAX Experience").
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
                // A title word, not the start of a tag run: "The.IMAX.1080p" gives "The IMAX".
                runEnd = -1
            }
        }
        return s
    }

    /** Scene-style capitals: "FRENCH", "PROPER", "iNTERNAL", but not "4K", which has a single letter. */
    private fun inCapitals(tag: String) = tag.count(Char::isLetter) > 1 && tag.drop(1).none(Char::isLowerCase)

    /** A prefix that cannot be a title: nothing, or an article alone ("The ", "L'", "Die "). */
    private val NO_TITLE = Regex("""(?i)^[ ._-]*(?:(?:${MetadataMerge.ARTICLE_WORDS}|der|die|das)[ ._-]*|l['’])?$""")

    /**
     * The episode named by [fileName]; [folders] run from the source root to the file's parent. [named] reads only an
     * "S01E05" or "1x05", as a films folder tells a title's own folder by them ([ScanTitles]).
     */
    fun parseEpisode(fileName: String, folders: List<String>, named: Boolean = false): ParsedEpisode? =
        episodeIn(fileName, folders, all = !named)?.episode

    /** Text after "S01E05" or "1x05" ("Crime-Scene" in "Show - S01E05 - Crime-Scene.mkv"); null without. */
    fun afterEpisode(fileName: String): String? =
        numbers(normalizeSeparators(dropBrackets(stripExtension(fileName))), null, all = false)?.after?.trim(' ', '.', '_', '-')

    /** The episode at [relativePath] and its show; null when no episode or no show name is found. */
    fun parseShowPath(relativePath: String): ShowPath? {
        // A disc is named by its folder: "Show/Season 1/Show.S01E01/BDMV/STREAM/00001.m2ts".
        val path = discOf(relativePath)?.root?.ifEmpty { null } ?: relativePath
        val folders = path.split('/').dropLast(1)
        val found = episodeIn(path.substringAfterLast('/'), folders) ?: return null
        val title = found.episode.showTitle ?: return null
        val dir = found.folder?.let { folders.take(it + 1).joinToString("/") }.orEmpty()
        return ShowPath(title, found.year, dir, found.episode)
    }

    /**
     * Why the video at [relativePath] of a shows folder gets no row: no episode number its name gives, or numbers but no
     * show's name ("S01E01.mkv" at the folder's root, "Saison 1/03 - Victor.mkv"); null when it names an episode.
     */
    fun refusal(relativePath: String): IgnoredReason? {
        if (parseShowPath(relativePath) != null) return null
        val path = discOf(relativePath)?.root?.ifEmpty { null } ?: relativePath
        val numbered = episodeIn(path.substringAfterLast('/'), path.split('/').dropLast(1)) != null
        return if (numbered) IgnoredReason.NO_SHOW else IgnoredReason.NO_NUMBER
    }

    private class Found(val episode: ParsedEpisode, val folder: Int?, val year: Int?)

    private fun episodeIn(fileName: String, folders: List<String>, all: Boolean = true): Found? {
        val base = normalizeSeparators(dropBrackets(stripExtension(nfc(fileName))))
        val numbers = numbers(base, folders.lastOrNull()?.let(::seasonFromFolder), all) ?: return null
        val (folder, show) = showIn(folders, parseMovie(numbers.before))
        val episode = ParsedEpisode(show?.title, numbers.season, numbers.episode, numbers.last, numbers.numbering)
        return Found(episode, folder, show?.year)
    }

    /**
     * The show folder's index among [folders] and the show's name and year, given [named] from the file name:
     * - a same-named folder, preferring name and year ("Doctor Who/Doctor Who (2005)/Season 1"); a season pack with
     *   a year lends it to the same-named folder above ("Breaking Bad/Breaking Bad (2008) Season 2");
     * - else a folder naming the show before pack tags ("Friends - The Complete Series");
     * - else the first non-season folder ("Show/Show.S01.1080p.WEB/Show.S01E01"); without any, the file name.
     */
    private fun showIn(folders: List<String>, named: ParsedMovie): Pair<Int?, ParsedMovie?> {
        val shows = folders.indices.filter { seasonFromFolder(folders[it]) == null }
        val known = named.title.isNotEmpty()
        val sameName = shows.filter { known && sameShow(showName(folders[it]).title, named.title) }
        val ofYear = sameName.firstOrNull { titleAndYear(folders[it]) }
        val same = ofYear?.takeUnless { namesSeason(folders[it]) && it != sameName.first() } ?: sameName.firstOrNull()
        if (same != null) return same to showName(folders[ofYear ?: same])
        val pack = shows.firstOrNull { known && namesThenTags(folders[it], named.title) }
        if (pack != null) return pack to ParsedMovie(named.title, showName(folders[pack]).year)
        val first = shows.firstOrNull()
        return first to (first?.let { showName(folders[it]) } ?: named.takeIf { known })
    }

    /** A folder name cut before season numbers ("Show.Name.S01-S03.1080p"), so season packs are not separate shows. */
    private fun showName(folder: String): ParsedMovie = parseMovie(beforeSeason(folder))

    private fun beforeSeason(folder: String): String {
        val cut = listOfNotNull(SEASON_TOKEN.find(folder), NUMBER_RANGE.find(folder)).minOfOrNull { it.range.first }
        return cut?.let { folder.substring(0, it) }?.takeIf { it.isNotBlank() } ?: folder
    }

    /** True for a season pack after a name ("Doctor Who (2005) - S01"), as opposed to a show folder. */
    private fun namesSeason(folder: String): Boolean = beforeSeason(folder) != folder

    /** "S01", "S01-S03", "S01E01", "Saison 1", "Saisons 1 à 5", "Season 2023", "Livre I"; never "The Four Seasons". */
    private val SEASON_TOKEN = Regex(
        """(?i)(?<![a-z0-9])(?:(?:s|(?:saisons?|seasons?)[ ._-]?)(?:\d{1,2}|$YEAR_SEASON)(?:e\d{1,4}|-s?\d{1,2})?""" +
            """|livre[ ._-]*(?:\d{1,2}|[ivx]{1,5})|book[ ._-]*[ivx]{1,5})(?![a-z0-9])""",
    )

    /** True when [folder] is [title] followed by release tags: "Friends - The Complete Series". */
    private fun namesThenTags(folder: String, title: String): Boolean {
        val base = normalizeSeparators(dropBrackets(folder))
        val tag = JUNK.find(base) ?: return false
        return sameShow(base.substring(0, tag.range.first).replace(TRAILING_THE, ""), title)
    }

    /**
     * True for a title and year with at most bracketed ids ("Doctor.Who.2005", "Doctor Who (2005) [tvdbid-78804]"),
     * or a parenthesized year then a season or tags ("Doctor Who (2005) - S01"); false for "Severance.2022.S01.WEB".
     */
    private fun titleAndYear(folder: String): Boolean {
        val name = parseMovie(folder)
        val year = name.year ?: return false
        val named = letters(name.title) + year
        if (letters(dropBrackets(folder)) == named) return true
        val apart = folder.contains("($year)") || folder.contains("[$year]")
        return apart && letters(beforeTags(normalizeSeparators(dropBrackets(beforeSeason(folder))))) == named
    }

    /** The "The" left before "Complete Series". */
    private val TRAILING_THE = Regex("""(?i)(?<![a-z0-9])the[ ._-]*$""")

    /** Compares ignoring case and punctuation: "Marvel's Agents of S.H.I.E.L.D." equals "Marvels.Agents.of.SHIELD". */
    private fun sameShow(a: String, b: String) = letters(a) == letters(b)

    private fun letters(s: String) = nfc(s).lowercase().filter(Char::isLetterOrDigit)

    /** [name] composed (NFC): "é" one character, as Sonarr writes it, not "e" and its accent, as a Mac copies it. */
    private fun nfc(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC)

    private class Numbers(
        val season: Int,
        val episode: Int,
        val last: Int?,
        val before: String,
        val after: String = "",
        val numbering: Numbering = Numbering.SEASON,
    )

    /**
     * "S01E02", "1x02"; with [all] rules, else a book's or a season's in words ("Livre I - 02", "Season 1 Episode 2"), an
     * anime sequel's ("S2 - 02"), then in a season's folder ([folderSeason]) "02 - Title" or "Show - 02", and outside one
     * a number from the show's start ("Show - 1071", "Show E1071").
     */
    private fun numbers(base: String, folderSeason: Int?, all: Boolean): Numbers? {
        val named = SXXEYY.find(base)?.let { m ->
            val (season, first, last) = m.destructured
            numbersAt(base, m, season.toInt(), first.toInt(), rangeOf(first.toInt(), last), Numbering.SEASON)
        } ?: NXNN.find(base)?.let { m ->
            val (season, episode) = m.destructured
            numbersAt(base, m, season.toInt(), episode.toInt(), null, Numbering.SEASON)
        }
        if (named != null || !all) return named
        return inWords(base) ?: sequel(base) ?: if (folderSeason != null) inFolder(base, folderSeason) else fromStart(base)
    }

    /** The last episode of a file numbered [first] to [last]: none past [MAX_RANGE], nor before [first]. */
    private fun rangeOf(first: Int, last: String): Int? = last.toIntOrNull()?.takeIf { it > first && it - first <= MAX_RANGE }

    /** A book's episode ("Livre I - 02") or a season's and episode in words ("Season 1 Episode 2"). */
    private fun inWords(base: String): Numbers? {
        val book = BOOK_EPISODE.findAll(base).firstNotNullOfOrNull { m ->
            val (numeral, volume, episode) = m.destructured
            numberOf(numeral.ifEmpty { volume })?.let { season -> numbersAt(base, m, season, episode.toInt(), null, Numbering.SEASON) }
        }
        return book ?: SEASON_EPISODE_WORDS.find(base)?.let { m ->
            val (season, episode) = m.destructured
            numbersAt(base, m, season.toInt(), episode.toInt(), null, Numbering.SEASON)
        }
    }

    /** An anime sequel's season and episode: "S2 - 01", "Season 2 - 01", "2nd Season - 01". */
    private fun sequel(base: String): Numbers? = SEQUEL_DASH.find(base)?.let { m ->
        val groups = m.groupValues
        // "S2", "Season 2" or "2nd Season", then the episode and the last of a range.
        val season = groups.subList(1, SEQUEL_EPISODE).first { it.isNotEmpty() }.toInt()
        val first = groups[SEQUEL_EPISODE].toInt()
        numbersAt(base, m, season, first, rangeOf(first, groups[SEQUEL_EPISODE + 1]), Numbering.SEQUEL)
    }

    /** [SEQUEL_DASH]'s group of the episode, after the season's three ways. */
    private const val SEQUEL_EPISODE = 4

    /** A number alone in season [season]'s folder: "02 - Title", else "Show - 02" or "Show E02". */
    private fun inFolder(base: String, season: Int): Numbers? {
        val leading = LEADING_EPISODE.find(base.trim())?.groupValues?.get(1)?.toInt()
        if (leading != null) return Numbers(season, leading, null, "", numbering = Numbering.FOLDER)
        return byNumber(base)?.let { found -> numbersAt(base, found.match, season, found.first, found.last, Numbering.FOLDER) }
    }

    /** A number from the show's start, its season 1 until placed: "One Piece - 1071", "One Piece E1071". */
    private fun fromStart(base: String): Numbers? =
        byNumber(base)?.let { found -> numbersAt(base, found.match, 1, found.first, found.last, Numbering.ABSOLUTE) }

    /** A number after a dash, else after a word saying it, and the last of its range. */
    private fun byNumber(base: String): ByNumber? {
        val m = DASH_NUMBER.find(base) ?: WORD_NUMBER.find(base) ?: return null
        val first = m.groupValues[1].toInt().takeIf { it > 0 } ?: return null
        return ByNumber(m, first, m.groupValues.getOrNull(2)?.let { rangeOf(first, it) })
    }

    private class ByNumber(val match: MatchResult, val first: Int, val last: Int?)

    /** [Numbers] read at [m] in [base]: the show's name before it, the episode's title after. */
    @Suppress("LongParameterList") // A season, a range and how they are numbered, as Numbers holds them.
    private fun numbersAt(base: String, m: MatchResult, season: Int, first: Int, last: Int?, numbering: Numbering) =
        Numbers(season, first, last, base.substring(0, m.range.first), base.substring(m.range.last + 1), numbering)

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
                has("dv", "dovi", """dolby[ ._-]?vision""") -> add(HdrFormat.DOLBY_VISION.token)
                has("""hdr10\+""", "hdr10plus") -> add(HdrFormat.HDR10_PLUS.token)
                has("hdr10", "hdr") -> add(BadgeTokens.HDR)
            }
            if (has("atmos")) add(BadgeTokens.ATMOS)
            when {
                has("truehd") -> add(Codecs.TRUEHD)
                has("""dts[ ._-]?hd([ ._-]?ma)?""") -> add(Codecs.DTS_HD_MA)
                has("""dts[ ._-]?x""") -> add(BadgeTokens.DTS_X)
            }
            if (has("remux")) add(BadgeTokens.REMUX)
        }
    }

    /**
     * Removes [tags], {tags} and ids, also in parentheses ([NameIds]: "Film (tmdb-1999) (2020)" is of 2020), but keeps
     * a bracketed year: "Title [2024]".
     */
    private fun dropBrackets(s: String): String = BRACKETS.replace(NameIds.HINT.replace(s, " ")) { m ->
        YEAR.matchEntire(m.value.trim('[', ']', '{', '}').trim())?.let { " ${it.value} " } ?: " "
    }

    /** Dots and underscores separate words in scene names, unless the name already has spaces. */
    private fun normalizeSeparators(s: String): String =
        if (s.contains(' ')) s.replace('_', ' ') else s.replace('.', ' ').replace('_', ' ')

    private val SPACES = Regex("""\s+""")

    private fun clean(s: String): String =
        s.replace(SPACES, " ")
            .trim()
            .trim('-', '–', '(', ')', ' ', '.')
            .trim()

    /** Groups a show's episodes, with the folder's year when given ("doctor who|2005"), so remakes stay apart. */
    fun showKey(title: String, year: Int? = null): String =
        nfc(title).lowercase().replace(NOT_LETTERS, " ").trim().let { if (year == null) it else "$it$YEAR_MARK$year" }

    private val NOT_LETTERS = Regex("""[^\p{L}\p{N}]+""")

    const val YEAR_MARK = "|"
}
