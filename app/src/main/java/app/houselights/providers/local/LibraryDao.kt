package app.houselights.providers.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/**
 * Ids bound by one statement at most: Android 9's SQLite (3.22) takes at most 999 values in a
 * statement, and lists of ids can be longer. Robolectric's SQLite takes more: tests count them.
 */
internal const val SQL_IN_LIMIT = 500

private const val SET_PLAYED =
    "UPDATE items SET played = :played, positionMs = 0, lastPlayedAt = CASE WHEN :played THEN :now ELSE lastPlayedAt END"

/** A folder's password as the database keeps it: sealed text, or plain text from before sealing. */
data class StoredPassword(val id: Long, val password: String) {
    override fun toString() = "StoredPassword($id, ***)"
}

@Dao
interface LibraryDao {
    // Sources
    @Query("SELECT * FROM sources ORDER BY kind, name")
    fun observeSources(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY kind, name")
    suspend fun sources(): List<SourceEntity>

    @Query("SELECT id, firstScanAt FROM sources")
    suspend fun firstScans(): List<SourceFirstScan>

    @Insert
    suspend fun insertSource(source: SourceEntity): Long

    @Update
    suspend fun updateSource(source: SourceEntity)

    /**
     * A folder's new address or sign-in, column by column: it keeps its id, so its titles and what the
     * user did with them stay, and its kind and scan dates. [name] follows from the path.
     */
    @Query(
        "UPDATE sources SET name = :name, protocol = :protocol, host = :host, share = :share, path = :path, " +
            "username = :username, password = :password WHERE id = :id"
    )
    @Suppress("LongParameterList") // One per column written.
    suspend fun updateConnection(
        id: Long,
        name: String,
        protocol: Protocol,
        host: String,
        share: String,
        path: String,
        username: String,
        password: Secret,
    )

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun source(id: Long): SourceEntity?

    /** Folders' passwords as stored, sealed or not: read as text, they are not opened ([LibraryDatabase.sealPlainPasswords]). */
    @Query("SELECT id, password FROM sources WHERE password != ''")
    suspend fun storedPasswords(): List<StoredPassword>

    /** Writes [sealed] in place of the [plain] password read, unless the folder was saved meanwhile. */
    @Query("UPDATE sources SET password = :sealed WHERE id = :id AND password = :plain")
    suspend fun sealPassword(id: Long, plain: String, sealed: String)

    /**
     * The end of a scan, written alone: the rest of the folder's row may have changed during the
     * scan, and writing it again would seal its password again.
     */
    @Query("UPDATE sources SET lastScanAt = :at, firstScanAt = CASE WHEN firstScanAt = 0 THEN :at ELSE firstScanAt END WHERE id = :id")
    suspend fun markScanned(id: Long, at: Long)

    // What the user changes is written column by column, so it never overwrites a scan's work.
    // Watched state goes by parent, never by a list of ids: a long show holds thousands of episodes.

    @Query("$SET_PLAYED WHERE id = :id")
    suspend fun setPlayed(id: Long, played: Boolean, now: Long)

    @Query("$SET_PLAYED WHERE kind = 'EPISODE' AND seriesId = :seriesId")
    suspend fun setSeriesPlayed(seriesId: Long, played: Boolean, now: Long)

    @Query("$SET_PLAYED WHERE kind = 'EPISODE' AND parentId = :seasonId")
    suspend fun setSeasonPlayed(seasonId: Long, played: Boolean, now: Long)

    @Query("$SET_PLAYED WHERE kind = 'MOVIE' AND collectionId = :collectionId")
    suspend fun setCollectionPlayed(collectionId: Long, played: Boolean, now: Long)

    @Query("UPDATE items SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    /** When it was played, alone: a report of the previous session may land meanwhile. */
    @Query("UPDATE items SET lastPlayedAt = :now WHERE id = :id")
    suspend fun setLastPlayedAt(id: Long, now: Long)

    @Query(
        "UPDATE items SET positionMs = :positionMs, durationMs = :durationMs, played = :played, " +
            "lastPlayedAt = :lastPlayedAt WHERE id = :id"
    )
    suspend fun savePlayback(id: Long, positionMs: Long, durationMs: Long?, played: Boolean, lastPlayedAt: Long)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun deleteSource(id: Long)

    // Items

    /** New rows only: a row inserted again fails, rather than replacing what the user did with it. */
    @Insert
    suspend fun insert(item: ItemEntity): Long

    @Update
    suspend fun update(item: ItemEntity)

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun item(id: Long): ItemEntity?

    @Query("SELECT $ITEM_ROW FROM items WHERE sourceId = :sourceId AND filePath IS NOT NULL")
    suspend fun filesOf(sourceId: Long): List<ItemEntity>

    /** Whether the library holds a row for the file at [filePath] of a source. */
    @Query("SELECT EXISTS(SELECT 1 FROM items WHERE sourceId = :sourceId AND filePath = :filePath)")
    suspend fun hasFile(sourceId: Long, filePath: String): Boolean

    @Query("SELECT * FROM items WHERE kind = 'SERIES' AND matchKey = :key LIMIT 1")
    suspend fun seriesByKey(key: String): ItemEntity?

    @Query("SELECT * FROM items WHERE kind = 'SEASON' AND parentId = :seriesId AND seasonNumber = :season LIMIT 1")
    suspend fun season(seriesId: Long, season: Int): ItemEntity?

    @Query("SELECT * FROM items WHERE parentId = :parentId ORDER BY seasonNumber, episodeNumber, sortTitle")
    suspend fun children(parentId: Long): List<ItemEntity>

    // --- Rows to show (see ITEM_ROW) ---

    @Query("SELECT $ITEM_ROW FROM items WHERE parentId = :parentId ORDER BY seasonNumber, episodeNumber, sortTitle")
    suspend fun childRows(parentId: Long): List<ItemEntity>

    @Query("SELECT $ITEM_ROW FROM items WHERE id IN (:ids)")
    suspend fun rows(ids: List<Long>): List<ItemEntity>

    /** Per show: episodes not watched yet, and seasons. One query for a whole page of shows. */
    @Query(
        "SELECT seriesId, SUM(played = 0) AS unplayed, COUNT(DISTINCT parentId) AS seasons FROM items " +
            "WHERE kind = 'EPISODE' AND seriesId IN (:seriesIds) GROUP BY seriesId"
    )
    suspend fun seriesCounts(seriesIds: List<Long>): List<SeriesCounts>

    /** Shows whose episodes were played most recently. */
    @Query(
        "SELECT seriesId FROM items WHERE kind = 'EPISODE' AND seriesId IS NOT NULL AND lastPlayedAt IS NOT NULL " +
            "GROUP BY seriesId ORDER BY MAX(lastPlayedAt) DESC LIMIT :limit"
    )
    suspend fun recentlyPlayedSeries(limit: Int): List<Long>

    @Query("SELECT $ITEM_ROW FROM items WHERE seriesId = :seriesId AND kind = 'EPISODE' ORDER BY seasonNumber, episodeNumber")
    suspend fun episodesOfSeries(seriesId: Long): List<ItemEntity>

    @Query(
        "SELECT $ITEM_ROW FROM items WHERE kind IN ('MOVIE','EPISODE') AND positionMs > 0 AND played = 0 " +
            "ORDER BY lastPlayedAt DESC LIMIT :limit"
    )
    suspend fun inProgress(limit: Int): List<ItemEntity>

    @Query("SELECT $ITEM_ROW FROM items WHERE kind = :kind ORDER BY addedAt DESC, id DESC LIMIT :limit")
    suspend fun latest(kind: ItemKind, limit: Int): List<ItemEntity>

    @Query("SELECT COUNT(*) FROM items WHERE kind = :kind")
    suspend fun count(kind: ItemKind): Int

    @Query("SELECT genres FROM items WHERE kind = :kind AND genres != ''")
    suspend fun genresOf(kind: ItemKind): List<String>

    @Query("SELECT DISTINCT year FROM items WHERE kind = :kind AND year IS NOT NULL")
    suspend fun yearsOf(kind: ItemKind): List<Int>

    @Query("SELECT DISTINCT badges FROM items WHERE kind = :kind AND badges != ''")
    suspend fun badgesOf(kind: ItemKind): List<String>

    /** Films and shows found in a source (not seasons or episodes). */
    @Query("SELECT COUNT(*) FROM items WHERE sourceId = :sourceId AND kind IN ('MOVIE','SERIES')")
    fun observeTitleCount(sourceId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM items WHERE kind IN ('MOVIE','SERIES')")
    fun observeAllTitleCount(): Flow<Int>

    /** Posters of the latest titles of a source, the user's choice first. */
    @Query(
        "SELECT COALESCE(NULLIF(userPosterPath, ''), posterPath) FROM items " +
            "WHERE sourceId = :sourceId AND kind IN ('MOVIE','SERIES') " +
            "AND COALESCE(NULLIF(userPosterPath, ''), posterPath) IS NOT NULL ORDER BY addedAt DESC LIMIT :limit"
    )
    fun observePosters(sourceId: Long, limit: Int): Flow<List<String>>

    /** Files of a source in Ultra HD (their badges carry the definition token). */
    @Query("SELECT COUNT(*) FROM items WHERE sourceId = :sourceId AND filePath IS NOT NULL AND ('|' || badges || '|') LIKE '%|' || :token || '|%'")
    fun observeDefinitionCount(sourceId: Long, token: String): Flow<Int>

    @RawQuery
    suspend fun query(query: SupportSQLiteQuery): List<ItemEntity>

    @RawQuery
    suspend fun countQuery(query: SupportSQLiteQuery): Int

    @Query(
        "SELECT $ITEM_ROW FROM items WHERE kind = :kind AND id != :excludeId AND genres LIKE '%' || :genre || '%' " +
            "ORDER BY rating DESC LIMIT :limit"
    )
    suspend fun byGenre(kind: ItemKind, genre: String, excludeId: Long, limit: Int): List<ItemEntity>

    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun deleteItemBatch(ids: List<Long>)

    /** Any number of items, [SQL_IN_LIMIT] per statement, all or none. */
    @Transaction
    suspend fun deleteItems(ids: List<Long>) = ids.chunked(SQL_IN_LIMIT).forEach { deleteItemBatch(it) }

    /**
     * Moves a row to the file that replaced its own (see [MissingFiles]): what the user did with the
     * title stays. A file of another size is read again; one of the same size was only renamed.
     */
    @Query(
        "UPDATE items SET filePath = :filePath, fileSize = :fileSize, " +
            "mediaInfo = CASE WHEN fileSize = :fileSize THEN mediaInfo ELSE NULL END, missingSince = NULL WHERE id = :id"
    )
    suspend fun relink(id: Long, filePath: String, fileSize: Long)

    /**
     * A film's parts after the first ([ItemEntity.parts], as [PipeList] writes them), as its folder lists
     * them now. A film of one file that becomes one of several is asked for again: it was known by its
     * first part's name ("Film CD1"), not the film's.
     */
    @Query(
        "UPDATE items SET parts = :parts, " +
            "matchedAt = CASE WHEN parts = '' AND :parts != '' THEN NULL ELSE matchedAt END WHERE id = :id"
    )
    suspend fun setParts(id: Long, parts: String)

    @Query("UPDATE items SET missingSince = :since WHERE id IN (:ids)")
    suspend fun setMissingSinceBatch(ids: List<Long>, since: Long?)

    /**
     * [since]: when a scan first missed their files (see [MissingFiles]); null once they are back.
     * Any number of items, [SQL_IN_LIMIT] per statement, all or none.
     */
    @Transaction
    suspend fun setMissingSince(ids: List<Long>, since: Long?) =
        ids.chunked(SQL_IN_LIMIT).forEach { setMissingSinceBatch(it, since) }

    /**
     * What a scan made of the rows of files its folder no longer lists (see [MissingFiles]), in one
     * transaction: a rename of a whole library is a few thousand rows.
     */
    @Transaction
    @Suppress("LongParameterList") // One per outcome of [MissingFiles.Outcome].
    suspend fun settleMissing(
        relinked: List<Pair<Long, RemoteFile>>,
        back: List<Long>,
        held: List<Long>,
        removed: List<Long>,
        parts: List<Pair<Long, List<String>>>,
        now: Long,
    ) {
        relinked.forEach { (id, file) -> relink(id, file.relativePath, file.size) }
        parts.forEach { (id, files) -> setParts(id, PipeList.toText(files)) }
        setMissingSince(back, null)
        setMissingSince(held, now)
        deleteItems(removed)
    }

    /** Seasons without episodes, then series without seasons. */
    @Query("DELETE FROM items WHERE kind = 'SEASON' AND id NOT IN (SELECT parentId FROM items WHERE kind = 'EPISODE' AND parentId IS NOT NULL)")
    suspend fun deleteEmptySeasons()

    @Query("DELETE FROM items WHERE kind = 'SERIES' AND id NOT IN (SELECT parentId FROM items WHERE kind = 'SEASON' AND parentId IS NOT NULL)")
    suspend fun deleteEmptySeries()

    /**
     * Films and episodes of folders that are gone. Shows and seasons follow through [deleteEmptySeasons]
     * and [deleteEmptySeries]: one show can gather episodes from several folders.
     */
    @Query("DELETE FROM items WHERE kind IN ('MOVIE', 'EPISODE') AND sourceId NOT IN (SELECT id FROM sources)")
    suspend fun deleteOrphanItems()

    /**
     * Shows and seasons first found in a folder that is gone, which still hold episodes of another,
     * move to that one: its card counts them, and they stay new as long as its titles do. Once
     * [deleteEmptySeasons] and [deleteEmptySeries] ran, each still holds an episode.
     */
    @Query(
        "UPDATE items SET sourceId = (SELECT e.sourceId FROM items e WHERE e.kind = 'EPISODE' " +
            "AND (e.seriesId = items.id OR e.parentId = items.id) ORDER BY e.id LIMIT 1) " +
            "WHERE kind IN ('SERIES', 'SEASON') AND sourceId NOT IN (SELECT id FROM sources) " +
            "AND EXISTS (SELECT 1 FROM items e WHERE e.kind = 'EPISODE' AND (e.seriesId = items.id OR e.parentId = items.id))"
    )
    suspend fun moveOrphanShows()

    /** Removes a folder and what was found in it, at once. */
    @Transaction
    suspend fun removeSource(id: Long) {
        deleteSource(id)
        deleteOrphanItems()
        deleteEmptySeasons()
        deleteEmptySeries()
        moveOrphanShows()
        deleteEmptyCollections()
    }

    /** [writes], all or none: an identification, which changes a title, its seasons and its episodes. */
    @Transaction
    suspend fun inTransaction(writes: suspend () -> Unit) = writes()

    /**
     * Saves what a scan found without touching what the user owns: artwork and match choices,
     * favourite, watched state and resume point may have changed while the scan was working.
     */
    @Transaction
    suspend fun saveScanned(item: ItemEntity) {
        val current = item(item.id) ?: return
        update(
            item.copy(
                userTmdbId = current.userTmdbId,
                userPosterPath = current.userPosterPath,
                userBackdropPath = current.userBackdropPath,
                userLogoPath = current.userLogoPath,
                positionMs = current.positionMs,
                durationMs = current.durationMs,
                played = current.played,
                favorite = current.favorite,
                lastPlayedAt = current.lastPlayedAt,
            )
        )
    }

    @Query("UPDATE items SET userPosterPath = :path WHERE id = :id")
    suspend fun setUserPoster(id: Long, path: String?)

    @Query("UPDATE items SET userBackdropPath = :path WHERE id = :id")
    suspend fun setUserBackdrop(id: Long, path: String?)

    @Query("UPDATE items SET userLogoPath = :path WHERE id = :id")
    suspend fun setUserLogo(id: Long, path: String?)

    @Query("UPDATE items SET userTmdbId = :tmdbId WHERE id = :id")
    suspend fun setUserTmdbId(id: Long, tmdbId: Int?)

    /** After a new match: artwork chosen for the previous title no longer applies. */
    @Query("UPDATE items SET userPosterPath = NULL, userBackdropPath = NULL, userLogoPath = NULL WHERE id = :id")
    suspend fun clearUserArtwork(id: Long)

    /** Every cached local image the library still points to. */
    @Query(
        "SELECT posterPath FROM items WHERE posterPath LIKE 'file:%' " +
            "UNION SELECT backdropPath FROM items WHERE backdropPath LIKE 'file:%' " +
            "UNION SELECT logoPath FROM items WHERE logoPath LIKE 'file:%' " +
            "UNION SELECT stillPath FROM items WHERE stillPath LIKE 'file:%' " +
            "UNION SELECT userPosterPath FROM items WHERE userPosterPath LIKE 'file:%' " +
            "UNION SELECT userBackdropPath FROM items WHERE userBackdropPath LIKE 'file:%' " +
            "UNION SELECT userLogoPath FROM items WHERE userLogoPath LIKE 'file:%' " +
            "UNION SELECT profilePath FROM `cast` WHERE profilePath LIKE 'file:%'"
    )
    suspend fun cachedArtworkPaths(): List<String>

    /** Cast rows of items that no longer exist. */
    @Query("DELETE FROM `cast` WHERE itemId NOT IN (SELECT id FROM items)")
    suspend fun deleteOrphanCast(): Int

    // Collections
    @Query("SELECT * FROM collections WHERE matchKey = :key LIMIT 1")
    suspend fun collectionByKey(key: String): CollectionEntity?

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun collection(id: Long): CollectionEntity?

    @Insert
    suspend fun insertCollection(collection: CollectionEntity): Long

    @Update
    suspend fun updateCollection(collection: CollectionEntity)

    /** Films of a collection, in the order they came out. */
    @Query("SELECT $ITEM_ROW FROM items WHERE collectionId = :collectionId AND kind = 'MOVIE' ORDER BY year IS NULL, year, sortTitle")
    suspend fun collectionFilms(collectionId: Long): List<ItemEntity>

    /** Collections holding at least [minFilms] films of the library. */
    @Query(
        "SELECT COUNT(*) FROM (SELECT collectionId FROM items WHERE kind = 'MOVIE' AND collectionId IS NOT NULL " +
            "GROUP BY collectionId HAVING COUNT(*) >= :minFilms)"
    )
    suspend fun collectionCount(minFilms: Int): Int

    @RawQuery
    suspend fun collectionQuery(query: SupportSQLiteQuery): List<CollectionEntity>

    @Query("DELETE FROM collections WHERE id NOT IN (SELECT collectionId FROM items WHERE collectionId IS NOT NULL)")
    suspend fun deleteEmptyCollections()

    // Cast
    @Query("SELECT * FROM `cast` WHERE itemId = :itemId ORDER BY ord")
    suspend fun cast(itemId: Long): List<CastEntity>

    @Query("DELETE FROM `cast` WHERE itemId = :itemId")
    suspend fun deleteCast(itemId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCast(cast: List<CastEntity>)

    @Transaction
    suspend fun replaceCast(itemId: Long, cast: List<CastEntity>) {
        deleteCast(itemId)
        insertCast(cast)
    }
}
