package app.houselights.providers.trakt

import app.houselights.providers.local.Resume
import app.houselights.providers.local.SyncRow
import app.houselights.providers.local.TraktAction
import app.houselights.providers.local.TraktPendingEntity
import app.houselights.providers.local.runtimeMs
import kotlin.math.abs

/**
 * What a sync does with what it read of Trakt: the rows Trakt saw watched, by date; the resume points Trakt paused at
 * later than the library played them; and, joining both sides, the titles only the library has seen or paused.
 */
internal class SyncPlan(
    val watched: Map<Long, List<Long>>,
    val resumes: List<Resume>,
    val send: List<TraktPendingEntity>,
    /** Titles watched, and in progress, once applied, of those Trakt can name. */
    val watchedCount: Int,
    val inProgressCount: Int,
) {
    val changes: Boolean get() = watched.isNotEmpty() || resumes.isNotEmpty()

    /** What a sync makes of one title. */
    private class Outcome(val seen: Boolean, val inProgress: Boolean, val resume: Resume?, val send: List<TraktPendingEntity>)

    /** Trakt's side of a sync: its [watched] titles by date, its pauses, the rows it marks; [union] sends what it lacks. */
    private class Planner(
        val watched: Map<TraktTitle, Long>,
        val paused: Map<TraktTitle, PlaybackProgress>,
        val marked: Set<Long>,
        val union: Boolean,
    ) {
        fun outcomeOf(title: TraktTitle, rows: List<SyncRow>): Outcome {
            val seen = rows.any { it.played || it.id in marked }
            val lastPlayed = rows.mapNotNull { it.lastPlayedAt }.maxOrNull() ?: 0L
            val send = mutableListOf<TraktPendingEntity>()
            if (union && title !in watched && rows.any { it.played }) send += TraktPendingEntity(title.key, TraktAction.WATCHED, lastPlayed)
            val main = mainOf(rows)
            val length = main?.runtimeMs()?.takeIf { it > 0 }
            var resume: Resume? = null
            if (main != null && length != null) {
                resume = resumeOf(main, length, lastPlayed, paused[title])
                if (resume == null && union) progressOf(title, main, length, lastPlayed)?.let { send += it }
            }
            val position = resume?.positionMs ?: main?.positionMs ?: 0L
            return Outcome(seen, !seen && position > 0, resume, send)
        }

        /** Trakt's pause, applied as a position of the file's [length] when more recent than the last play here. */
        private fun resumeOf(main: SyncRow, length: Long, lastPlayed: Long, pause: PlaybackProgress?): Resume? {
            if (pause == null) return null
            val pausedAt = millisOf(pause.pausedAt)?.takeIf { it > lastPlayed } ?: return null
            val position = (pause.progress.toDouble() / WHOLE * length).toLong()
            return Resume(main.id, position, pausedAt).takeUnless { abs(position - main.positionMs) * WHOLE / length < NEAR_PERCENT }
        }

        /** The library's resume point, for Trakt to keep, unless watched, barely started or where Trakt has it. */
        private fun progressOf(title: TraktTitle, main: SyncRow, length: Long, lastPlayed: Long): TraktPendingEntity? {
            if (main.played || main.positionMs <= 0) return null
            val percent = (main.positionMs.toDouble() / length * WHOLE).toFloat()
            if (percent < MIN_PERCENT || percent >= WATCHED_PERCENT) return null
            val pause = paused[title]
            if (pause != null && abs(pause.progress - percent) < NEAR_PERCENT) return null
            return TraktPendingEntity(title.key, TraktAction.PROGRESS, lastPlayed, percent)
        }
    }

    companion object {
        /** Trakt watches a title from this percentage; nothing under the lowest is kept as progress. */
        const val WATCHED_PERCENT = 80f
        const val MIN_PERCENT = 1f

        /** Positions closer than this share of the length are the same. */
        private const val NEAR_PERCENT = 1f
        private const val WHOLE = 100f

        /**
         * The plan for [rows] given Trakt's [watched] titles (by date, ms) and [paused] playbacks: watched wherever
         * either side saw it, the most recent pause winning; [union] also sends what Trakt lacks.
         */
        fun of(rows: List<SyncRow>, watched: Map<TraktTitle, Long>, paused: List<PlaybackProgress>, union: Boolean): SyncPlan {
            val titled = rows.map { it to it.titles() }.filter { it.second.isNotEmpty() }
            // A file holding several episodes is watched once all are.
            val marks = titled.filter { (row, titles) -> !row.played && titles.all { it in watched } }
                .groupBy({ (_, titles) -> titles.maxOf { watched.getValue(it) } }, { (row, _) -> row.id })
            val pausedBy = paused.mapNotNull { p -> p.title()?.let { it to p } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, list) -> list.maxBy { millisOf(it.pausedAt) ?: 0L } }
            val planner = Planner(watched, pausedBy, marks.values.flatten().toSet(), union)
            val outcomes = titled.flatMap { (row, titles) -> titles.map { it to row } }
                .groupBy({ it.first }, { it.second })
                .map { (title, titleRows) -> planner.outcomeOf(title, titleRows) }
            return SyncPlan(
                marks,
                outcomes.mapNotNull { it.resume },
                outcomes.flatMap { it.send },
                watchedCount = outcomes.count { it.seen },
                inProgressCount = outcomes.count { it.inProgress },
            )
        }

        /** The row a title resumes from: a single file holding one title, its film's main copy first. */
        private fun mainOf(rows: List<SyncRow>): SyncRow? =
            rows.filter { it.parts.isEmpty() && it.titles().size == 1 }.minWithOrNull(compareBy({ it.versionOf != null }, { it.id }))
    }
}
