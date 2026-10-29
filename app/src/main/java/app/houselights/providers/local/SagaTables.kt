package app.houselights.providers.local

import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * A saga's poster and backdrop as the user chose them (#48), kept by its key ([CollectionEntity.matchKey]), never on its
 * row: the row goes with its last film and comes back under the same key, its choice with it. Scans never write it; a
 * picture back to Automatique is null, and the row goes once both are.
 */
@Entity(tableName = "saga_choices")
data class SagaChoiceEntity(
    @PrimaryKey val matchKey: String,
    val userPosterPath: String? = null,
    val userBackdropPath: String? = null,
)

/**
 * A saga's own tables: its films as TMDB lists them ([SagaPartEntity]) and its pictures as the user chose them, by its key
 * ([SagaChoiceEntity]); [LibraryDao] extends it.
 */
interface SagaDao {
    @Query("DELETE FROM saga_parts WHERE collectionId = :collectionId")
    suspend fun deleteSagaParts(collectionId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSagaParts(parts: List<SagaPartEntity>)

    @Transaction
    suspend fun replaceSagaParts(collectionId: Long, parts: List<SagaPartEntity>) {
        deleteSagaParts(collectionId)
        insertSagaParts(parts)
    }

    /** TMDB parts of a saga in release order, each with the library's main row when present. */
    @Query(
        "SELECT p.*, (SELECT i.id FROM items i WHERE i.kind = 'MOVIE' AND i.tmdbId = p.tmdbId AND i.versionOf IS NULL " +
            "ORDER BY i.id LIMIT 1) AS itemId FROM saga_parts p WHERE p.collectionId = :collectionId ORDER BY p.ord",
    )
    suspend fun sagaParts(collectionId: Long): List<SagaPartRow>

    /**
     * The rows saga [collectionId] holds, its films and their copies: with none, the cleanup deletes it
     * ([LibraryDao.deleteEmptyCollections]).
     */
    @Query("SELECT COUNT(*) FROM items WHERE collectionId = :collectionId")
    suspend fun sagaItemCount(collectionId: Long): Int

    /** The pictures chosen for the sagas of [keys], at most [SQL_IN_LIMIT] keys. */
    @Query("SELECT * FROM saga_choices WHERE matchKey IN (:keys)")
    suspend fun sagaChoices(keys: List<String>): List<SagaChoiceEntity>

    @Query("SELECT * FROM saga_choices WHERE matchKey = :key")
    suspend fun sagaChoice(key: String): SagaChoiceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSagaChoice(choice: SagaChoiceEntity)

    @Query("DELETE FROM saga_choices WHERE matchKey = :key")
    suspend fun deleteSagaChoice(key: String)
}
