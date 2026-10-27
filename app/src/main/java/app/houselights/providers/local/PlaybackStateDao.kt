package app.houselights.providers.local

import androidx.room.Query

/** Where a title's playback stands, as its reports read and write it ([LocalReporter]; [LibraryDao] extends it). */
interface PlaybackStateDao {
    /** Columns needed by playback progress reports, read every ten seconds during playback. */
    @Query("SELECT parts, positionMs, durationMs, fileDurationMs, runtimeMin, played FROM items WHERE id = :id")
    suspend fun playState(id: Long): PlayState?

    @Query(
        "UPDATE items SET positionMs = :positionMs, durationMs = :durationMs, played = :played, " +
            "lastPlayedAt = :lastPlayedAt WHERE id = :id",
    )
    suspend fun savePlayback(id: Long, positionMs: Long, durationMs: Long?, played: Boolean, lastPlayedAt: Long)

    /** Writes only lastPlayedAt, since a late report from the previous session may update the rest meanwhile. */
    @Query("UPDATE items SET lastPlayedAt = :now WHERE id = :id")
    suspend fun setLastPlayedAt(id: Long, now: Long)

    /** Marks the other copies of film [id] watched, keeping their resume points, after one copy was finished. */
    @Query("UPDATE items SET played = 1 WHERE played = 0 AND $COPIES")
    suspend fun setCopiesPlayed(id: Long)
}
