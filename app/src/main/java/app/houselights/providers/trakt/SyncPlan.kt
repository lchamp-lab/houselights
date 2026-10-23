package app.houselights.providers.trakt

import app.houselights.providers.local.Resume
import app.houselights.providers.local.SyncRow
import app.houselights.providers.local.TraktAction
import app.houselights.providers.local.TraktPendingEntity
import app.houselights.providers.local.WatchHistory
import app.houselights.providers.local.runtimeMs
import kotlin.math.abs

/** Which of Trakt's histories were read whole: with an entry left out unread, Trakt may have seen what it seems to lack. */
internal class WholeHistory(val movies: Boolean = true, val episodes: Boolean = true) {
    fun of(title: TraktTitle) = if (title is TraktTitle.Movie) movies else episodes
}

/**
 * What a sync does with what it read of Trakt: the rows Trakt saw watched, by date; the resume points Trakt paused at
 * since the title was last played here and last watched there; and, joining both sides, the titles only the library
 * has seen or paused.
 */
internal class SyncPlan(
    val watched: Map<Long, List<Long>>,
    val resumes: List<Resume>,
    val send: List<TraktPendingEntity>,
    /** The titles of the rows [watched] and [resumes] change, by row id: a row whose title waits for Trakt is left. */
    val titles: Map<Long, List<String>>,
    /** Titles watched, and in progress, once applied, of those Trakt can name. */
    val watchedCount: Int,
    val inProgressCount: Int,
) {
    val changes: Boolean get() = watched.isNotEmpty() || resumes.isNotEmpty()

    /** What a sync makes of one title. */
    private class Outcome(val seen: Boolean, val inProgress: Boolean, val resume: Resume?, val send: List<TraktPendingEntity>)

    /** Trakt's pause of a title: at [progress] percent, made at [at] (ms; null when Trakt does not say). */
    private class Pause(val progress: Float, val at: Long?)

    /**
     * Trakt's side of a sync: its [watched] titles by date, its pauses, the rows it marks, the copies Lire plays
     * ([played]); [union] sends what it lacks.
     */
    private class Planner(
        val watched: Map<TraktTitle, Long>,
        val paused: Map<TraktTitle, Pause>,
        val marked: Set<Long>,
        val played: Set<Long>,
        val union: Boolean,
        val whole: WholeHistory,
    ) {
        fun outcomeOf(title: TraktTitle, rows: List<SyncRow>): Outcome {
            val seenThere = watched[title] ?: 0L
            val main = mainOf(rows)
            val resume = main?.let { resumeOf(title, it, seenThere) }
            val send = if (!union) emptyList() else listOfNotNull(
                playOf(title, rows),
                main?.takeIf { resume == null }?.let { progressOf(title, it, seenThere) },
            )
            val seen = rows.any { it.played || it.id in marked }
            val position = resume?.positionMs ?: main?.positionMs ?: 0L
            return Outcome(seen, !seen && position > 0, resume, send)
        }

        /** Watched here, never on Trakt: Trakt gets the last play here, unless its history of the kind was read in part. */
        private fun playOf(title: TraktTitle, rows: List<SyncRow>): TraktPendingEntity? {
            if (title in watched || rows.none { it.played } || !whole.of(title)) return null
            return TraktPendingEntity(title.key, TraktAction.WATCHED, rows.mapNotNull { it.lastPlayedAt }.maxOrNull() ?: 0L)
        }

        /** The row a title resumes from: a single file holding one title, the copy Lire plays, else the film's main copy. */
        private fun mainOf(rows: List<SyncRow>): SyncRow? {
            val files = rows.filter { it.parts.isEmpty() && it.titles().size == 1 }
            return files.firstOrNull { it.id in played } ?: files.minWithOrNull(compareBy({ it.versionOf != null }, { it.id }))
        }

        /**
         * Trakt's pause, as a position of the file's length, when made since the last play here and the last play
         * Trakt knows ([seenThere]).
         */
        private fun resumeOf(title: TraktTitle, main: SyncRow, seenThere: Long): Resume? {
            val length = main.runtimeMs()?.takeIf { it > 0 } ?: return null
            val pause = paused[title] ?: return null
            val pausedAt = pause.at?.takeIf { it > maxOf(main.lastPlayedAt ?: 0L, seenThere) } ?: return null
            val position = (pause.progress.toDouble() / WHOLE * length).toLong()
            return Resume(main.id, position, pausedAt).takeUnless { abs(position - main.positionMs) * WHOLE / length < NEAR_PERCENT }
        }

        /**
         * The library's resume point, for Trakt to keep, unless watched, barely started or where Trakt has it; or
         * watched on Trakt since it was played here ([seenThere]), when it is no longer where the viewer is.
         */
        private fun progressOf(title: TraktTitle, main: SyncRow, seenThere: Long): TraktPendingEntity? {
            val length = main.runtimeMs()?.takeIf { it > 0 } ?: return null
            val playedHere = main.lastPlayedAt ?: 0L
            if (main.played || main.positionMs <= 0 || seenThere > playedHere) return null
            val percent = (main.positionMs.toDouble() / length * WHOLE).toFloat()
            val pause = paused[title]
            val kept = pause != null && abs(pause.progress - percent) < NEAR_PERCENT
            if (percent < MIN_PERCENT || percent >= WATCHED_PERCENT || kept) return null
            return TraktPendingEntity(title.key, TraktAction.PROGRESS, playedHere, percent)
        }
    }

    companion object {
        /** Trakt watches a title from this percentage; nothing under the lowest is kept as progress. */
        const val WATCHED_PERCENT = WatchHistory.WATCHED_PERCENT
        const val MIN_PERCENT = 1f

        /** Positions closer than this share of the length are the same. */
        private const val NEAR_PERCENT = 1f
        private const val WHOLE = 100f

        /**
         * The plan for [rows] given Trakt's [watched] titles (by date, ms) and [paused] playbacks: watched wherever
         * either side saw it; Trakt's latest pause placed when made since the last play here (on the copy Lire plays,
         * among [played]) and the last play Trakt knows; [union] also sends what Trakt lacks.
         */
        fun of(
            rows: List<SyncRow>,
            watched: Map<TraktTitle, Long>,
            paused: List<PlaybackProgress>,
            union: Boolean,
            played: Set<Long> = emptySet(),
            whole: WholeHistory = WholeHistory(),
        ): SyncPlan {
            val titled = rows.map { it to it.titles() }.filter { it.second.isNotEmpty() }
            // A file holding several episodes is watched once all are.
            val marks = titled.filter { (row, titles) -> !row.played && titles.all { it in watched } }
                .groupBy({ (_, titles) -> titles.maxOf { watched.getValue(it) } }, { (row, _) -> row.id })
            // A pause without a title the library can name, or without a progress, cannot be placed.
            val pausedBy = paused
                .mapNotNull { p ->
                    val title = p.title()
                    val progress = p.progress
                    if (title != null && progress != null) title to Pause(progress, millisOf(p.pausedAt)) else null
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, list) -> list.maxBy { it.at ?: 0L } }
            val planner = Planner(watched, pausedBy, marks.values.flatten().toSet(), played, union, whole)
            val outcomes = titled.flatMap { (row, titles) -> titles.map { it to row } }
                .groupBy({ it.first }, { it.second })
                .map { (title, titleRows) -> planner.outcomeOf(title, titleRows) }
            val resumes = outcomes.mapNotNull { it.resume }
            val changed = planner.marked + resumes.map { it.id }
            return SyncPlan(
                marks,
                resumes,
                outcomes.flatMap { it.send },
                titled.filter { (row, _) -> row.id in changed }.associate { (row, titles) -> row.id to titles.map { it.key } },
                watchedCount = outcomes.count { it.seen },
                inProgressCount = outcomes.count { it.inProgress },
            )
        }
    }
}
