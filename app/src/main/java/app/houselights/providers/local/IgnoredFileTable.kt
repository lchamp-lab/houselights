package app.houselights.providers.local

import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Why a video of a shows folder gets no row ([FilenameParser.refusal]): no episode number, or numbers but no show. */
enum class IgnoredReason { NO_NUMBER, NO_SHOW }

/**
 * A video of shows folder [sourceId] that its name places nowhere, at [filePath] in it, and why: listed, not dropped
 * in silence, as the last pass that read the folder found it ([LibraryScanner]); forgotten with the folder.
 */
@Entity(tableName = "ignored_files", primaryKeys = ["sourceId", "filePath"])
data class IgnoredFileEntity(val sourceId: Long, val filePath: String, val reason: IgnoredReason)

/** An ignored file and the name of its folder, as Réglages lists it. */
data class IgnoredFileRow(val sourceId: Long, val folder: String, val filePath: String, val reason: IgnoredReason)

/**
 * The videos the shows folders hold that the library leaves out ([IgnoredFileEntity]); [LibraryDao] extends it and
 * replaces a folder's list in one transaction ([LibraryDao.replaceIgnored]).
 */
interface IgnoredFileDao {
    @Query("SELECT * FROM ignored_files WHERE sourceId = :sourceId ORDER BY filePath")
    suspend fun ignoredOf(sourceId: Long): List<IgnoredFileEntity>

    /** Every folder's, by folder name then path. */
    @Query(
        "SELECT f.sourceId, s.name AS folder, f.filePath, f.reason FROM ignored_files f JOIN sources s ON s.id = f.sourceId " +
            "ORDER BY s.name, s.id, f.filePath",
    )
    fun observeIgnored(): Flow<List<IgnoredFileRow>>

    @Query("DELETE FROM ignored_files WHERE sourceId = :sourceId")
    suspend fun forgetIgnoredOf(sourceId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIgnored(rows: List<IgnoredFileEntity>)

    /** The lists of folders removed. */
    @Query("DELETE FROM ignored_files WHERE sourceId NOT IN (SELECT id FROM sources)")
    suspend fun deleteOrphanIgnored()
}
