package app.houselights.providers.local

import app.houselights.core.PlaybackReporter

/** Saves a local film or episode's progress as the player reports it. */
internal class LocalReporter(private val dao: LibraryDao, private val itemId: Long) : PlaybackReporter {
    /**
     * Only says when it was played: where the player starts is not progress, and starting over must
     * not lose the resume point before the viewer has watched anything.
     */
    override suspend fun started(positionMs: Long) {
        val entity = dao.item(itemId) ?: return
        dao.savePlayback(entity.id, entity.positionMs, entity.durationMs, entity.played, System.currentTimeMillis())
    }

    override suspend fun progress(positionMs: Long, durationMs: Long?, paused: Boolean) =
        save(positionMs, durationMs, finished = false)

    override suspend fun stopped(positionMs: Long, durationMs: Long?) = save(positionMs, durationMs, finished = true)

    private suspend fun save(positionMs: Long, durationMs: Long?, finished: Boolean) {
        val entity = dao.item(itemId) ?: return
        val duration = durationMs ?: entity.durationMs ?: entity.runtimeMin?.let { it * MS_PER_MINUTE }
        val watched = finished && duration != null && duration > 0 && positionMs >= duration * WATCHED_RATIO
        // Only the playback columns: a scan may be writing the others at the same time.
        dao.savePlayback(
            id = entity.id,
            positionMs = when {
                watched -> 0
                // Every report, not only the last one: the first ones come seconds after starting over.
                positionMs < MIN_RESUME_MS -> if (entity.played) 0 else entity.positionMs
                else -> positionMs
            },
            durationMs = durationMs ?: entity.durationMs,
            played = entity.played || watched,
            lastPlayedAt = System.currentTimeMillis(),
        )
    }

    private companion object {
        /** Watched past this share of its length. */
        const val WATCHED_RATIO = 0.9

        /** Before this, a film keeps its previous resume point (none once watched): started over, or by mistake. */
        const val MIN_RESUME_MS = 60_000L

        const val MS_PER_MINUTE = 60_000L
    }
}
