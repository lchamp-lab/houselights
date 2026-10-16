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

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun source(id: Long): SourceEntity?

    // What the user changes is written column by column, so it never overwrites a scan's work.

    @Query(
        "UPDATE items SET played = :played, positionMs = 0, " +
            "lastPlayedAt = CASE WHEN :played THEN :now ELSE lastPlayedAt END WHERE id IN (:ids)"
    )
    suspend fun setPlayed(ids: List<Long>, played: Boolean, now: Long)

    @Query("UPDATE items SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query(
        "UPDATE items SET positionMs = :positionMs, durationMs = :durationMs, played = :played, " +
            "lastPlayedAt = :lastPlayedAt WHERE id = :id"
    )
    suspend fun savePlayback(id: Long, positionMs: Long, durationMs: Long?, played: Boolean, lastPlayedAt: Long)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun deleteSource(id: Long)

    // Items
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ItemEntity): Long

    @Update
    suspend fun update(item: ItemEntity)

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun item(id: Long): ItemEntity?

    @Query("SELECT $ITEM_ROW FROM items WHERE sourceId = :sourceId AND filePath IS NOT NULL")
    suspend fun filesOf(sourceId: Long): List<ItemEntity>

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
    suspend fun deleteItems(ids: List<Long>)

    /** [since]: when a scan first missed their files (see [MissingFiles]); null once they are back. */
    @Query("UPDATE items SET missingSince = :since WHERE id IN (:ids)")
    suspend fun setMissingSince(ids: List<Long>, since: Long?)

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

    /** Removes a folder and what was found in it, at once. */
    @Transaction
    suspend fun removeSource(id: Long) {
        deleteSource(id)
        deleteOrphanItems()
        deleteEmptySeasons()
        deleteEmptySeries()
        deleteEmptyCollections()
    }

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
