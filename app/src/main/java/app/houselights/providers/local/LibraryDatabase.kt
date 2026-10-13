package app.houselights.providers.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

enum class SourceKind { MOVIES, SHOWS }

enum class Protocol { SMB, NFS }

/** A network folder the user added, holding either movies or TV shows. */
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: SourceKind,
    val protocol: Protocol = Protocol.SMB,
    val host: String,
    /** SMB share name, or NFS export path; stored without leading or trailing slash. */
    val share: String,
    /** Folder inside the share, without leading or trailing slash ("" for the share root). */
    val path: String,
    val username: String,
    val password: String,
    val domain: String = "",
    val lastScanAt: Long = 0,
)

enum class ItemKind { MOVIE, SERIES, SEASON, EPISODE }

@Entity(
    tableName = "items",
    indices = [Index("parentId"), Index("seriesId"), Index(value = ["sourceId", "filePath"]), Index("matchKey")],
)
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: ItemKind,
    val sourceId: Long,
    /** Season for an episode, series for a season. */
    val parentId: Long? = null,
    val seriesId: Long? = null,
    /** Groups files of the same show ("severance"), or "series:season" for seasons. */
    val matchKey: String? = null,
    val tmdbId: Int? = null,
    val title: String,
    val sortTitle: String,
    val originalTitle: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val year: Int? = null,
    val endYear: Int? = null,
    val continuing: Boolean = false,
    val runtimeMin: Int? = null,
    val certification: String? = null,
    val rating: Double? = null,
    val genres: String = "",
    val directors: String = "",
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val logoPath: String? = null,
    val stillPath: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    /** Path relative to the source folder, for movies and episodes. */
    val filePath: String? = null,
    val fileSize: Long? = null,
    val badges: String = "",
    val addedAt: Long,
    /** Last TMDB lookup (successful or not); null = never tried. */
    val matchedAt: Long? = null,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val played: Boolean = false,
    val favorite: Boolean = false,
    val lastPlayedAt: Long? = null,
)

@Entity(tableName = "cast", primaryKeys = ["itemId", "ord"])
data class CastEntity(
    val itemId: Long,
    val ord: Int,
    val name: String,
    val character: String?,
    val profilePath: String?,
)

@Dao
interface LibraryDao {
    // Sources
    @Query("SELECT * FROM sources ORDER BY kind, name")
    fun observeSources(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY kind, name")
    suspend fun sources(): List<SourceEntity>

    @Insert
    suspend fun insertSource(source: SourceEntity): Long

    @Update
    suspend fun updateSource(source: SourceEntity)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun deleteSource(id: Long)

    // Items
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ItemEntity): Long

    @Update
    suspend fun update(item: ItemEntity)

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun item(id: Long): ItemEntity?

    @Query("SELECT * FROM items WHERE sourceId = :sourceId AND filePath IS NOT NULL")
    suspend fun filesOf(sourceId: Long): List<ItemEntity>

    @Query("SELECT * FROM items WHERE kind = 'SERIES' AND matchKey = :key LIMIT 1")
    suspend fun seriesByKey(key: String): ItemEntity?

    @Query("SELECT * FROM items WHERE kind = 'SEASON' AND parentId = :seriesId AND seasonNumber = :season LIMIT 1")
    suspend fun season(seriesId: Long, season: Int): ItemEntity?

    @Query("SELECT * FROM items WHERE parentId = :parentId ORDER BY seasonNumber, episodeNumber, sortTitle")
    suspend fun children(parentId: Long): List<ItemEntity>

    @Query("SELECT * FROM items WHERE seriesId = :seriesId AND kind = 'EPISODE' ORDER BY seasonNumber, episodeNumber")
    suspend fun episodesOfSeries(seriesId: Long): List<ItemEntity>

    @Query(
        "SELECT * FROM items WHERE kind IN ('MOVIE','EPISODE') AND positionMs > 0 AND played = 0 " +
            "ORDER BY lastPlayedAt DESC LIMIT :limit"
    )
    suspend fun inProgress(limit: Int): List<ItemEntity>

    @Query("SELECT * FROM items WHERE kind = 'EPISODE' AND lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT 200")
    suspend fun recentlyPlayedEpisodes(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE kind = :kind ORDER BY addedAt DESC LIMIT :limit")
    suspend fun latest(kind: ItemKind, limit: Int): List<ItemEntity>

    @Query("SELECT COUNT(*) FROM items WHERE kind = :kind")
    suspend fun count(kind: ItemKind): Int

    /** Films and shows found in a source (not seasons or episodes). */
    @Query("SELECT COUNT(*) FROM items WHERE sourceId = :sourceId AND kind IN ('MOVIE','SERIES')")
    fun observeTitleCount(sourceId: Long): Flow<Int>

    @RawQuery
    suspend fun query(query: SupportSQLiteQuery): List<ItemEntity>

    @RawQuery
    suspend fun countQuery(query: SupportSQLiteQuery): Int

    @Query("SELECT * FROM items WHERE kind = :kind AND id != :excludeId AND genres LIKE '%' || :genre || '%' ORDER BY rating DESC LIMIT :limit")
    suspend fun byGenre(kind: ItemKind, genre: String, excludeId: Long, limit: Int): List<ItemEntity>

    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun deleteItems(ids: List<Long>)

    /** Seasons without episodes, then series without seasons. */
    @Query("DELETE FROM items WHERE kind = 'SEASON' AND id NOT IN (SELECT parentId FROM items WHERE kind = 'EPISODE' AND parentId IS NOT NULL)")
    suspend fun deleteEmptySeasons()

    @Query("DELETE FROM items WHERE kind = 'SERIES' AND id NOT IN (SELECT parentId FROM items WHERE kind = 'SEASON' AND parentId IS NOT NULL)")
    suspend fun deleteEmptySeries()

    @Query("DELETE FROM items WHERE sourceId = :sourceId")
    suspend fun deleteItemsOfSource(sourceId: Long)

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

@Database(entities = [SourceEntity::class, ItemEntity::class, CastEntity::class], version = 2, exportSchema = false)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN protocol TEXT NOT NULL DEFAULT 'SMB'")
            }
        }

        fun create(context: Context): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
