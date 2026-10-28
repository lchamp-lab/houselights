package app.houselights.providers.local

import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A film or show as home's first scan shows it ([FirstTitleDao.observeFirstTitles]): its poster's path, the user's first. */
data class FirstTitleRow(val id: Long, val title: String, val year: Int?, val poster: String?)

/** The films and shows home's first scan shows: a film's copies once ([ItemEntity.versionOf]). */
private const val FIRST_TITLES = "kind IN ('MOVIE','SERIES') AND versionOf IS NULL"

/** The titles home's first scan shows as they are found ([LocalLibrary.firstTitles]; [LibraryDao] extends it). */
interface FirstTitleDao {
    /**
     * The [limit] films and shows first found, then the latest, first found first: a film's copies once, titles without
     * a poster too. Found in the order their rows were made (`id`), not by `addedAt`, which a title given back from a
     * kept state takes from before and a show moves on with each new episode.
     */
    @Query(
        "SELECT id, title, year, COALESCE(NULLIF(userPosterPath, ''), posterPath) AS poster FROM items WHERE id IN (" +
            "SELECT id FROM (SELECT id FROM items WHERE $FIRST_TITLES ORDER BY id LIMIT :limit) " +
            "UNION SELECT id FROM (SELECT id FROM items WHERE $FIRST_TITLES ORDER BY id DESC LIMIT 1)" +
            ") ORDER BY id",
    )
    fun observeFirstTitles(limit: Int): Flow<List<FirstTitleRow>>

    /** How many films and shows the library holds, a film's copies once: the titles home's first scan has found. */
    @Query("SELECT COUNT(*) FROM items WHERE $FIRST_TITLES")
    fun observeFirstTitleCount(): Flow<Int>
}
