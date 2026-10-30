package app.houselights.providers.local

import androidx.room.Query
import androidx.room.Transaction

/** Row :id plus, for a film, its other copies ([ItemEntity.versionOf]), which share watched and favourite state. */
internal const val COPIES =
    "(id = :id OR id = (SELECT versionOf FROM items WHERE id = :id) " +
        "OR versionOf = (SELECT COALESCE(versionOf, id) FROM items WHERE id = :id AND kind = 'MOVIE'))"

/** The ids of [COPIES], for a query that reads `items` under another name: `i.id IN ($COPY_IDS)`. */
internal const val COPY_IDS = "SELECT id FROM items WHERE $COPIES"

/** A film's copies, as the library and Trakt read them ([LibraryDao] and [TraktDao] extend it). */
interface CopyDao {
    /** Copies of the film with main row [titleId], that row first, without NFO text. */
    @Query("SELECT $ITEM_ROW FROM items WHERE kind = 'MOVIE' AND (id = :titleId OR versionOf = :titleId) ORDER BY id")
    suspend fun copies(titleId: Long): List<ItemEntity>

    /** The copy of film [titleId] that Lire plays ([VersionRank.played]); null for a single copy. */
    @Transaction
    suspend fun playedCopy(titleId: Long): Long? = VersionRank.played(copies(titleId))
}
