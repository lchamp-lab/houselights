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

    /**
     * The copies of the films [titles] ([LibraryDao.titlesInCopies]), their main rows included, without NFO text, read
     * whole as Lire ranks them ([LocalMapper.playedCopiesOf]). [titles] is bound once, so [SQL_IN_LIMIT] of them stay
     * under Android 9's 999 arguments.
     */
    @Query("SELECT $ITEM_ROW FROM items WHERE kind = 'MOVIE' AND COALESCE(versionOf, id) IN (:titles) ORDER BY id")
    suspend fun copiesOf(titles: List<Long>): List<ItemEntity>

    /** The copy of film [titleId] that Lire plays ([VersionRank.played]); null for a single copy. */
    @Transaction
    suspend fun playedCopy(titleId: Long): Long? = VersionRank.played(copies(titleId))
}

/** Main row of the film whose TMDB id row :id is about to take, :tmdbId, as [GROUP_VERSIONS] finds it ([CopyHeirDao.joinTitle]). */
private const val FILM_JOINED = "(SELECT MIN(o.id) FROM items o WHERE o.kind = 'MOVIE' AND o.tmdbId = :tmdbId AND o.id != :id)"

/** Nothing chosen on [FILM_JOINED]: no artwork, no copy to play. */
private const val FILM_UNCHOSEN = "NOT EXISTS (SELECT 1 FROM items f WHERE f.id = $FILM_JOINED AND (f.userPosterPath IS NOT NULL " +
    "OR f.userBackdropPath IS NOT NULL OR f.userLogoPath IS NOT NULL OR f.versionChoice IS NOT NULL))"

/** Row [copy] taking the place of main row [title], which chose to play [choice] ([CopyHeirDao.handTitlesOf]). */
data class TitleCopy(val title: Long, val choice: Long?, val copy: Long)

/** A film's copies grouped under its main row, and the copy taking that row's place as it leaves ([LibraryDao] extends it). */
interface CopyHeirDao {
    /**
     * Groups copies ([ItemEntity.versionOf]) by TMDB id under the oldest row, which keeps favourite, resume point and
     * "recently added" position. Writes only changed rows and returns their count.
     */
    @Query(GROUP_VERSIONS)
    suspend fun groupVersions(): Int

    /** Films of removed sources, whose rows are about to go ([LibraryDao.deleteOrphanItems]). */
    @Query("SELECT id FROM items WHERE kind = 'MOVIE' AND sourceId NOT IN (SELECT id FROM sources)")
    suspend fun orphanFilms(): List<Long>

    /**
     * The other films of the main rows among [ids]'s TMDB ids, each with its main row's choice of copy: main rows and
     * copies by TMDB id, as [groupVersions] groups them, since a row matched anew keeps its versionOf until then.
     */
    @Query(
        "SELECT t.id AS title, t.versionChoice AS choice, c.id AS copy FROM items t " +
            "JOIN items c ON c.kind = 'MOVIE' AND c.tmdbId = t.tmdbId AND c.id != t.id " +
            "WHERE t.id IN (:ids) AND t.kind = 'MOVIE' " +
            "AND t.id = (SELECT MIN(o.id) FROM items o WHERE o.kind = 'MOVIE' AND o.tmdbId = t.tmdbId)",
    )
    suspend fun copiesOfTitlesAmong(ids: List<Long>): List<TitleCopy>

    /**
     * Gives copy [heir] what main row [title] shows of the film: its artwork, chosen or not, over the copy's own from
     * before it was a copy; with [match], its match by hand; the earlier arrival, its place in "recently added"; and
     * [choice] as the copy to play ([ItemEntity.versionChoice]).
     */
    @Query(
        "UPDATE items SET userPosterPath = (SELECT t.userPosterPath FROM items t WHERE t.id = :title), " +
            "userBackdropPath = (SELECT t.userBackdropPath FROM items t WHERE t.id = :title), " +
            "userLogoPath = (SELECT t.userLogoPath FROM items t WHERE t.id = :title), " +
            "userTmdbId = CASE WHEN :match THEN COALESCE((SELECT t.userTmdbId FROM items t WHERE t.id = :title), userTmdbId) " +
            "ELSE userTmdbId END, " +
            "addedAt = MIN(addedAt, (SELECT t.addedAt FROM items t WHERE t.id = :title)), " +
            "versionChoice = :choice WHERE id = :heir",
    )
    suspend fun handTitle(title: Long, heir: Long, choice: Long?, match: Boolean)

    /**
     * Joins row [id], about to be matched as film [tmdbId] it was not, to that film's main row ([FILM_JOINED]): the older
     * of the two, the main row once [groupVersions] runs, shows the film's artwork and copy to play, or, nothing chosen on
     * the film, all [id] brought, kept only by the last row of a film ([MovieIndexer]); and the earlier arrival. The other
     * keeps its own. A lone film's chosen poster so lands on a film that showed only automatic artwork: nothing is lost,
     * and one action undoes it. Never the match by hand: [id]'s is its own file's, NFO's or TMDB's, which a later scan
     * may correct. Runs in the transaction writing the match, before it.
     */
    @Query(
        "UPDATE items SET " +
            "userPosterPath = COALESCE((SELECT f.userPosterPath FROM items f WHERE f.id = $FILM_JOINED), " +
            "(SELECT n.userPosterPath FROM items n WHERE n.id = :id AND $FILM_UNCHOSEN)), " +
            "userBackdropPath = COALESCE((SELECT f.userBackdropPath FROM items f WHERE f.id = $FILM_JOINED), " +
            "(SELECT n.userBackdropPath FROM items n WHERE n.id = :id AND $FILM_UNCHOSEN)), " +
            "userLogoPath = COALESCE((SELECT f.userLogoPath FROM items f WHERE f.id = $FILM_JOINED), " +
            "(SELECT n.userLogoPath FROM items n WHERE n.id = :id AND $FILM_UNCHOSEN)), " +
            "versionChoice = COALESCE((SELECT f.versionChoice FROM items f WHERE f.id = $FILM_JOINED), " +
            "(SELECT n.versionChoice FROM items n WHERE n.id = :id AND $FILM_UNCHOSEN)), " +
            "addedAt = MIN((SELECT f.addedAt FROM items f WHERE f.id = $FILM_JOINED), (SELECT n.addedAt FROM items n WHERE n.id = :id)) " +
            "WHERE id = MIN(:id, $FILM_JOINED) AND (SELECT r.tmdbId FROM items r WHERE r.id = :id) IS NOT :tmdbId",
    )
    suspend fun joinTitle(id: Long, tmdbId: Int?)

    /**
     * Hands the films whose main rows are among [leaving], about to be deleted or matched as another film, to the row
     * that stays to hold each, the oldest of its TMDB id, which [groupVersions] makes the main row ([handTitle]): the
     * copy to play stays unless it leaves too, the film then playing the copy Lire picks ([VersionRank.played]); with
     * [match], a deleted row's match by hand too, the one trace left of what the user said of the film, as kept states
     * keep it ([KeptStates]); a row matched anew keeps its own, which was said of its file. Watched and favourite are
     * every copy's already ([COPIES]). Two main rows handing to one row, the oldest wins. Runs in the transaction that
     * deletes or matches them.
     */
    suspend fun handTitlesOf(leaving: List<Long>, match: Boolean = true) {
        if (leaving.isEmpty()) return
        val gone = leaving.toHashSet()
        leaving.chunked(SQL_IN_LIMIT).flatMap { copiesOfTitlesAmong(it) }.filterNot { it.copy in gone }
            .groupBy { it.title }.values.map { copies -> copies.minBy { it.copy } }
            .sortedByDescending { it.title }
            .forEach { heir -> handTitle(heir.title, heir.copy, heir.choice?.takeUnless { it in gone }, match) }
    }

    /** Row [id]'s TMDB id, as its match stands before a scan or an identification writes it anew. */
    @Query("SELECT tmdbId FROM items WHERE id = :id")
    suspend fun tmdbIdOf(id: Long): Int?

    /** How many other films have row [id]'s TMDB id: none when it is the last row of its film. */
    @Query("SELECT COUNT(*) FROM items WHERE kind = 'MOVIE' AND tmdbId = (SELECT tmdbId FROM items WHERE id = :id) AND id != :id")
    suspend fun othersOfFilm(id: Long): Int
}
