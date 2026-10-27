package app.houselights.providers.local

import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

/**
 * One way TMDB numbers a show's episodes, kept so a scan or the show's page asks TMDB again only when due: the show's
 * own seasons ([AIRED], their episode counts) or one of its episode groups (DVD, absolute…), listed with the show and
 * fetched once an episode needs it ([EpisodeOrders]). Gone with the last show of its TMDB id
 * ([LibraryDao.deleteOrphanEpisodeOrders]).
 */
@Entity(tableName = "episode_orders", primaryKeys = ["showTmdbId", "orderId"])
data class EpisodeOrderEntity(
    val showTmdbId: Int,
    /** [AIRED] for TMDB's own seasons, else the episode group's id. */
    val orderId: String,
    /** 0 for TMDB's own seasons, else the group's type as TMDB numbers it: 1 original air date, 2 absolute, 3 DVD… */
    val type: Int,
    /** The group's name at TMDB ("DVD Order"), as TMDB writes it; "" for TMDB's own seasons. */
    val name: String,
    /** How many episodes it holds, as TMDB lists it. */
    val episodeCount: Int,
    /** Its rank in TMDB's list of the show's groups; 0 for TMDB's own seasons. */
    val rank: Int = 0,
    /**
     * Where its episodes stand ([OrderPlaces]): TMDB's own seasons' counts, or a group's seasons and their episodes; null
     * until fetched, "[]" for a group TMDB would not give for this show (refused until the show's next listing).
     */
    val places: String? = null,
    /** When [places] were fetched; null until then. */
    val fetchedAt: Long? = null,
    /** On the [AIRED] row: when the show's groups were listed; null until they are. */
    val groupsAt: Long? = null,
) {
    companion object {
        /** TMDB's own seasons, as [orderId] and as a choice ([ItemEntity.userEpisodeOrder]). */
        const val AIRED = "aired"

        /** TMDB's type of an absolute order, numbered from the show's first episode. */
        const val ABSOLUTE_TYPE = 2

        /** TMDB's type of a story arcs group, whose seasons keep their names. */
        const val STORY_ARC_TYPE = 5
    }
}

/** The ways TMDB numbers each show's episodes ([EpisodeOrderEntity]); [LibraryDao] extends it. */
interface EpisodeOrderDao {
    /** Show [show]'s orders: its own seasons first, then its groups in TMDB's order. */
    @Query("SELECT * FROM episode_orders WHERE showTmdbId = :show ORDER BY orderId != 'aired', rank, orderId")
    suspend fun episodeOrders(show: Int): List<EpisodeOrderEntity>

    @Upsert
    suspend fun saveEpisodeOrders(rows: List<EpisodeOrderEntity>)

    /** Forgets show [show]'s groups but those [kept], once TMDB lists them no more. */
    @Query("DELETE FROM episode_orders WHERE showTmdbId = :show AND orderId != 'aired' AND orderId NOT IN (:kept)")
    suspend fun forgetEpisodeGroups(show: Int, kept: List<String>)

    /** Show [show]'s groups to fetch again when next needed, and its list to read again, as after a refresh. */
    @Query(
        "UPDATE episode_orders SET places = CASE WHEN orderId = 'aired' THEN places ELSE NULL END, " +
            "fetchedAt = CASE WHEN orderId = 'aired' THEN fetchedAt ELSE NULL END, groupsAt = NULL WHERE showTmdbId = :show",
    )
    suspend fun staleEpisodeGroups(show: Int)

    /**
     * Up to [limit] TMDB ids of shows whose groups were never listed, or last listed before [before] or after [now] (a
     * clock set back), the longest waiting first: a few a pass, so a show matched before its groups were kept learns them.
     */
    @Query(
        "SELECT s.tmdbId FROM items s LEFT JOIN episode_orders o ON o.showTmdbId = s.tmdbId AND o.orderId = 'aired' " +
            "WHERE s.kind = 'SERIES' AND s.tmdbId IS NOT NULL " +
            "AND (o.groupsAt IS NULL OR o.groupsAt <= :before OR o.groupsAt > :now) " +
            "GROUP BY s.tmdbId ORDER BY MIN(IFNULL(o.groupsAt, 0)), s.tmdbId LIMIT :limit",
    )
    suspend fun groupsDue(before: Long, now: Long, limit: Int): List<Int>

    /**
     * Up to [limit] shows with episodes waiting for TMDB to list them ([ItemEntity.absoluteNumber] without a TMDB
     * episode), whose TMDB counts were read before [before] or after [now] (a clock set back), the longest waiting first.
     */
    @Query(
        "SELECT s.id FROM items s LEFT JOIN episode_orders o ON o.showTmdbId = s.tmdbId AND o.orderId = 'aired' " +
            "WHERE s.kind = 'SERIES' AND s.tmdbId IS NOT NULL " +
            "AND (o.fetchedAt IS NULL OR o.fetchedAt <= :before OR o.fetchedAt > :now) " +
            "AND EXISTS (SELECT 1 FROM items e WHERE e.kind = 'EPISODE' AND e.seriesId = s.id " +
            "AND e.absoluteNumber IS NOT NULL AND e.tmdbEpisode IS NULL) " +
            "ORDER BY IFNULL(o.fetchedAt, 0), s.id LIMIT :limit",
    )
    suspend fun waitingShows(before: Long, now: Long, limit: Int): List<Long>

    /** The orders of TMDB ids no show has any more. */
    @Query(
        "DELETE FROM episode_orders WHERE showTmdbId NOT IN " +
            "(SELECT tmdbId FROM items WHERE kind = 'SERIES' AND tmdbId IS NOT NULL)",
    )
    suspend fun deleteOrphanEpisodeOrders()
}
