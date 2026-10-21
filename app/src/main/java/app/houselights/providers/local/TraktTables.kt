package app.houselights.providers.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * What the viewer did to a title, for Trakt: watched it, unmarked it (every play removed), stopped it at a
 * percentage, or dropped where it stood (its pause removed).
 */
enum class TraktAction { WATCHED, UNWATCHED, PROGRESS, UNPAUSED }

/**
 * Why the last exchange with Trakt failed: no answer, its limit, a refusal of the account or the app, a certificate
 * refused (the device's clock), an answer this version cannot read, the device's storage, or Trakt failing.
 */
enum class TraktSyncError { OFFLINE, BUSY, REFUSED, CERTIFICATE, UNREADABLE, FAILED, STORAGE }

/**
 * A title's latest intent for Trakt, kept until Trakt has it: one per [title] (a `TraktTitle` key), the newest
 * replacing the one before. [seq] orders them, so only the entry sent is removed, never one queued since.
 */
@Entity(tableName = "trakt_pending")
data class TraktPendingEntity(
    @PrimaryKey val title: String,
    val action: TraktAction,
    /** When it was watched, unmarked or stopped (ms). */
    val at: Long,
    /** Percent seen, for [TraktAction.PROGRESS]. */
    val progress: Float? = null,
    val seq: Long = 0,
    /** Writes Trakt answered with a failure, counted while it answers reads; dropped at a limit. */
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    /** When it was last sent (ms): a write left unanswered may have reached Trakt. */
    val sentAt: Long? = null,
)

/** Where the sync with Trakt stands: a single row, gone once the account is disconnected. */
@Entity(tableName = "trakt_state")
data class TraktStateEntity(
    @PrimaryKey val id: Int = 0,
    /** End of the last sync that read Trakt (ms); null before the first, which joins both sides. */
    val syncedAt: Long? = null,
    /** Titles watched, and in progress, among those Trakt and the library share, at that sync. */
    val watched: Int = 0,
    val inProgress: Int = 0,
    /** Why the last exchange failed; null once one succeeds. */
    val error: TraktSyncError? = null,
    /** Trakt's last activities read then, so a pull is skipped while they stay the same. */
    val activities: String? = null,
)

/** A film or episode file as Trakt names it: a film by its TMDB id, an episode by its show's and its numbers. */
data class SyncRow(
    val id: Long,
    val kind: ItemKind,
    /** The film's TMDB id, or the episode's show's. */
    val tmdbId: Int?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val lastEpisodeNumber: Int?,
    val versionOf: Long?,
    val parts: List<String>,
    val played: Boolean,
    val positionMs: Long,
    val lastPlayedAt: Long?,
    val durationMs: Long?,
    val fileDurationMs: Long?,
    val runtimeMin: Int?,
)

internal fun SyncRow.runtimeMs(): Long? = runtimeMs(parts, durationMs, fileDurationMs, runtimeMin)

private const val SYNC_ROW =
    "SELECT i.id, i.kind, CASE WHEN i.kind = 'MOVIE' THEN i.tmdbId ELSE s.tmdbId END AS tmdbId, i.seasonNumber, " +
        "i.episodeNumber, i.lastEpisodeNumber, i.versionOf, i.parts, i.played, i.positionMs, i.lastPlayedAt, i.durationMs, " +
        "i.fileDurationMs, i.runtimeMin FROM items i LEFT JOIN items s ON s.id = i.seriesId WHERE i.kind IN ('MOVIE', 'EPISODE')"

/** Another copy of film :id ([ItemEntity.versionOf]). */
private const val COPY_OF =
    "(i.kind = 'MOVIE' AND (i.id = (SELECT versionOf FROM items WHERE id = :id) " +
        "OR i.versionOf = (SELECT COALESCE(versionOf, id) FROM items WHERE id = :id AND kind = 'MOVIE')))"

/** Values for a queued intent, numbered after every other. */
private const val PENDING_VALUES =
    "(title, action, at, progress, seq) VALUES (:title, :action, :at, :progress, (SELECT IFNULL(MAX(seq), 0) + 1 FROM trakt_pending))"

/** The library as Trakt sees it, and what waits for Trakt. */
@Dao
interface TraktDao {
    @Query(SYNC_ROW)
    suspend fun rows(): List<SyncRow>

    /** Row [id]'s films or episodes: a film with its copies, an episode, a season's episodes or a show's. */
    @Query("$SYNC_ROW AND (i.id = :id OR i.parentId = :id OR i.seriesId = :id OR $COPY_OF)")
    suspend fun rowsOf(id: Long): List<SyncRow>

    @Query("$SYNC_ROW AND i.collectionId = :collectionId")
    suspend fun rowsOfSaga(collectionId: Long): List<SyncRow>

    /** Watched on Trakt at [at]: a resume point goes unless the row was played since. */
    @Query(
        "UPDATE items SET played = 1, positionMs = CASE WHEN lastPlayedAt > :at THEN positionMs ELSE 0 END, " +
            "lastPlayedAt = CASE WHEN :at > IFNULL(lastPlayedAt, 0) THEN :at ELSE lastPlayedAt END WHERE id IN (:ids)"
    )
    suspend fun watchedBatch(ids: List<Long>, at: Long)

    /** Trakt's pause at [at], unless the row was played since: a stop saved meanwhile stands. */
    @Query("UPDATE items SET positionMs = :positionMs, lastPlayedAt = :at WHERE id = :id AND IFNULL(lastPlayedAt, 0) < :at")
    suspend fun resumeAt(id: Long, positionMs: Long, at: Long)

    /** Copies of the film with main row [titleId], that row first, without NFO text. */
    @Query("SELECT $ITEM_ROW FROM items WHERE kind = 'MOVIE' AND (id = :titleId OR versionOf = :titleId) ORDER BY id")
    suspend fun copies(titleId: Long): List<ItemEntity>

    /** The copy of film [titleId] that Lire plays ([VersionRank.played]); null for a single copy. */
    @Transaction
    suspend fun playedCopy(titleId: Long): Long? = VersionRank.played(copies(titleId))

    /** Queues [title]'s intent in place of any before it. */
    @Query("INSERT OR REPLACE INTO trakt_pending $PENDING_VALUES")
    suspend fun queue(title: String, action: TraktAction, at: Long, progress: Float?)

    /** Queues [title]'s intent unless one of the viewer's waits already: a sync never overrides a mark. */
    @Query("INSERT OR IGNORE INTO trakt_pending $PENDING_VALUES")
    suspend fun offer(title: String, action: TraktAction, at: Long, progress: Float?)

    @Transaction
    suspend fun queue(entries: List<TraktPendingEntity>, replace: Boolean = true) = entries.forEach {
        if (replace) queue(it.title, it.action, it.at, it.progress) else offer(it.title, it.action, it.at, it.progress)
    }

    @Query("SELECT * FROM trakt_pending ORDER BY seq")
    suspend fun pending(): List<TraktPendingEntity>

    @Query("SELECT title FROM trakt_pending")
    suspend fun waiting(): List<String>

    @Query("UPDATE trakt_pending SET sentAt = :at WHERE title = :title AND seq = :seq")
    suspend fun sending(title: String, seq: Long, at: Long?)

    /** Notes when [entries] go to Trakt, unless queued again since. */
    @Transaction
    suspend fun sending(entries: List<TraktPendingEntity>, at: Long) = entries.forEach { sending(it.title, it.seq, at) }

    /** [entries] never reached Trakt: each keeps when it was sent before, if ever. */
    @Transaction
    suspend fun unsent(entries: List<TraktPendingEntity>) = entries.forEach { sending(it.title, it.seq, it.sentAt) }

    @Query("UPDATE trakt_pending SET attempts = attempts + 1 WHERE title = :title AND seq = :seq")
    suspend fun tried(title: String, seq: Long)

    @Query("DELETE FROM trakt_pending WHERE attempts >= :most")
    suspend fun dropTried(most: Int): Int

    /** Counts a failed write of [entries], then drops those that failed [most] times; returns how many. */
    @Transaction
    suspend fun failed(entries: List<TraktPendingEntity>, most: Int): Int {
        entries.forEach { tried(it.title, it.seq) }
        return dropTried(most)
    }

    @Query("DELETE FROM trakt_pending WHERE title = :title AND seq = :seq")
    suspend fun sent(title: String, seq: Long)

    /** Removes [entries] once Trakt has them, unless queued again since. */
    @Transaction
    suspend fun sent(entries: List<TraktPendingEntity>) = entries.forEach { sent(it.title, it.seq) }

    @Query("SELECT * FROM trakt_state WHERE id = 0")
    suspend fun state(): TraktStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveState(state: TraktStateEntity)

    @Query("DELETE FROM trakt_pending")
    suspend fun clearPending()

    @Query("DELETE FROM trakt_state")
    suspend fun clearState()

    /** Forgets the queue and the sync's state: the account is gone, the library stays as it is. */
    @Transaction
    suspend fun forget() {
        clearPending()
        clearState()
    }

    /**
     * Applies what a sync read from Trakt: rows watched at their dates, then resume points; a row whose [titles] (by
     * row id) wait for Trakt is left as the viewer made it since.
     */
    @Transaction
    suspend fun apply(watched: Map<Long, List<Long>>, resumes: List<Resume>, titles: Map<Long, List<String>>) {
        val waiting = waiting().toSet()
        val free = { id: Long -> titles[id].orEmpty().none { it in waiting } }
        watched.forEach { (at, ids) -> ids.filter(free).chunked(SQL_IN_LIMIT).forEach { watchedBatch(it, at) } }
        resumes.filter { free(it.id) }.forEach { resumeAt(it.id, it.positionMs, it.at) }
    }
}

/** A resume point Trakt paused at more recently than the row was played. */
data class Resume(val id: Long, val positionMs: Long, val at: Long)
