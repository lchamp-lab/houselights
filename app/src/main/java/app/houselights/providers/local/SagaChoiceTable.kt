package app.houselights.providers.local

import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

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

/** A saga's pictures as the user chose them, by its key ([SagaChoiceEntity]; [LibraryDao] extends it). */
interface SagaChoiceDao {
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
