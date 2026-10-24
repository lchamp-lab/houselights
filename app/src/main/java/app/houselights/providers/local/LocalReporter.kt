package app.houselights.providers.local

import app.houselights.core.PlaybackReporter

/**
 * Saves playback progress and tells [history] where it stopped. A multi-file film keeps one resume point, on [part]
 * 1; later parts only record the play time, and the film is watched at the end of its last part.
 */
internal class LocalReporter(
    private val dao: PlaybackStateDao,
    private val itemId: Long,
    private val part: Int = 1,
    private val history: WatchHistory = WatchHistory.None,
) : PlaybackReporter {
    /** Records only the play time, so starting over does not lose the resume point before anything was watched. */
    override suspend fun started(positionMs: Long) = dao.setLastPlayedAt(itemId, System.currentTimeMillis())

    override suspend fun progress(positionMs: Long, durationMs: Long?, paused: Boolean) =
        save(positionMs, durationMs, finished = false)

    override suspend fun stopped(positionMs: Long, durationMs: Long?) = save(positionMs, durationMs, finished = true)

    override suspend fun next(): PlaybackReporter.Next? {
        // parts lists the files after the first, so part 2 is index 0.
        val path = dao.playState(itemId)?.parts?.getOrNull(part - 1) ?: return null
        return PlaybackReporter.Next(path, LocalReporter(dao, itemId, part + 1, history), LocalId.ofPart(itemId, part + 1))
    }

    private suspend fun save(positionMs: Long, durationMs: Long?, finished: Boolean) {
        // Called every ten seconds during playback: read a few columns, never the row's NFO text.
        val state = dao.playState(itemId) ?: return
        val length = durationMs ?: lengthOf(state)
        val watched = finished && part > state.parts.size && pastTheEnd(positionMs, length)
        if (watched) copiesWatched(state)
        if (part > 1) {
            if (watched) dao.savePlayback(itemId, 0, state.durationMs, played = true, lastPlayedAt = System.currentTimeMillis())
        } else {
            saveResumePoint(state, positionMs, durationMs, watched)
        }
        // Trakt hears the length the player or the file gives: a stated runtime may be another cut's.
        if (finished) tell(state, positionMs, durationMs ?: state.durationMs ?: state.fileDurationMs, watched)
    }

    /** Writes only playback columns, as a scan may be writing the others concurrently. */
    private suspend fun saveResumePoint(state: PlayState, positionMs: Long, durationMs: Long?, watched: Boolean) {
        dao.savePlayback(
            id = itemId,
            positionMs = when {
                watched -> 0
                // Applies to every report, as the first ones arrive seconds after starting over.
                positionMs < MIN_RESUME_MS -> if (state.played) 0 else state.positionMs
                else -> positionMs
            },
            durationMs = durationMs ?: state.durationMs,
            played = state.played || watched,
            lastPlayedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Where a single file stopped, when the player or the file gives its [length] and it moved the resume point; a
     * multi-part film only once watched, as the length of the whole is unknown.
     */
    private suspend fun tell(state: PlayState, positionMs: Long, length: Long?, watched: Boolean) {
        val percent = when {
            state.parts.isNotEmpty() -> if (watched) WHOLE else return
            length == null || length <= 0 -> return
            !watched && positionMs < MIN_RESUME_MS -> return
            else -> (positionMs.toDouble() / length * WHOLE).toFloat().coerceAtMost(WHOLE)
        }
        history.stopped(itemId, percent)
    }

    private suspend fun copiesWatched(state: PlayState) {
        if (!state.played) dao.setCopiesPlayed(itemId)
    }

    /** Fallback duration ([runtimeMs]); null for a part, whose own duration only the player knows. */
    private fun lengthOf(state: PlayState): Long? = if (state.parts.isNotEmpty()) null else state.runtimeMs()

    private fun pastTheEnd(positionMs: Long, duration: Long?) =
        duration != null && duration > 0 && positionMs >= duration * WatchHistory.WATCHED_PERCENT / WHOLE

    private companion object {
        const val WHOLE = 100f

        /** Below this, the previous resume point is kept: a restart or an accidental start. */
        const val MIN_RESUME_MS = 60_000L
    }
}
