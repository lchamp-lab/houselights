package app.houselights.providers.local

import app.houselights.core.BadgeTokens
import app.houselights.core.Definition
import app.houselights.core.FileTypes
import app.houselights.core.HdrFormat
import app.houselights.core.LibraryFilter
import app.houselights.core.Quality
import app.houselights.core.WatchStatus

/**
 * SQL for a library wall ([LibraryFilter]), main rows only. File filters match a film when any copy matches, and a
 * show when any episode does.
 */
internal object LocalFilters {
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
            // Credits are stored on the film's main row and on the show row.
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
                if (show) {
                    "EXISTS ($episodes AND (e.played = 1 OR e.positionMs > 0)) AND EXISTS ($episodes AND e.played = 0)"
                } else {
                    "played = 0 AND positionMs > 0"
                }
            WatchStatus.WATCHED -> if (show) "NOT EXISTS ($episodes AND e.played = 0)" else "played = 1"
            WatchStatus.FAVORITES -> "favorite = 1"
        }
    }

    /** File conditions of [filter], each taking the row alias prefix ("", "v.", "e."). */
    private fun fileConditions(filter: LibraryFilter): List<(String) -> String> = listOfNotNull(
        filter.quality?.let { quality -> { p: String -> qualityCondition(quality, "${p}badges") } },
        filter.picture?.let { token -> { p: String -> PipeList.matches("${p}badges", literal(token)) } },
        filter.sound?.let { token -> { p: String -> PipeList.matches("${p}badges", literal(token)) } },
        filter.fileType?.let { type -> { p: String -> fileTypeCondition("${p}filePath", type) } },
    )

    private fun anyFile(kind: ItemKind, condition: (String) -> String): String =
        if (kind == ItemKind.SERIES) {
            "EXISTS (SELECT 1 FROM items e WHERE e.seriesId = items.id AND e.kind = 'EPISODE' AND ${condition("e.")})"
        } else {
            "(${condition("")} OR EXISTS (SELECT 1 FROM items v WHERE v.versionOf = items.id AND ${condition("v.")}))"
        }

    fun qualityCondition(quality: Quality, column: String): String {
        // Badge tokens are the app's own and contain no quotes.
        fun holds(token: String) = PipeList.matches(column, "'$token'")
        return when (quality) {
            Quality.UHD_4K -> holds(Definition.ULTRA_HD.token)
            Quality.HDR -> HDR_BADGES.joinToString(" OR ", "(", ")") { holds(it) }
            Quality.DOLBY_VISION -> holds(HdrFormat.DOLBY_VISION.token)
        }
    }

    private fun fileTypeCondition(column: String, type: String): String = when (type) {
        FileTypes.BLU_RAY -> "$column LIKE '%$BLU_RAY_STREAMS%'"
        FileTypes.DVD -> "$column LIKE '%$DVD_STREAMS%'"
        else -> "$column LIKE ${literal("%.$type")} AND $column NOT LIKE '%$BLU_RAY_STREAMS%' AND $column NOT LIKE '%$DVD_STREAMS%'"
    }

    /** File type of [path] ([FileTypes]): the disc type, else the lowercase extension. */
    fun fileTypeOf(path: String): String {
        val disc = FilenameParser.discOf(path)
        return when {
            disc == null -> FilenameParser.extension(path)
            disc.index.startsWith("BDMV") -> FileTypes.BLU_RAY
            else -> FileTypes.DVD
        }
    }

    /** File type of a path ending in [end] ([LibraryDao.fileEnds]), consistent with [fileTypeOf]. */
    fun fileTypeOf(end: FileEnd): String? = when {
        end.bluRay -> FileTypes.BLU_RAY
        end.dvd -> FileTypes.DVD
        '.' in end.tail -> end.tail.substringAfterLast('.')
        else -> null
    }

    private fun literal(text: String) = "'" + text.replace("'", "''") + "'"

    private const val DECADE_LAST_YEAR = 9
    private const val BLU_RAY_STREAMS = "BDMV/STREAM/"
    private const val DVD_STREAMS = "VIDEO_TS/"

    /** Badges matched by the HDR filter; HLG, a broadcast format, is excluded. */
    val HDR_BADGES = listOf(HdrFormat.DOLBY_VISION, HdrFormat.HDR10_PLUS, HdrFormat.HDR10).map(HdrFormat::token) + BadgeTokens.HDR

    /** Picture badge tokens, best first: definitions, then dynamic ranges. */
    val PICTURE_TOKENS = Definition.entries.map { it.token } + HdrFormat.entries.map { it.token } + BadgeTokens.HDR

    /** Channel layout badges ("7.1", "6 ch"), which are not sound formats. */
    private val CHANNELS = Regex("""\d\.\d|\d+ ch""")

    /** True for a sound format badge: not picture, channel layout or remux. */
    fun isSound(token: String): Boolean = token !in PICTURE_TOKENS && token != BadgeTokens.REMUX && !CHANNELS.matches(token)
}
