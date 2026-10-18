package app.houselights.providers.local

import app.houselights.core.PlaybackReporter

/**
 * Saves playback progress. A multi-file film keeps one resume point, on [part] 1; later parts only record the play
 * time, and the film is watched at the end of its last part.
 */
internal class LocalReporter(private val dao: LibraryDao, private val itemId: Long, private val part: Int = 1) : PlaybackReporter {
    /** Records only the play time, so starting over does not lose the resume point before anything was watched. */
    override suspend fun started(positionMs: Long) = dao.setLastPlayedAt(itemId, System.currentTimeMillis())

    override suspend fun progress(positionMs: Long, durationMs: Long?, paused: Boolean) =
        save(positionMs, durationMs, finished = false)

    override suspend fun stopped(positionMs: Long, durationMs: Long?) = save(positionMs, durationMs, finished = true)

    override suspend fun next(): PlaybackReporter.Next? {
        // parts lists the files after the first, so part 2 is index 0.
        val path = dao.playState(itemId)?.parts?.getOrNull(part - 1) ?: return null
        return PlaybackReporter.Next(path, LocalReporter(dao, itemId, part + 1), LocalId.ofPart(itemId, part + 1))
    }

    private suspend fun save(positionMs: Long, durationMs: Long?, finished: Boolean) {
        // Called every ten seconds during playback: read a few columns, never the row's NFO text.
        val state = dao.playState(itemId) ?: return
        val watched = finished && part > state.parts.size && pastTheEnd(positionMs, durationMs ?: lengthOf(state))
        if (watched) copiesWatched(state)
        if (part > 1) {
            if (watched) dao.savePlayback(itemId, 0, state.durationMs, played = true, lastPlayedAt = System.currentTimeMillis())
            return
        }
        // Write only playback columns, as a scan may be writing the others concurrently.
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

    private suspend fun copiesWatched(state: PlayState) {
        if (!state.played) dao.setCopiesPlayed(itemId)
    }

    /** Fallback duration ([runtimeMs]); null for a part, whose own duration only the player knows. */
    private fun lengthOf(state: PlayState): Long? = if (state.parts.isNotEmpty()) null else state.runtimeMs()

    private fun pastTheEnd(positionMs: Long, duration: Long?) = duration != null && duration > 0 && positionMs >= duration * WATCHED_RATIO

    private companion object {
        const val WATCHED_RATIO = 0.9

        /** Below this, the previous resume point is kept: a restart or an accidental start. */
        const val MIN_RESUME_MS = 60_000L
    }
}
