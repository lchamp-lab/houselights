package app.houselights.providers.local

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

data class SearchRef(val ref: String)

/** The search index's words ([SearchWords]; [LibraryDao] extends it). */
interface SearchWordDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSearchWords(words: List<SearchWordEntity>)

    @Query("DELETE FROM search_words WHERE kind = :kind AND ref = :ref")
    suspend fun deleteSearchWords(kind: SearchKind, ref: String)

    /** Runs the search query built by [LocalCatalog]: one primary-key range per word and kind. */
    @RawQuery
    suspend fun searchRefs(query: SupportSQLiteQuery): List<SearchRef>
}
