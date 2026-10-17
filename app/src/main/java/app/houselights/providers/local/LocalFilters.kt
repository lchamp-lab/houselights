package app.houselights.providers.local

import app.houselights.core.BadgeTokens
import app.houselights.core.Definition
import app.houselights.core.FileTypes
import app.houselights.core.HdrFormat
import app.houselights.core.LibraryFilter
import app.houselights.core.Quality
import app.houselights.core.WatchStatus

/**
 * What a wall of the local library holds, as SQL on its items ([LibraryFilter]): titles alone (a film's other
 * copies are none, [ItemEntity.versionOf]), then each filter. What a file has (its picture, its sound, its kind)
 * a film has when one of its copies has it, and a show when one of its episodes does.
 */
internal object LocalFilters {
    /** The condition of a page's query, and its arguments in order. */
    class Where(val sql: String, val args: List<Any>)

    fun where(kind: ItemKind, filter: LibraryFilter): Where {
        val args = mutableListOf<Any>()
        val clauses = mutableListOf("kind = '${kind.name}'")
        if (kind == ItemKind.MOVIE) clauses += "versionOf IS NULL"
        status(kind, filter.status)?.let(clauses::add)
        filter.genre?.let {
            clauses += PipeList.matchesAnyCase("genres")
            args += it
        }
        filter.decade?.let {
            clauses += "year BETWEEN ? AND ?"
            args += it
            args += it + DECADE_LAST_YEAR
        }
        filter.person?.let {
            // A film's credits are its title's row's, a show's its show's.
            clauses += "id IN (SELECT itemId FROM credits WHERE personKey = ?)"
            args += it
        }
        val files = fileConditions(filter)
        if (files.isNotEmpty()) clauses += anyFile(kind) { prefix -> files.joinToString(" AND ") { it(prefix) } }
        return Where(clauses.joinToString(" AND "), args)
    }

    private fun status(kind: ItemKind, status: WatchStatus): String? {
        val show = kind == ItemKind.SERIES
        val episodes = "SELECT 1 FROM items e WHERE e.seriesId = items.id AND e.kind = 'EPISODE'"
        return when (status) {
            WatchStatus.ALL -> null
            WatchStatus.UNWATCHED -> if (show) "EXISTS ($episodes AND e.played = 0)" else "played = 0 AND positionMs = 0"
            WatchStatus.IN_PROGRESS ->
                if (show) "EXISTS ($episodes AND (e.played = 1 OR e.positionMs > 0)) AND EXISTS ($episodes AND e.played = 0)"
                else "played = 0 AND positionMs > 0"
            WatchStatus.WATCHED -> if (show) "NOT EXISTS ($episodes AND e.played = 0)" else "played = 1"
            WatchStatus.FAVORITES -> "favorite = 1"
        }
    }

    /** What [filter] asks of a file, each condition on the columns of the row the prefix names ("", "v.", "e."). */
    private fun fileConditions(filter: LibraryFilter): List<(String) -> String> = listOfNotNull(
        filter.quality?.let { quality -> { p: String -> qualityCondition(quality, "${p}badges") } },
        filter.picture?.let { token -> { p: String -> PipeList.matches("${p}badges", literal(token)) } },
        filter.sound?.let { token -> { p: String -> PipeList.matches("${p}badges", literal(token)) } },
        filter.fileType?.let { type -> { p: String -> fileTypeCondition("${p}filePath", type) } },
    )

    /**
     * [condition] holds for a film or one of its other copies ([ItemEntity.versionOf]), for one of a show's
     * episodes: its columns are those of the row the prefix it is given names.
     */
    private fun anyFile(kind: ItemKind, condition: (String) -> String): String =
        if (kind == ItemKind.SERIES) {
            "EXISTS (SELECT 1 FROM items e WHERE e.seriesId = items.id AND e.kind = 'EPISODE' AND ${condition("e.")})"
        } else {
            "(${condition("")} OR EXISTS (SELECT 1 FROM items v WHERE v.versionOf = items.id AND ${condition("v.")}))"
        }

    /** [column], a row's badges, holds [quality]. */
    fun qualityCondition(quality: Quality, column: String): String {
        // Badges are the app's own tokens, never a quote in them.
        fun holds(token: String) = PipeList.matches(column, "'$token'")
        return when (quality) {
            Quality.UHD_4K -> holds(Definition.ULTRA_HD.token)
            Quality.HDR -> HDR_BADGES.joinToString(" OR ", "(", ")") { holds(it) }
            Quality.DOLBY_VISION -> holds(HdrFormat.DOLBY_VISION.token)
        }
    }

    /**
     * [column], a file's path, is of the kind [type] ([fileTypeOf]): a disc's stream by its folder, any other
     * file by its extension.
     */
    private fun fileTypeCondition(column: String, type: String): String = when (type) {
        FileTypes.BLU_RAY -> "$column LIKE '%$BLU_RAY_STREAMS%'"
        FileTypes.DVD -> "$column LIKE '%$DVD_STREAMS%'"
        else -> "$column LIKE ${literal("%.$type")} AND $column NOT LIKE '%$BLU_RAY_STREAMS%' AND $column NOT LIKE '%$DVD_STREAMS%'"
    }

    /** The kind of the file at [path] ([FileTypes]): a disc's folder, else its extension in lower case. */
    fun fileTypeOf(path: String): String {
        val disc = FilenameParser.discOf(path)
        return when {
            disc == null -> FilenameParser.extension(path)
            disc.index.startsWith("BDMV") -> FileTypes.BLU_RAY
            else -> FileTypes.DVD
        }
    }

    /** The kind of file of a path ending in [end] ([LibraryDao.fileEnds]), as [fileTypeOf] tells it. */
    fun fileTypeOf(end: FileEnd): String? = when {
        end.bluRay -> FileTypes.BLU_RAY
        end.dvd -> FileTypes.DVD
        '.' in end.tail -> end.tail.substringAfterLast('.')
        else -> null
    }

    /** [text] as an SQL string, its quotes doubled. */
    private fun literal(text: String) = "'" + text.replace("'", "''") + "'"

    private const val DECADE_LAST_YEAR = 9
    private const val BLU_RAY_STREAMS = "BDMV/STREAM/"
    private const val DVD_STREAMS = "VIDEO_TS/"

    /**
     * The badges the HDR filter keeps, and its menu offers it for: Dolby Vision, HDR10+ and HDR10, and
     * a name's plain HDR. HLG, a broadcast range, has never been among them.
     */
    val HDR_BADGES = listOf(HdrFormat.DOLBY_VISION, HdrFormat.HDR10_PLUS, HdrFormat.HDR10).map(HdrFormat::token) + BadgeTokens.HDR

    /** Picture tokens of badges, best first: definitions, then ranges. */
    val PICTURE_TOKENS = Definition.entries.map { it.token } + HdrFormat.entries.map { it.token } + BadgeTokens.HDR

    /** What badges hold that is neither picture nor sound: a layout ("7.1") and how the file was made. */
    private val CHANNELS = Regex("""\d\.\d|\d+ ch""")

    /** A badge token names the file's sound: neither its picture, nor its channels, nor how it was made. */
    fun isSound(token: String): Boolean = token !in PICTURE_TOKENS && token != BadgeTokens.REMUX && !CHANNELS.matches(token)
}
