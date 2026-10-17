package app.houselights.providers.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import app.houselights.core.Job
import kotlinx.coroutines.flow.Flow

/**
 * Ids bound by one statement at most: Android 9's SQLite (3.22) takes at most 999 values in a
 * statement, and lists of ids can be longer. Robolectric's SQLite takes more: tests count them.
 */
internal const val SQL_IN_LIMIT = 500

private const val SET_PLAYED =
    "UPDATE items SET played = :played, positionMs = 0, lastPlayedAt = CASE WHEN :played THEN :now ELSE lastPlayedAt END"

/**
 * The row :id and, for a film, its other copies ([ItemEntity.versionOf]): what is watched or a favourite of a
 * film is of all its copies. Through the indexes on the id and on versionOf.
 */
private const val COPIES =
    "(id = :id OR id = (SELECT versionOf FROM items WHERE id = :id) " +
        "OR versionOf = (SELECT COALESCE(versionOf, id) FROM items WHERE id = :id AND kind = 'MOVIE'))"

/** The title of each film's copies ([LibraryDao.groupVersions]): the first row of its TMDB id, none for itself. */
private const val TITLE_OF_COPY = "NULLIF((SELECT MIN(o.id) FROM items o WHERE o.kind = 'MOVIE' AND o.tmdbId = items.tmdbId), id)"

/** Gathers the copies of each film, writing only the rows that change (see [LibraryDao.groupVersions]). */
internal const val GROUP_VERSIONS = "UPDATE items SET versionOf = $TITLE_OF_COPY WHERE kind = 'MOVIE' AND versionOf IS NOT $TITLE_OF_COPY"

/** How many titles hold the person of the credit c, a film of several copies once (see [LibraryDao.creditsOf]). */
private const val TITLES_OF_PERSON =
    "(SELECT COUNT(DISTINCT o.itemId) FROM credits o JOIN items oi ON oi.id = o.itemId " +
        "WHERE o.personKey = c.personKey AND oi.versionOf IS NULL)"

/** People with their name, a portrait and how many titles hold them, a film of several copies once. */
private const val PERSON_SUMMARY =
    "SELECT c.personKey, MIN(c.name) AS name, MAX(c.profilePath) AS profilePath, COUNT(DISTINCT c.itemId) AS titles " +
        "FROM credits c JOIN items i ON i.id = c.itemId"
/** A folder's password as the database keeps it: sealed text, or plain text from before sealing. */
data class StoredPassword(val id: Long, val password: String) {
    override fun toString() = "StoredPassword($id, ***)"
}

/** What a folder holds, as its card in the settings says it: films and shows, and files in Ultra HD. */
data class FolderCounts(val sourceId: Long, val titles: Int, val ultraHd: Int)

/** A credit of a title, and how many titles of the library hold its person ([LibraryDao.creditsOf]). */
data class TitleCredit(
    val personKey: String,
    val name: String,
    val job: Job,
    val ord: Int,
    val character: String?,
    val profilePath: String?,
    val titles: Int,
)

/** A credit of a person, on the title [itemId] ([LibraryDao.creditsOfPerson]). */
data class PersonCredit(val itemId: Long, val job: Job, val ord: Int, val name: String, val character: String?, val profilePath: String?)

/** A person: a name, a portrait, and how many titles of the library hold them. */
data class PersonSummary(val personKey: String, val name: String, val profilePath: String?, val titles: Int)

/** A person's TMDB key, by their folded name ([LibraryDao.tmdbKeysOf]). */
data class NamedKey(val foldedName: String, val personKey: String)

/** A film of a saga as TMDB lists it, and the library's title of it, when it holds one. */
data class SagaPartRow(@Embedded val part: SagaPartEntity, val itemId: Long?)

/** When the recent viewing of a show or a saga ([id]) was. */
data class Recent(val id: Long, val at: Long)

/** Where a film of a saga stands for the viewer, in the saga's order ([LibraryDao.startedSagas]). */
data class SagaFilmState(val id: Long, val collectionId: Long, val played: Boolean, val positionMs: Long, val lastPlayedAt: Long?)

/** What the library in numbers counts of a film's or an episode's file, nothing it would decode ([LibraryDao.fileFacts]). */
data class FileFacts(
    val id: Long,
    val kind: ItemKind,
    val versionOf: Long?,
    val seriesId: Long?,
    val badges: List<String>,
    val filePath: String?,
    val parts: List<String>,
    val fileSize: Long?,
    val fileDurationMs: Long?,
    val durationMs: Long?,
    val runtimeMin: Int?,
)

/** How a library's files end, each way once ([LibraryDao.fileEnds]). */
data class FileEnd(val tail: String, val bluRay: Boolean, val dvd: Boolean)

/** What a search word found ([LibraryDao.searchRefs]). */
data class SearchRef(val ref: String)

@Dao
interface LibraryDao {
    // Sources
    @Query("SELECT * FROM sources ORDER BY kind, name")
    fun observeSources(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY kind, name")
    suspend fun sources(): List<SourceEntity>

    @Query("SELECT id, firstScanAt FROM sources")
    suspend fun firstScans(): List<SourceFirstScan>

    @Insert
    suspend fun insertSource(source: SourceEntity): Long

    @Update
    suspend fun updateSource(source: SourceEntity)

    /**
     * A folder's new address or sign-in, column by column: it keeps its id, so its titles and what the
     * user did with them stay, and its kind and scan dates. [name] follows from the path.
     */
    @Query(
        "UPDATE sources SET name = :name, protocol = :protocol, host = :host, share = :share, path = :path, " +
            "username = :username, password = :password WHERE id = :id"
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
    )

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun source(id: Long): SourceEntity?

    /** Folders' passwords as stored, sealed or not: read as text, they are not opened ([LibraryDatabase.sealPlainPasswords]). */
    @Query("SELECT id, password FROM sources WHERE password != ''")
    suspend fun storedPasswords(): List<StoredPassword>

    /** Writes [sealed] in place of the [plain] password read, unless the folder was saved meanwhile. */
    @Query("UPDATE sources SET password = :sealed WHERE id = :id AND password = :plain")
    suspend fun sealPassword(id: Long, plain: String, sealed: String)

    /**
     * The end of a scan, written alone: the rest of the folder's row may have changed during the
     * scan, and writing it again would seal its password again.
     */
    @Query("UPDATE sources SET lastScanAt = :at, firstScanAt = CASE WHEN firstScanAt = 0 THEN :at ELSE firstScanAt END WHERE id = :id")
    suspend fun markScanned(id: Long, at: Long)

    // What the user changes is written column by column, so it never overwrites a scan's work.
    // Watched state goes by parent, never by a list of ids: a long show holds thousands of episodes.

    /** A film's other copies are watched, or not, with it ([COPIES]). */
    @Query("$SET_PLAYED WHERE $COPIES")
    suspend fun setPlayed(id: Long, played: Boolean, now: Long)

    /** The other copies of the film [id] are watched too, their resume points kept: one copy was watched to its end. */
    @Query("UPDATE items SET played = 1 WHERE played = 0 AND $COPIES")
    suspend fun setCopiesPlayed(id: Long)

    @Query("$SET_PLAYED WHERE kind = 'EPISODE' AND seriesId = :seriesId")
    suspend fun setSeriesPlayed(seriesId: Long, played: Boolean, now: Long)

    @Query("$SET_PLAYED WHERE kind = 'EPISODE' AND parentId = :seasonId")
    suspend fun setSeasonPlayed(seasonId: Long, played: Boolean, now: Long)

    @Query("$SET_PLAYED WHERE kind = 'MOVIE' AND collectionId = :collectionId")
    suspend fun setCollectionPlayed(collectionId: Long, played: Boolean, now: Long)

    /** A film's other copies are a favourite, or not, with it ([COPIES]). */
    @Query("UPDATE items SET favorite = :favorite WHERE $COPIES")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    /** The copy of its film "Lire" plays ([ItemEntity.versionChoice]), on the title's row [id]; null: the best. */
    @Query("UPDATE items SET versionChoice = :choice WHERE id = :id")
    suspend fun setVersionChoice(id: Long, choice: Long?)

    /** When it was played, alone: a report of the previous session may land meanwhile. */
    @Query("UPDATE items SET lastPlayedAt = :now WHERE id = :id")
    suspend fun setLastPlayedAt(id: Long, now: Long)

    @Query(
        "UPDATE items SET positionMs = :positionMs, durationMs = :durationMs, played = :played, " +
            "lastPlayedAt = :lastPlayedAt WHERE id = :id"
    )
    suspend fun savePlayback(id: Long, positionMs: Long, durationMs: Long?, played: Boolean, lastPlayedAt: Long)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun deleteSource(id: Long)

    // Items

    /** New rows only: a row inserted again fails, rather than replacing what the user did with it. */
    @Insert
    suspend fun insertRow(item: ItemEntity): Long

    /** A new row ([insertRow]), and a film's or a show's words for searches. */
    @Transaction
    suspend fun insert(item: ItemEntity): Long = insertRow(item).also { indexTitle(item.copy(id = it)) }

    @Update
    suspend fun update(item: ItemEntity)

    /** The words a search finds a film or a show by ([SearchWords]), its title's and its original title's. */
    @Transaction
    suspend fun indexTitle(item: ItemEntity) {
        if (item.kind != ItemKind.MOVIE && item.kind != ItemKind.SERIES) return
        deleteSearchWords(SearchKind.TITLE, item.id.toString())
        insertSearchWords(SearchWords.rows(SearchKind.TITLE, item.id.toString(), item.title, item.originalTitle))
    }

    /** The whole row, its NFO text included: what a scan writes back, and what the player is handed artwork from. */
    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun item(id: Long): ItemEntity?

    /** The row as a title's page shows it ([ITEM_ROW]): all of it but its NFO text, which can weigh up to 1 MB. */
    @Query("SELECT $ITEM_ROW FROM items WHERE id = :id")
    suspend fun itemRow(id: Long): ItemEntity?

    @Query("SELECT sourceId, nfoPath, nfo FROM items WHERE id = :id")
    suspend fun nfoOf(id: Long): ItemNfo?

    /** What a report of the player's progress needs of a title: read every ten seconds while it plays. */
    @Query("SELECT parts, positionMs, durationMs, fileDurationMs, runtimeMin, played FROM items WHERE id = :id")
    suspend fun playState(id: Long): PlayState?

    /** The rows of a source's files, all but their NFO text: what the tests look at of a scan's work. */
    @Query("SELECT $ITEM_ROW FROM items WHERE sourceId = :sourceId AND filePath IS NOT NULL")
    suspend fun filesOf(sourceId: Long): List<ItemEntity>

    /** A source's files, as a scan sorts what to do with them ([KnownFile]). */
    @Query(
        "SELECT id, filePath, parts, fileSize, missingSince, seriesId, tmdbId, userTmdbId, matchedAt, sidecarKey, probeVersion " +
            "FROM items WHERE sourceId = :sourceId AND filePath IS NOT NULL"
    )
    suspend fun knownFiles(sourceId: Long): List<KnownFile>

    /** Whether the library holds a row for the file at [filePath] of a source. */
    @Query("SELECT EXISTS(SELECT 1 FROM items WHERE sourceId = :sourceId AND filePath = :filePath)")
    suspend fun hasFile(sourceId: Long, filePath: String): Boolean

    @Query("SELECT * FROM items WHERE kind = 'SERIES' AND matchKey = :key LIMIT 1")
    suspend fun seriesByKey(key: String): ItemEntity?

    /** Shows of that name keyed with a year ([FilenameParser.showKey]); [nameKey] holds no LIKE wildcard. */
    @Query("SELECT * FROM items WHERE kind = 'SERIES' AND matchKey LIKE :nameKey || '${FilenameParser.YEAR_MARK}%'")
    suspend fun seriesNamed(nameKey: String): List<ItemEntity>

    /**
     * Episodes of a source under the folder [dir], as long as it is not the root (""): paths from "dir/" up to
     * "dir0", "0" coming right after "/", which the index on the source and path reads as a range.
     */
    @Query(
        "SELECT $CARD_ROW FROM items WHERE kind = 'EPISODE' AND sourceId = :sourceId " +
            "AND filePath > :dir || '/' AND filePath < :dir || '0'"
    )
    suspend fun episodesUnder(sourceId: Long, dir: String): List<ItemEntity>

    /** Whether the show [seriesId] holds an episode besides [episodeId]. */
    @Query("SELECT EXISTS(SELECT 1 FROM items WHERE kind = 'EPISODE' AND seriesId = :seriesId AND id != :episodeId)")
    suspend fun hasOtherEpisodes(seriesId: Long, episodeId: Long): Boolean

    @Query("SELECT * FROM items WHERE kind = 'SEASON' AND parentId = :seriesId AND seasonNumber = :season LIMIT 1")
    suspend fun season(seriesId: Long, season: Int): ItemEntity?

    @Query("SELECT * FROM items WHERE parentId = :parentId ORDER BY seasonNumber, episodeNumber, sortTitle")
    suspend fun children(parentId: Long): List<ItemEntity>

    // --- Rows to show (see CARD_ROW) ---

    @Query("SELECT $CARD_ROW FROM items WHERE parentId = :parentId ORDER BY seasonNumber, episodeNumber, sortTitle")
    suspend fun childRows(parentId: Long): List<ItemEntity>

    @Query("SELECT $CARD_ROW FROM items WHERE id IN (:ids)")
    suspend fun rows(ids: List<Long>): List<ItemEntity>

    /** Per show: episodes not watched yet, and seasons. One query for a whole page of shows. */
    @Query(
        "SELECT seriesId, SUM(played = 0) AS unplayed, COUNT(DISTINCT parentId) AS seasons FROM items " +
            "WHERE kind = 'EPISODE' AND seriesId IN (:seriesIds) GROUP BY seriesId"
    )
    suspend fun seriesCounts(seriesIds: List<Long>): List<SeriesCounts>

    /** Shows whose episodes were played most recently, and when. */
    @Query(
        "SELECT seriesId AS id, MAX(lastPlayedAt) AS at FROM items WHERE kind = 'EPISODE' AND seriesId IS NOT NULL " +
            "AND lastPlayedAt IS NOT NULL GROUP BY seriesId ORDER BY MAX(lastPlayedAt) DESC LIMIT :limit"
    )
    suspend fun recentSeries(limit: Int): List<Recent>

    /**
     * The films of the [limit] sagas one of whose films was watched last, saga by saga, each in the order they
     * came out, a film of several copies once: what tells the next film of each.
     */
    @Query(
        "SELECT id, collectionId, played, positionMs, lastPlayedAt FROM items WHERE kind = 'MOVIE' AND versionOf IS NULL " +
            "AND collectionId IN (SELECT collectionId FROM items WHERE kind = 'MOVIE' AND collectionId IS NOT NULL AND played = 1 " +
            "AND lastPlayedAt IS NOT NULL GROUP BY collectionId ORDER BY MAX(lastPlayedAt) DESC LIMIT :limit) " +
            "ORDER BY collectionId, year IS NULL, year, sortTitle, id"
    )
    suspend fun startedSagas(limit: Int): List<SagaFilmState>

    @Query("SELECT $CARD_ROW FROM items WHERE seriesId = :seriesId AND kind = 'EPISODE' ORDER BY seasonNumber, episodeNumber")
    suspend fun episodesOfSeries(seriesId: Long): List<ItemEntity>

    /**
     * Where each episode of the shows [seriesIds] stands, show by show, in their order: one light query for
     * the next episode of many shows, rather than every row of each.
     */
    @Query(
        "SELECT id, seriesId, seasonNumber, episodeNumber, played, positionMs FROM items " +
            "WHERE kind = 'EPISODE' AND seriesId IN (:seriesIds) ORDER BY seriesId, seasonNumber, episodeNumber, id"
    )
    suspend fun episodeStates(seriesIds: List<Long>): List<EpisodeState>

    @Query(
        "SELECT $CARD_ROW FROM items WHERE kind IN ('MOVIE','EPISODE') AND positionMs > 0 AND played = 0 " +
            "ORDER BY lastPlayedAt DESC LIMIT :limit"
    )
    suspend fun inProgress(limit: Int): List<ItemEntity>

    /** Titles last added: a film's other copies are no titles of their own ([ItemEntity.versionOf]). */
    @Query("SELECT $CARD_ROW FROM items WHERE kind = :kind AND versionOf IS NULL ORDER BY addedAt DESC, id DESC LIMIT :limit")
    suspend fun latest(kind: ItemKind, limit: Int): List<ItemEntity>

    @Query("SELECT COUNT(*) FROM items WHERE kind = :kind")
    suspend fun count(kind: ItemKind): Int

    /** Each title's genres, folded beside them by the lower() the genre filter compares ([PipeList.distinctGenres]). */
    @Query("SELECT genres, lower(genres) AS folded FROM items WHERE kind = :kind AND genres != ''")
    suspend fun genresOf(kind: ItemKind): List<GenreList>

    @Query("SELECT DISTINCT year FROM items WHERE kind = :kind AND year IS NOT NULL")
    suspend fun yearsOf(kind: ItemKind): List<Int>

    @Query("SELECT DISTINCT badges FROM items WHERE kind = :kind AND badges != ''")
    suspend fun badgesOf(kind: ItemKind): List<String>

    /** Films and shows found in a source (not seasons or episodes). */
    @Query("SELECT COUNT(*) FROM items WHERE sourceId = :sourceId AND kind IN ('MOVIE','SERIES')")
    fun observeTitleCount(sourceId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM items WHERE kind IN ('MOVIE','SERIES')")
    fun observeAllTitleCount(): Flow<Int>

    /** Posters of the latest titles of a source, the user's choice first. */
    @Query(
        "SELECT COALESCE(NULLIF(userPosterPath, ''), posterPath) FROM items " +
            "WHERE sourceId = :sourceId AND kind IN ('MOVIE','SERIES') " +
            "AND COALESCE(NULLIF(userPosterPath, ''), posterPath) IS NOT NULL ORDER BY addedAt DESC LIMIT :limit"
    )
    fun observePosters(sourceId: Long, limit: Int): Flow<List<String>>

    /** Files of a source in Ultra HD (their badges carry the definition token). */
    @Query("SELECT COUNT(*) FROM items WHERE sourceId = :sourceId AND filePath IS NOT NULL AND ${PipeList.BADGES_MATCH}")
    fun observeDefinitionCount(sourceId: Long, token: String): Flow<Int>

    /**
     * Per source, its films and shows and its files in Ultra HD (their badges carry [token], the
     * definition's): what the two queries above count, for every source in one query, which each
     * write to the items runs again.
     */
    @Query(
        "SELECT sourceId, SUM(kind IN ('MOVIE','SERIES')) AS titles, " +
            "SUM(filePath IS NOT NULL AND ${PipeList.BADGES_MATCH}) AS ultraHd " +
            "FROM items GROUP BY sourceId"
    )
    fun observeFolderCounts(token: String): Flow<List<FolderCounts>>

    @RawQuery
    suspend fun query(query: SupportSQLiteQuery): List<ItemEntity>

    @RawQuery
    suspend fun countQuery(query: SupportSQLiteQuery): Int

    /** Titles of [kind] that have [genre], the best rated first; the id settles ties, so the row stays put. */
    @Query(
        "SELECT $CARD_ROW FROM items WHERE kind = :kind AND id != :excludeId AND versionOf IS NULL AND ${PipeList.GENRES_MATCH} " +
            "ORDER BY rating DESC, id LIMIT :limit"
    )
    suspend fun byGenre(kind: ItemKind, genre: String, excludeId: Long, limit: Int): List<ItemEntity>

    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun deleteItemBatch(ids: List<Long>)

    /** Any number of items, [SQL_IN_LIMIT] per statement, all or none. */
    @Transaction
    suspend fun deleteItems(ids: List<Long>) = ids.chunked(SQL_IN_LIMIT).forEach { deleteItemBatch(it) }

    /**
     * Moves a row to the file that replaced its own (see [MissingFiles]): what the user did with the
     * title stays. A file of another size is read again; one of the same size was only renamed.
     * (SQLite reads the row as it was in every expression: fileSize is the old file's.)
     */
    @Query(
        "UPDATE items SET filePath = :filePath, fileSize = :fileSize, " +
            "mediaInfo = CASE WHEN fileSize = :fileSize THEN mediaInfo ELSE NULL END, " +
            "probeVersion = CASE WHEN fileSize = :fileSize THEN probeVersion ELSE 0 END, " +
            "fileDurationMs = CASE WHEN fileSize = :fileSize THEN fileDurationMs ELSE NULL END, missingSince = NULL WHERE id = :id"
    )
    suspend fun relink(id: Long, filePath: String, fileSize: Long)

    /**
     * A film's parts after the first ([ItemEntity.parts], as [PipeList] writes them), as its folder lists
     * them now. A film of one file that becomes one of several is asked for again: it was known by its
     * first part's name ("Film CD1"), not the film's.
     */
    @Query(
        "UPDATE items SET parts = :parts, " +
            "matchedAt = CASE WHEN parts = '' AND :parts != '' THEN NULL ELSE matchedAt END WHERE id = :id"
    )
    suspend fun setParts(id: Long, parts: String)

    @Query("UPDATE items SET missingSince = :since WHERE id IN (:ids)")
    suspend fun setMissingSinceBatch(ids: List<Long>, since: Long?)

    /**
     * [since]: when a scan first missed their files (see [MissingFiles]); null once they are back.
     * Any number of items, [SQL_IN_LIMIT] per statement, all or none.
     */
    @Transaction
    suspend fun setMissingSince(ids: List<Long>, since: Long?) =
        ids.chunked(SQL_IN_LIMIT).forEach { setMissingSinceBatch(it, since) }

    /**
     * What a scan made of the rows of files its folder no longer lists (see [MissingFiles]), in one
     * transaction: a rename of a whole library is a few thousand rows.
     */
    @Transaction
    @Suppress("LongParameterList") // One per outcome of [MissingFiles.Outcome].
    suspend fun settleMissing(
        relinked: List<Pair<Long, RemoteFile>>,
        back: List<Long>,
        held: List<Long>,
        removed: List<Long>,
        parts: List<Pair<Long, List<String>>>,
        now: Long,
    ) {
        relinked.forEach { (id, file) -> relink(id, file.relativePath, file.size) }
        parts.forEach { (id, files) -> setParts(id, PipeList.toText(files)) }
        setMissingSince(back, null)
        setMissingSince(held, now)
        deleteItems(removed)
    }

    /** Seasons without episodes, then series without seasons. */
    @Query("DELETE FROM items WHERE kind = 'SEASON' AND id NOT IN (SELECT parentId FROM items WHERE kind = 'EPISODE' AND parentId IS NOT NULL)")
    suspend fun deleteEmptySeasons()

    @Query("DELETE FROM items WHERE kind = 'SERIES' AND id NOT IN (SELECT parentId FROM items WHERE kind = 'SEASON' AND parentId IS NOT NULL)")
    suspend fun deleteEmptySeries()

    /**
     * Films and episodes of folders that are gone. Shows and seasons follow through [deleteEmptySeasons]
     * and [deleteEmptySeries]: one show can gather episodes from several folders.
     */
    @Query("DELETE FROM items WHERE kind IN ('MOVIE', 'EPISODE') AND sourceId NOT IN (SELECT id FROM sources)")
    suspend fun deleteOrphanItems()

    /**
     * Shows and seasons first found in a folder that is gone, which still hold episodes of another,
     * move to that one: its card counts them, and they stay new as long as its titles do. Once
     * [deleteEmptySeasons] and [deleteEmptySeries] ran, each still holds an episode.
     */
    @Query(
        "UPDATE items SET sourceId = (SELECT e.sourceId FROM items e WHERE e.kind = 'EPISODE' " +
            "AND (e.seriesId = items.id OR e.parentId = items.id) ORDER BY e.id LIMIT 1) " +
            "WHERE kind IN ('SERIES', 'SEASON') AND sourceId NOT IN (SELECT id FROM sources) " +
            "AND EXISTS (SELECT 1 FROM items e WHERE e.kind = 'EPISODE' AND (e.seriesId = items.id OR e.parentId = items.id))"
    )
    suspend fun moveOrphanShows()

    /** Removes a folder and what was found in it, at once. */
    @Transaction
    suspend fun removeSource(id: Long) {
        deleteSource(id)
        deleteOrphanItems()
        deleteEmptySeasons()
        deleteEmptySeries()
        moveOrphanShows()
        deleteEmptyCollections()
        groupVersions()
    }

    /** [writes], all or none: an identification, which changes a title, its seasons and its episodes. */
    @Transaction
    suspend fun inTransaction(writes: suspend () -> Unit) = writes()

    /**
     * Saves what a scan found without touching what the user owns: artwork and match choices,
     * favourite, watched state, resume point and the copy chosen may have changed while the scan was
     * working; nor what is written apart (the copies of a film, when its credits were). A title's words
     * for searches follow its names.
     */
    @Transaction
    suspend fun saveScanned(item: ItemEntity) {
        val current = item(item.id) ?: return
        update(
            item.copy(
                userTmdbId = current.userTmdbId,
                userPosterPath = current.userPosterPath,
                userBackdropPath = current.userBackdropPath,
                userLogoPath = current.userLogoPath,
                positionMs = current.positionMs,
                durationMs = current.durationMs,
                played = current.played,
                favorite = current.favorite,
                lastPlayedAt = current.lastPlayedAt,
                versionChoice = current.versionChoice,
                versionOf = current.versionOf,
                creditsAt = current.creditsAt,
            )
        )
        if (item.title != current.title || item.originalTitle != current.originalTitle) indexTitle(item)
    }

    /**
     * What the user did with the title [fromId] goes to [toId] where it has none of it: its favourite, the user's
     * match and artwork. For a show about to go, whose episodes another took over ([ShowIndexer]).
     */
    @Transaction
    suspend fun handOver(fromId: Long, toId: Long) {
        val from = item(fromId) ?: return
        val to = item(toId) ?: return
        update(
            to.copy(
                favorite = to.favorite || from.favorite,
                userTmdbId = to.userTmdbId ?: from.userTmdbId,
                userPosterPath = to.userPosterPath ?: from.userPosterPath,
                userBackdropPath = to.userBackdropPath ?: from.userBackdropPath,
                userLogoPath = to.userLogoPath ?: from.userLogoPath,
            )
        )
    }

    @Query("UPDATE items SET userPosterPath = :path WHERE id = :id")
    suspend fun setUserPoster(id: Long, path: String?)

    @Query("UPDATE items SET userBackdropPath = :path WHERE id = :id")
    suspend fun setUserBackdrop(id: Long, path: String?)

    @Query("UPDATE items SET userLogoPath = :path WHERE id = :id")
    suspend fun setUserLogo(id: Long, path: String?)

    @Query("UPDATE items SET userTmdbId = :tmdbId WHERE id = :id")
    suspend fun setUserTmdbId(id: Long, tmdbId: Int?)

    /** After a new match: artwork chosen for the previous title no longer applies. */
    @Query("UPDATE items SET userPosterPath = NULL, userBackdropPath = NULL, userLogoPath = NULL WHERE id = :id")
    suspend fun clearUserArtwork(id: Long)

    /** Every cached local image the library still points to. */
    @Query(
        "SELECT posterPath FROM items WHERE posterPath LIKE 'file:%' " +
            "UNION SELECT backdropPath FROM items WHERE backdropPath LIKE 'file:%' " +
            "UNION SELECT logoPath FROM items WHERE logoPath LIKE 'file:%' " +
            "UNION SELECT stillPath FROM items WHERE stillPath LIKE 'file:%' " +
            "UNION SELECT userPosterPath FROM items WHERE userPosterPath LIKE 'file:%' " +
            "UNION SELECT userBackdropPath FROM items WHERE userBackdropPath LIKE 'file:%' " +
            "UNION SELECT userLogoPath FROM items WHERE userLogoPath LIKE 'file:%' " +
            "UNION SELECT profilePath FROM credits WHERE profilePath LIKE 'file:%'"
    )
    suspend fun cachedArtworkPaths(): List<String>

    /** Credits of items that no longer exist. */
    @Query("DELETE FROM credits WHERE itemId NOT IN (SELECT id FROM items)")
    suspend fun deleteOrphanCredits(): Int

    /** Words of titles, people and sagas that no longer exist, or of a person's key another took over. */
    @Query(
        "DELETE FROM search_words WHERE (kind = 'TITLE' AND ref NOT IN (SELECT CAST(id AS TEXT) FROM items)) " +
            "OR (kind = 'PERSON' AND ref NOT IN (SELECT personKey FROM credits)) " +
            "OR (kind = 'SAGA' AND ref NOT IN (SELECT CAST(id AS TEXT) FROM collections))"
    )
    suspend fun deleteOrphanSearchWords(): Int

    /**
     * Gathers the copies of each film ([ItemEntity.versionOf]): the films of one TMDB id are one title, held by
     * the first of them, the oldest row, whose favourite, resume point and place in "recently added" it keeps. Rows
     * already right are not written. Returns the rows written.
     */
    @Query(GROUP_VERSIONS)
    suspend fun groupVersions(): Int

    /** The copies of the film whose title is the row [titleId], it first: all of each row but its NFO text. */
    @Query("SELECT $ITEM_ROW FROM items WHERE kind = 'MOVIE' AND (id = :titleId OR versionOf = :titleId) ORDER BY id")
    suspend fun copies(titleId: Long): List<ItemEntity>

    /** The best rated titles of [kind], with [minVotes] votes at least where their number is known. */
    @Query(
        "SELECT $CARD_ROW FROM items WHERE kind = :kind AND versionOf IS NULL AND rating IS NOT NULL " +
            "AND (votes IS NULL OR votes >= :minVotes) ORDER BY rating DESC, sortTitle, id LIMIT :limit"
    )
    suspend fun bestRated(kind: ItemKind, minVotes: Int, limit: Int): List<ItemEntity>

    /** Every film's and episode's file, as the library in numbers counts them. */
    @Query(
        "SELECT id, kind, versionOf, seriesId, badges, filePath, parts, fileSize, fileDurationMs, durationMs, runtimeMin " +
            "FROM items WHERE kind IN ('MOVIE', 'EPISODE')"
    )
    suspend fun fileFacts(): List<FileFacts>

    /** How the files of [kind] end, each way once: what tells their kinds of file without reading every path. */
    @Query(
        "SELECT DISTINCT lower(substr(filePath, -5)) AS tail, filePath LIKE '%BDMV/STREAM/%' AS bluRay, " +
            "filePath LIKE '%VIDEO_TS/%' AS dvd FROM items WHERE kind = :kind AND filePath IS NOT NULL"
    )
    suspend fun fileEnds(kind: ItemKind): List<FileEnd>

    // Collections
    @Query("SELECT * FROM collections WHERE matchKey = :key LIMIT 1")
    suspend fun collectionByKey(key: String): CollectionEntity?

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun collection(id: Long): CollectionEntity?

    @Insert
    suspend fun insertCollectionRow(collection: CollectionEntity): Long

    @Update
    suspend fun updateCollectionRow(collection: CollectionEntity)

    /** A new collection ([insertCollectionRow]), and the words a search finds it by. */
    @Transaction
    suspend fun insertCollection(collection: CollectionEntity): Long =
        insertCollectionRow(collection).also { indexSaga(it, collection.name) }

    /** A collection written again ([updateCollectionRow]), and the words a search finds it by. */
    @Transaction
    suspend fun updateCollection(collection: CollectionEntity) {
        updateCollectionRow(collection)
        indexSaga(collection.id, collection.name)
    }

    /** The words a search finds the saga [id] by, its [name]'s. */
    @Transaction
    suspend fun indexSaga(id: Long, name: String) {
        deleteSearchWords(SearchKind.SAGA, id.toString())
        insertSearchWords(SearchWords.rows(SearchKind.SAGA, id.toString(), name))
    }

    /** Films of a collection, in the order they came out, a film of several copies once. */
    @Query(
        "SELECT $CARD_ROW FROM items WHERE collectionId = :collectionId AND kind = 'MOVIE' AND versionOf IS NULL " +
            "ORDER BY year IS NULL, year, sortTitle"
    )
    suspend fun collectionFilms(collectionId: Long): List<ItemEntity>

    /**
     * The films of the collections [collectionIds], as their cards tell of them: collection by collection,
     * each in the order they came out ([collectionFilms]). One query for a whole page of collections.
     */
    @Query(
        "SELECT collectionId, year, rating, genres, played, posterPath, userPosterPath, backdropPath, userBackdropPath " +
            "FROM items WHERE kind = 'MOVIE' AND versionOf IS NULL AND collectionId IN (:collectionIds) " +
            "ORDER BY collectionId, year IS NULL, year, sortTitle, id"
    )
    suspend fun collectionMembers(collectionIds: List<Long>): List<CollectionMember>

    /** Collections holding at least [minFilms] films of the library. */
    @Query(
        "SELECT COUNT(*) FROM (SELECT collectionId FROM items WHERE kind = 'MOVIE' AND collectionId IS NOT NULL " +
            "AND versionOf IS NULL GROUP BY collectionId HAVING COUNT(*) >= :minFilms)"
    )
    suspend fun collectionCount(minFilms: Int): Int

    @RawQuery
    suspend fun collectionQuery(query: SupportSQLiteQuery): List<CollectionEntity>

    @Query("DELETE FROM collections WHERE id NOT IN (SELECT collectionId FROM items WHERE collectionId IS NOT NULL)")
    suspend fun deleteEmptyCollectionRows()

    @Query("DELETE FROM saga_parts WHERE collectionId NOT IN (SELECT id FROM collections)")
    suspend fun deleteOrphanSagaParts()

    /** Collections no film is in any more, and what TMDB listed of them. */
    @Transaction
    suspend fun deleteEmptyCollections() {
        deleteEmptyCollectionRows()
        deleteOrphanSagaParts()
    }

    @Query("SELECT * FROM collections WHERE id IN (:ids)")
    suspend fun collections(ids: List<Long>): List<CollectionEntity>

    /** Collections TMDB knows whose films it was not asked for yet ([SagaPartEntity]), [limit] at most. */
    @Query("SELECT * FROM collections WHERE tmdbId IS NOT NULL AND fetchedAt IS NULL ORDER BY id LIMIT :limit")
    suspend fun sagasDue(limit: Int): List<CollectionEntity>

    // Sagas whole

    @Query("DELETE FROM saga_parts WHERE collectionId = :collectionId")
    suspend fun deleteSagaParts(collectionId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSagaParts(parts: List<SagaPartEntity>)

    /** The films TMDB lists of the collection [collectionId], in place of those it listed before. */
    @Transaction
    suspend fun replaceSagaParts(collectionId: Long, parts: List<SagaPartEntity>) {
        deleteSagaParts(collectionId)
        insertSagaParts(parts)
    }

    /** The films TMDB lists of a saga, in the order they came out, with the library's title of each it holds. */
    @Query(
        "SELECT p.*, (SELECT i.id FROM items i WHERE i.kind = 'MOVIE' AND i.tmdbId = p.tmdbId AND i.versionOf IS NULL " +
            "ORDER BY i.id LIMIT 1) AS itemId FROM saga_parts p WHERE p.collectionId = :collectionId ORDER BY p.ord"
    )
    suspend fun sagaParts(collectionId: Long): List<SagaPartRow>

    // Credits

    /** The cast of the title [itemId], in billing order. */
    @Query("SELECT * FROM credits WHERE itemId = :itemId AND job = 'CAST' ORDER BY ord")
    suspend fun cast(itemId: Long): List<CreditEntity>

    /** The credits of the title [itemId], job by job in billing order, each with how many titles hold its person. */
    @Query(
        "SELECT c.personKey, c.name, c.job, c.ord, c.character, c.profilePath, $TITLES_OF_PERSON AS titles " +
            "FROM credits c WHERE c.itemId = :itemId ORDER BY c.job, c.ord"
    )
    suspend fun creditsOf(itemId: Long): List<TitleCredit>

    /** Every credit of the person [personKey], on the titles of the library (a film of several copies once). */
    @Query(
        "SELECT c.itemId, c.job, c.ord, c.name, c.character, c.profilePath FROM credits c JOIN items i ON i.id = c.itemId " +
            "WHERE c.personKey = :personKey AND i.versionOf IS NULL"
    )
    suspend fun creditsOfPerson(personKey: String): List<PersonCredit>

    /** The people of [job] in the most titles of [kind] (any when null), [minTitles] at least, most first. */
    @Query(
        "$PERSON_SUMMARY WHERE c.job = :job AND i.versionOf IS NULL AND (:kind IS NULL OR i.kind = :kind) " +
            "GROUP BY c.personKey HAVING COUNT(DISTINCT c.itemId) >= :minTitles ORDER BY titles DESC, name LIMIT :limit"
    )
    suspend fun topPeople(job: Job, kind: ItemKind?, minTitles: Int, limit: Int): List<PersonSummary>

    /** The people [personKeys], with how many titles hold each. */
    @Query("$PERSON_SUMMARY WHERE c.personKey IN (:personKeys) AND i.versionOf IS NULL GROUP BY c.personKey")
    suspend fun people(personKeys: List<String>): List<PersonSummary>

    @Query("DELETE FROM credits WHERE itemId = :itemId")
    suspend fun deleteCredits(itemId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCredits(credits: List<CreditEntity>)

    /** Of the keys [personKeys], those some credit holds. */
    @Query("SELECT DISTINCT personKey FROM credits WHERE personKey IN (:personKeys)")
    suspend fun heldKeys(personKeys: List<String>): List<String>

    /** The TMDB keys credits know the people of [foldedNames] by. */
    @Query(
        "SELECT foldedName, MIN(personKey) AS personKey FROM credits WHERE foldedName IN (:foldedNames) " +
            "AND personKey LIKE '${PersonKeys.TMDB}%' GROUP BY foldedName"
    )
    suspend fun tmdbKeysOf(foldedNames: List<String>): List<NamedKey>

    @Query("UPDATE credits SET personKey = :personKey WHERE personKey = :former")
    suspend fun rekeyPerson(former: String, personKey: String)

    @Query("UPDATE items SET creditsAt = :at WHERE id = :id")
    suspend fun setCreditsAt(id: Long, at: Long)

    /**
     * The credits of the title [itemId], in place of those it had, written [at]: a person named by name alone takes
     * the TMDB key other titles know them by, and those other titles name by name alone a person TMDB keys here
     * take that key, so that one person is one across the library. Their words for searches with them.
     */
    @Transaction
    suspend fun replaceCredits(itemId: Long, credits: List<CreditEntity>, at: Long) {
        val named = credits.filter { it.personKey.startsWith(PersonKeys.NAME) }.map { it.foldedName }.distinct()
        val tmdbKeys = named.chunked(SQL_IN_LIMIT).flatMap { tmdbKeysOf(it) }.associate { it.foldedName to it.personKey }
        val keyed = credits.map { c ->
            if (c.personKey.startsWith(PersonKeys.NAME)) tmdbKeys[c.foldedName]?.let { c.copy(personKey = it) } ?: c else c
        }
        val byName = keyed.filter { it.personKey.startsWith(PersonKeys.TMDB) }.associate { PersonKeys.name(it.foldedName) to it.personKey }
        byName.keys.toList().chunked(SQL_IN_LIMIT).flatMap { heldKeys(it) }.forEach { rekeyPerson(it, byName.getValue(it)) }
        deleteCredits(itemId)
        insertCredits(keyed)
        insertSearchWords(keyed.distinctBy { it.personKey }.flatMap { SearchWords.rows(SearchKind.PERSON, it.personKey, it.name) })
        setCreditsAt(itemId, at)
    }

    /**
     * Films and shows whose credits were never written ([ItemEntity.creditsAt]) though they were looked up, [limit]
     * at most, whole: titles known before credits were kept. Those TMDB knows only [withTmdb].
     */
    @Query(
        "SELECT * FROM items WHERE kind IN ('MOVIE', 'SERIES') AND creditsAt IS NULL AND matchedAt IS NOT NULL " +
            "AND (:withTmdb OR tmdbId IS NULL) ORDER BY id LIMIT :limit"
    )
    suspend fun creditsDue(withTmdb: Boolean, limit: Int): List<ItemEntity>

    /**
     * What the backfill read of a title known before credits were kept: its votes and its edition, from TMDB's
     * answer and its NFO, and when its credits were written. Nothing else of the row: never what the user did.
     */
    @Query("UPDATE items SET votes = :votes, edition = :edition, creditsAt = :at WHERE id = :id")
    suspend fun setBackfilled(id: Long, votes: Int?, edition: String?, at: Long)

    // Search

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSearchWords(words: List<SearchWordEntity>)

    @Query("DELETE FROM search_words WHERE kind = :kind AND ref = :ref")
    suspend fun deleteSearchWords(kind: SearchKind, ref: String)

    /** What words found: the query [LocalCatalog] writes, one word's range of each kind by the primary key. */
    @RawQuery
    suspend fun searchRefs(query: SupportSQLiteQuery): List<SearchRef>
}
