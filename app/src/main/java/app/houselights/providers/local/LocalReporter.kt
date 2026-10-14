package app.houselights.providers.local

import app.houselights.core.PlaybackReporter

/** Saves a local film or episode's progress as the player reports it. */
internal class LocalReporter(private val dao: LibraryDao, private val itemId: Long) : PlaybackReporter {
    override suspend fun started(positionMs: Long) = save(positionMs, null, finished = false)

    override suspend fun progress(positionMs: Long, durationMs: Long?, paused: Boolean) =
        save(positionMs, durationMs, finished = false)

    override suspend fun stopped(positionMs: Long, durationMs: Long?) = save(positionMs, durationMs, finished = true)

    private suspend fun save(positionMs: Long, durationMs: Long?, finished: Boolean) {
        val entity = dao.item(itemId) ?: return
        val duration = durationMs ?: entity.durationMs ?: entity.runtimeMin?.let { it * MS_PER_MINUTE }
        val watched = duration != null && duration > 0 && positionMs >= duration * WATCHED_RATIO
        // Only the playback columns: a scan may be writing the others at the same time.
        dao.savePlayback(
            id = entity.id,
            positionMs = when {
                finished && watched -> 0
                finished && positionMs < MIN_RESUME_MS -> entity.positionMs.takeIf { !entity.played } ?: 0
                else -> positionMs
            },
            durationMs = durationMs ?: entity.durationMs,
            played = entity.played || (finished && watched),
            lastPlayedAt = System.currentTimeMillis(),
        )
    }

    private companion object {
        /** Watched past this share of its length. */
        const val WATCHED_RATIO = 0.9

        /** Stopped before this, a film keeps its previous resume point. */
        const val MIN_RESUME_MS = 60_000L

        const val MS_PER_MINUTE = 60_000L
    }
}
