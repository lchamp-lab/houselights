package app.houselights.providers.local

import app.houselights.core.PlaybackReporter

/**
 * Saves a local film or episode's progress as the player reports it. A film in several files keeps one
 * resume point, its first part's ([part] 1), which the film's "Lire" resumes: a later part says only
 * when it was played. The film is watched at the end of its last part; the end of another is not its end.
 */
internal class LocalReporter(private val dao: LibraryDao, private val itemId: Long, private val part: Int = 1) : PlaybackReporter {
    /**
     * Only says when it was played: where the player starts is not progress, and starting over must
     * not lose the resume point before the viewer has watched anything.
     */
    override suspend fun started(positionMs: Long) = dao.setLastPlayedAt(itemId, System.currentTimeMillis())

    override suspend fun progress(positionMs: Long, durationMs: Long?, paused: Boolean) =
        save(positionMs, durationMs, finished = false)

    override suspend fun stopped(positionMs: Long, durationMs: Long?) = save(positionMs, durationMs, finished = true)

    /** The film's part after this one, while there is one: its file comes next in its folder. */
    override suspend fun next(): PlaybackReporter.Next? {
        // Part 2 is the first of the parts after the first file.
        val path = dao.playState(itemId)?.parts?.getOrNull(part - 1) ?: return null
        return PlaybackReporter.Next(path, LocalReporter(dao, itemId, part + 1), LocalId.ofPart(itemId, part + 1))
    }

    private suspend fun save(positionMs: Long, durationMs: Long?, finished: Boolean) {
        // Every ten seconds while it plays: a few columns, never the NFO text the whole row holds.
        val state = dao.playState(itemId) ?: return
        val watched = finished && part > state.parts.size && pastTheEnd(positionMs, durationMs ?: lengthOf(state))
        // A film's other copies are watched with the one watched to its end.
        if (watched && !state.played) dao.setCopiesPlayed(itemId)
        if (part > 1) {
            if (watched) dao.savePlayback(itemId, 0, state.durationMs, played = true, lastPlayedAt = System.currentTimeMillis())
            return
        }
        // Only the playback columns: a scan may be writing the others at the same time.
        dao.savePlayback(
            id = itemId,
            positionMs = when {
                watched -> 0
                // Every report, not only the last one: the first ones come seconds after starting over.
                positionMs < MIN_RESUME_MS -> if (state.played) 0 else state.positionMs
                else -> positionMs
            },
            durationMs = durationMs ?: state.durationMs,
            played = state.played || watched,
            lastPlayedAt = System.currentTimeMillis(),
        )
    }

    /**
     * The length the library knows of what plays, when the player gives none: the one its card shows
     * ([runtimeMs]). A part's is the player's alone: the library knows the film's, and its first file's.
     */
    private fun lengthOf(state: PlayState): Long? = if (state.parts.isNotEmpty()) null else state.runtimeMs()

    /** Watched past [WATCHED_RATIO] of its [duration], when it is known. */
    private fun pastTheEnd(positionMs: Long, duration: Long?) = duration != null && duration > 0 && positionMs >= duration * WATCHED_RATIO

    private companion object {
        /** Watched past this share of its length. */
        const val WATCHED_RATIO = 0.9

        /** Before this, a film keeps its previous resume point (none once watched): started over, or by mistake. */
        const val MIN_RESUME_MS = 60_000L
    }
}
