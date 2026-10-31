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

/** Copy [copy] of a film whose main row is [title], which chose to play [choice] ([CopyHeirDao.handTitlesOf]). */
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

    /** The copies of the main rows among [ids] ([ItemEntity.versionOf]), each with its main row's choice of copy. */
    @Query(
        "SELECT t.id AS title, t.versionChoice AS choice, c.id AS copy FROM items t JOIN items c ON c.versionOf = t.id " +
            "WHERE t.id IN (:ids)",
    )
    suspend fun copiesOfTitlesAmong(ids: List<Long>): List<TitleCopy>

    /**
     * Gives copy [heir] main row [title]'s chosen artwork where it has none, and [choice] as the copy to play
     * ([ItemEntity.versionChoice]).
     */
    @Query(
        "UPDATE items SET userPosterPath = COALESCE(userPosterPath, (SELECT t.userPosterPath FROM items t WHERE t.id = :title)), " +
            "userBackdropPath = COALESCE(userBackdropPath, (SELECT t.userBackdropPath FROM items t WHERE t.id = :title)), " +
            "userLogoPath = COALESCE(userLogoPath, (SELECT t.userLogoPath FROM items t WHERE t.id = :title)), " +
            "versionChoice = :choice WHERE id = :heir",
    )
    suspend fun handTitle(title: Long, heir: Long, choice: Long?)

    /**
     * Hands what was chosen on the films whose main rows are among [leaving], about to be deleted, to the copy that
     * stays to hold each ([groupVersions] makes it the main row, the oldest): chosen artwork where it has none, and the
     * copy to play unless that one leaves too, the film then playing the copy Lire picks ([VersionRank.played]). Watched
     * and favourite are every copy's already ([COPIES]). Runs in the deleting transaction.
     */
    suspend fun handTitlesOf(leaving: List<Long>) {
        if (leaving.isEmpty()) return
        val gone = leaving.toHashSet()
        leaving.chunked(SQL_IN_LIMIT).flatMap { copiesOfTitlesAmong(it) }.filterNot { it.copy in gone }
            .groupBy { it.title }.values.map { copies -> copies.minBy { it.copy } }
            .forEach { heir -> handTitle(heir.title, heir.copy, heir.choice?.takeUnless { it in gone }) }
    }
}
