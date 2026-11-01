package app.houselights.providers.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * A row's NFO text as last read, up to 1 MB, apart from [ItemEntity] so that writing a row never rewrites it: none for a
 * row without an NFO, or with an empty one; deleted with the row.
 */
@Entity(tableName = "item_nfos")
data class NfoEntity(@PrimaryKey val itemId: Long, val nfo: String)

/** A row's folder, NFO path and NFO text ([NfoDao.nfoOf]). */
data class ItemNfo(val sourceId: Long, val nfoPath: String?, val nfo: String?)

/** A row's NFO text, read for many rows at once ([NfoDao.nfosOf]). */
data class RowNfo(val id: Long, val nfo: String)

/**
 * The rows' NFO texts ([NfoEntity]), read only where a text is shown or parsed, never by a list or a write of the rows;
 * [LibraryDao] extends it and deletes a row's with the row ([LibraryDao.deleteItems]).
 */
@Suppress("TooManyFunctions") // A list of queries, as LibraryDao is.
interface NfoDao {
    /** Row [id]'s folder, NFO path and text, for the NFO's sheet. */
    @Query("SELECT i.sourceId, i.nfoPath, n.nfo FROM items i LEFT JOIN item_nfos n ON n.itemId = i.id WHERE i.id = :id")
    suspend fun nfoOf(id: Long): ItemNfo?

    /** Row [id]'s NFO text ([NfoEntity]); null without one. */
    @Query("SELECT nfo FROM item_nfos WHERE itemId = :id")
    suspend fun nfoText(id: Long): String?

    /** Whether row [id] has an NFO text, without reading it. */
    @Query("SELECT EXISTS(SELECT 1 FROM item_nfos WHERE itemId = :id)")
    suspend fun hasNfo(id: Long): Boolean

    @Query("SELECT itemId AS id, nfo FROM item_nfos WHERE itemId IN (:ids)")
    suspend fun nfosBatch(ids: List<Long>): List<RowNfo>

    /** The NFO text of each row of [ids] that has one, by id: a query per [SQL_IN_LIMIT] rows. */
    suspend fun nfosOf(ids: List<Long>): Map<Long, String> =
        ids.chunked(SQL_IN_LIMIT).flatMap { nfosBatch(it) }.associate { it.id to it.nfo }

    @Query("UPDATE item_nfos SET nfo = :text WHERE itemId = :id AND nfo != :text")
    suspend fun updateNfo(id: Long, text: String)

    @Query("INSERT OR IGNORE INTO item_nfos (itemId, nfo) VALUES (:id, :text)")
    suspend fun insertNfo(id: Long, text: String)

    @Query("DELETE FROM item_nfos WHERE itemId = :id")
    suspend fun forgetNfo(id: Long)

    /** Keeps [text] as row [id]'s NFO ([NfoEntity]), written only when it changed; none when null or empty. */
    @Transaction
    suspend fun setNfo(id: Long, text: String?) {
        if (text.isNullOrEmpty()) return forgetNfo(id)
        updateNfo(id, text)
        insertNfo(id, text)
    }

    @Query("DELETE FROM item_nfos WHERE itemId IN (:ids)")
    suspend fun forgetNfosBatch(ids: List<Long>)

    /** NFOs of rows gone. */
    @Query("DELETE FROM item_nfos WHERE itemId NOT IN (SELECT id FROM items)")
    suspend fun deleteOrphanNfos(): Int
}
