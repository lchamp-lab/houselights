package app.houselights.providers.local

import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A source password as stored: sealed, or plain text not yet sealed ([LibraryDatabase.sealPlainPasswords]). */
data class StoredPassword(val id: Long, val password: String) {
    override fun toString() = "StoredPassword($id, ***)"
}

/** The folders the library reads and their credentials ([LibraryDao] extends it). */
@Suppress("TooManyFunctions") // A list of queries, as LibraryDao is.
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY kind, name")
    fun observeSources(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY kind, name")
    suspend fun sources(): List<SourceEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM sources)")
    fun observeAnySource(): Flow<Boolean>

    @Query("SELECT id, firstScanAt FROM sources")
    suspend fun firstScans(): List<SourceFirstScan>

    @Insert
    suspend fun insertSource(source: SourceEntity): Long

    @Update
    suspend fun updateSource(source: SourceEntity)

    /**
     * Updates address, credentials and NFS identity in place, keeping id, kind and scan dates, so titles keep their
     * user state.
     */
    @Query(
        "UPDATE sources SET name = :name, protocol = :protocol, host = :host, share = :share, path = :path, " +
            "username = :username, password = :password, uid = :uid, gid = :gid WHERE id = :id",
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
        uid: Int?,
        gid: Int?,
    )

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun source(id: Long): SourceEntity?

    /** Raw stored passwords, sealed or not, read without unsealing ([LibraryDatabase.sealPlainPasswords]). */
    @Query("SELECT id, password FROM sources WHERE password != ''")
    suspend fun storedPasswords(): List<StoredPassword>

    /** Replaces the [plain] password with [sealed], unless the source was saved since it was read. */
    @Query("UPDATE sources SET password = :sealed WHERE id = :id AND password = :plain")
    suspend fun sealPassword(id: Long, plain: String, sealed: String)

    /** Writes only the scan columns: the row may have changed meanwhile, and rewriting it would reseal the password. */
    @Query("UPDATE sources SET lastScanAt = :at, firstScanAt = CASE WHEN firstScanAt = 0 THEN :at ELSE firstScanAt END WHERE id = :id")
    suspend fun markScanned(id: Long, at: Long)

    /** Asks every folder to be read again whole in [language] ([SourceEntity.refreshIn]). */
    @Query("UPDATE sources SET refreshIn = :language")
    suspend fun refreshAllIn(language: String)

    /** Clears [id]'s request once read whole in [language], unless another language was asked meanwhile. */
    @Query("UPDATE sources SET refreshIn = NULL WHERE id = :id AND refreshIn = :language")
    suspend fun refreshedIn(id: Long, language: String)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun deleteSource(id: Long)

    /** Whether the user kept the titles held in folder [id] ([SourceEntity.heldKept]). */
    @Query("UPDATE sources SET heldKept = :kept WHERE id = :id")
    suspend fun setHeldKept(id: Long, kept: Boolean)
}
