package app.houselights.providers.local

import androidx.room.Query

/** The titles to identify TMDB is asked about again ([Reidentify]; [LibraryDao] extends it). */
interface RecheckDao {
    /**
     * Films or shows ([kind]) to identify that TMDB is to be asked about again ([Reidentify]), the longest waiting
     * first, at most [limit]: those come after [newSince] last asked by [newDue], the others by [due]; never a title
     * matched by hand.
     */
    @Query(
        "SELECT $CARD_ROW FROM items WHERE $TO_IDENTIFY AND kind = :kind AND userTmdbId IS NULL AND matchedAt IS NOT NULL " +
            "AND matchedAt <= (CASE WHEN addedAt > :newSince THEN :newDue ELSE :due END) ORDER BY matchedAt LIMIT :limit",
    )
    suspend fun recheckDue(kind: ItemKind, newSince: Long, newDue: Long, due: Long, limit: Int): List<ItemEntity>

    /**
     * Every film and show to identify that TMDB was asked about made due at once, as if asked long ago ([Reidentify.newRules]),
     * those held missing too, due once back; never a title matched by hand or left as it is. Returns how many.
     */
    @Query(
        "UPDATE items SET matchedAt = 0 WHERE kind IN ('MOVIE', 'SERIES') AND matchState IN ('NOT_FOUND', 'UNSURE') " +
            "AND tmdbId IS NULL AND userTmdbId IS NULL AND userNoMatch = 0 AND matchedAt IS NOT NULL",
    )
    suspend fun recheckAllNow(): Int

    /** A title still to identify once TMDB was asked again ([Reidentify]): what it made of it, and when. */
    @Query("UPDATE items SET matchState = :state, matchedAt = :at WHERE id = :id")
    suspend fun setMatchState(id: Long, state: MatchState, at: Long)
}
