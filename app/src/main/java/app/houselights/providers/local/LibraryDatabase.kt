package app.houselights.providers.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.ProvidedTypeConverter
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import app.houselights.core.Secrets
import kotlinx.coroutines.flow.Flow

enum class SourceKind { MOVIES, SHOWS }

enum class Protocol { SMB, NFS }

/** A password as the app uses it. The database keeps it sealed (see [SecretConverter]); it never prints. */
data class Secret(val value: String) {
    override fun toString() = "Secret(***)"
}

/** Seals [Secret] columns on the way to the database, opens them on the way back. */
@ProvidedTypeConverter
class SecretConverter(private val secrets: Secrets) {
    @TypeConverter
    fun seal(secret: Secret): String = secrets.seal(secret.value)

    /** A secret that can no longer be opened reads as empty: the folder then asks for its password. */
    @TypeConverter
    fun open(stored: String): Secret = Secret(secrets.open(stored) ?: "")
}

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
    val password: Secret,
    val domain: String = "",
    val lastScanAt: Long = 0,
)

enum class ItemKind { MOVIE, SERIES, SEASON, EPISODE }

@Entity(
    tableName = "items",
    indices = [
        Index("parentId"), Index("seriesId"), Index(value = ["sourceId", "filePath"]), Index("matchKey"), Index("collectionId"),
        Index(value = ["kind", "sortTitle"]), Index(value = ["kind", "addedAt"]), Index(value = ["kind", "lastPlayedAt"]),
    ],
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
    /** [app.houselights.core.MediaInfo] as JSON; null until the file has been read. */
    val mediaInfo: String? = null,
    /** The NFO file next to the video (or the show), and its raw content. */
    val nfoPath: String? = null,
    val nfo: String? = null,
    /** Choices made by the user; scans never override them. */
    val userTmdbId: Int? = null,
    val userPosterPath: String? = null,
    val userBackdropPath: String? = null,
    val userLogoPath: String? = null,
    /** The collection a film belongs to ([CollectionEntity]). */
    val collectionId: Long? = null,
    val addedAt: Long,
    /** Last TMDB lookup (successful or not); null = never tried. */
    val matchedAt: Long? = null,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val played: Boolean = false,
    val favorite: Boolean = false,
    val lastPlayedAt: Long? = null,
)

/**
 * A collection of films (a saga): from the NFO's set or TMDB's "belongs to collection". Its films
 * point to it ([ItemEntity.collectionId]); it is removed once none is left.
 */
@Entity(tableName = "collections", indices = [Index(value = ["matchKey"], unique = true)])
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "tmdb:<id>" when TMDB knows the collection, else "name:<normalised name>". */
    val matchKey: String,
    val tmdbId: Int? = null,
    val name: String,
    val sortTitle: String,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    /** Films of the collection released so far, owned or not; null until TMDB was asked. */
    val partCount: Int? = null,
    /** Last time TMDB was asked for the collection; null = never. */
    val fetchedAt: Long? = null,
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

    /** Forget TMDB details of a show's seasons and episodes, so the next scan fetches them again. */
    @Query("UPDATE items SET matchedAt = NULL WHERE seriesId = :seriesId")
    suspend fun unmatchChildren(seriesId: Long)

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
    @Query("SELECT * FROM items WHERE collectionId = :collectionId AND kind = 'MOVIE' ORDER BY year IS NULL, year, sortTitle")
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

@Database(entities = [SourceEntity::class, ItemEntity::class, CastEntity::class, CollectionEntity::class], version = 7)
@TypeConverters(SecretConverter::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN protocol TEXT NOT NULL DEFAULT 'SMB'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN mediaInfo TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN nfoPath TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN nfo TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN userTmdbId INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN userPosterPath TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN userBackdropPath TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN userLogoPath TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`matchKey` TEXT NOT NULL, `tmdbId` INTEGER, `name` TEXT NOT NULL, `sortTitle` TEXT NOT NULL, " +
                        "`overview` TEXT, `posterPath` TEXT, `backdropPath` TEXT, `partCount` INTEGER, `fetchedAt` INTEGER)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_collections_matchKey` ON `collections` (`matchKey`)")
                db.execSQL("ALTER TABLE items ADD COLUMN collectionId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_collectionId` ON `items` (`collectionId`)")
                // Films already in the library learn their collection at the next scan.
                db.execSQL("UPDATE items SET matchedAt = NULL WHERE kind = 'MOVIE'")
            }
        }

        /** Seals the passwords stored in plain text so far, and indexes what lists sort and filter on. */
        internal fun migration6To7(secrets: Secrets) = object : Migration(startVersion = 6, endVersion = 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.query("SELECT id, password FROM sources").use { rows ->
                    while (rows.moveToNext()) {
                        val password = rows.getString(1)
                        if (password.isEmpty() || secrets.isSealed(password)) continue
                        db.execSQL("UPDATE sources SET password = ? WHERE id = ?", arrayOf<Any>(secrets.seal(password), rows.getLong(0)))
                    }
                }
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_kind_sortTitle` ON `items` (`kind`, `sortTitle`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_kind_addedAt` ON `items` (`kind`, `addedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_items_kind_lastPlayedAt` ON `items` (`kind`, `lastPlayedAt`)")
            }
        }

        internal fun migrations(secrets: Secrets) =
            arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, migration6To7(secrets))

        fun create(context: Context, secrets: Secrets): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
                .addTypeConverter(SecretConverter(secrets))
                .addMigrations(*migrations(secrets))
                .build()
    }
}
