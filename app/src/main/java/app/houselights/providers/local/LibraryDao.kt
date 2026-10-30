package app.houselights.providers.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** Max ids bound per statement: Android 9's SQLite (3.22) allows 999 variables; Robolectric more, so tests check it. */
internal const val SQL_IN_LIMIT = 500

/** Watched or not, back to the start; unwatched also forgets when it was played, as Jellyfin does, so it leaves À suivre. */
private const val SET_PLAYED =
    "UPDATE items SET played = :played, positionMs = 0, lastPlayedAt = CASE WHEN :played THEN :now ELSE NULL END"

/** Main row of a film's copies ([LibraryDao.groupVersions]): the lowest id with its TMDB id. */
private const val TITLE_OF_COPY = "NULLIF((SELECT MIN(o.id) FROM items o WHERE o.kind = 'MOVIE' AND o.tmdbId = items.tmdbId), id)"

/** Groups each film's copies, writing only rows whose versionOf changes (see [LibraryDao.groupVersions]). */
internal const val GROUP_VERSIONS = "UPDATE items SET versionOf = $TITLE_OF_COPY WHERE kind = 'MOVIE' AND versionOf IS NOT $TITLE_OF_COPY"

/** The episodes of the show whose row is `items`'s. */
private const val EPISODES_OF_SHOW = "SELECT 1 FROM items e WHERE e.kind = 'EPISODE' AND e.seriesId = items.id"

/**
 * Films and shows to identify: TMDB found nothing for them or hesitated ([MatchState]) and they have no TMDB id, unless
 * the user left them as they are; a film held missing is not ([MissingFiles]) until its file is back, nor a show whose
 * every episode is, which its page says is held ([LocalMapper]), identifying able to wait, though Modifier still opens
 * Identifier on its page ([doubt]). The unary + keeps SQLite, which has no statistics, from reading the titles not held,
 * nearly the whole library, through the index of held rows rather than the few to identify through matchState's.
 */
internal const val TO_IDENTIFY =
    "kind IN ('MOVIE', 'SERIES') AND matchState IN ('NOT_FOUND', 'UNSURE') AND tmdbId IS NULL AND userNoMatch = 0 " +
        "AND +missingSince IS NULL AND (kind = 'MOVIE' OR NOT EXISTS ($EPISODES_OF_SHOW) " +
        "OR EXISTS ($EPISODES_OF_SHOW AND e.missingSince IS NULL))"

/** The keys of the local films' and shows' choices of tracks ([tracksKey]). */
private const val TRACK_KEYS =
    "SELECT CASE WHEN kind = 'MOVIE' AND tmdbId IS NOT NULL THEN 'local:film:' || tmdbId ELSE 'local:' || id END " +
        "FROM items WHERE kind IN ('MOVIE', 'SERIES')"

/**
 * A film's or an episode's row held while its file is missing ([MissingFiles.isDrop]): its folder, what it is (an episode by
 * its show, season and number), its file, and since when.
 */
data class HeldRow(
    val id: Long,
    val sourceId: Long,
    val kind: ItemKind,
    val title: String,
    val year: Int?,
    val filePath: String?,
    val missingSince: Long,
    val showTitle: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
)

/** Counts shown on a source's settings showcase: titles (films and shows), Ultra HD files, shows and their episodes. */
data class FolderCounts(val sourceId: Long, val titles: Int, val ultraHd: Int, val shows: Int = 0, val episodes: Int = 0)

/** A saga part as TMDB lists it, with the library's matching item, if any. */
data class SagaPartRow(@Embedded val part: SagaPartEntity, val itemId: Long?)

/** Last viewing time of a show or saga [id]. */
data class Recent(val id: Long, val at: Long)

/** Viewing state of a saga film, listed in saga order ([LibraryDao.startedSagas]). */
data class SagaFilmState(val id: Long, val collectionId: Long, val played: Boolean, val positionMs: Long, val lastPlayedAt: Long?)

/** Columns of a film or episode file used by [LibraryCount], none needing decoding ([LibraryDao.fileFacts]). */
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

/** A distinct file ending of the library ([LibraryDao.fileEnds]). */
data class FileEnd(val tail: String, val bluRay: Boolean, val dvd: Boolean)

/**
 * Where an episode stands and what it shows, written alone ([LibraryDao.placeEpisodes]) when its show's order or TMDB's
 * numbering moves it: never what the user did to it (watched, resume point, favourite), nor its id, so nothing of it
 * is lost, Trakt's queue and its show's choice of tracks included.
 */
data class EpisodePlace(
    val id: Long,
    val parentId: Long?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val lastEpisodeNumber: Int?,
    val sortTitle: String,
    val absoluteNumber: Int?,
    val tmdbSeason: Int?,
    val tmdbEpisode: Int?,
    val tmdbLastEpisode: Int?,
    val title: String,
    val overview: String?,
    val stillPath: String?,
    val runtimeMin: Int?,
    val rating: Double?,
    val year: Int?,
    val matchedAt: Long?,
)

/** An episode at a place of its show, and its file ([LibraryDao.episodesAt]). */
data class PlacedFile(val id: Long, val filePath: String?)

/**
 * The library's one DAO, which Room implements. A class that owns only part of it takes the narrower view it
 * extends: [SourceDao], [PlaybackStateDao], [CreditDao], [SearchWordDao], [KeptStateDao], [EpisodeOrderDao],
 * [IgnoredFileDao], [FirstTitleDao], [SagaDao] or [CopyDao].
 */
@Dao
interface LibraryDao :
    SourceDao, PlaybackStateDao, CreditDao, SearchWordDao, KeptStateDao, EpisodeOrderDao, IgnoredFileDao, FirstTitleDao, SagaDao, CopyDao {
    // User changes are written column by column so they never overwrite a scan's work.
    // Watched state is set by parent, not by id list: a long show has thousands of episodes.

    /** Applies to all copies of a film ([COPIES]). */
    @Query("$SET_PLAYED WHERE $COPIES")
    suspend fun setPlayed(id: Long, played: Boolean, now: Long)

    @Query("$SET_PLAYED WHERE kind = 'EPISODE' AND seriesId = :seriesId")
    suspend fun setSeriesPlayed(seriesId: Long, played: Boolean, now: Long)

    @Query("$SET_PLAYED WHERE kind = 'EPISODE' AND parentId = :seasonId")
    suspend fun setSeasonPlayed(seasonId: Long, played: Boolean, now: Long)

    @Query("$SET_PLAYED WHERE kind = 'MOVIE' AND collectionId = :collectionId")
    suspend fun setCollectionPlayed(collectionId: Long, played: Boolean, now: Long)

    /** Applies to all copies of a film ([COPIES]). */
    @Query("UPDATE items SET favorite = :favorite WHERE $COPIES")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    /** Sets the copy to play on main row [id] ([ItemEntity.versionChoice]); null plays the one [VersionRank.played] names. */
    @Query("UPDATE items SET versionChoice = :choice WHERE id = :id")
    suspend fun setVersionChoice(id: Long, choice: Long?)

    // Items

    /** Insert only: inserting an existing row fails rather than replacing its user state. */
    @Insert
    suspend fun insertRow(item: ItemEntity): Long

    /** Inserts a row ([insertRow]) and indexes a film's or show's search words. */
    @Transaction
    suspend fun insert(item: ItemEntity): Long = insertRow(item).also { indexTitle(item.copy(id = it)) }

    @Update
    suspend fun update(item: ItemEntity)

    @Transaction
    suspend fun indexTitle(item: ItemEntity) {
        if (item.kind != ItemKind.MOVIE && item.kind != ItemKind.SERIES) return
        deleteSearchWords(SearchKind.TITLE, item.id.toString())
        insertSearchWords(SearchWords.rows(SearchKind.TITLE, item.id.toString(), item.title, item.originalTitle))
    }

    /** The full row, NFO text included: for writing back during a scan, and for the player's artwork. */
    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun item(id: Long): ItemEntity?

    /** The row without its NFO text, which can reach 1 MB ([ITEM_ROW]); used by the title page. */
    @Query("SELECT $ITEM_ROW FROM items WHERE id = :id")
    suspend fun itemRow(id: Long): ItemEntity?

    @Query("SELECT sourceId, nfoPath, nfo FROM items WHERE id = :id")
    suspend fun nfoOf(id: Long): ItemNfo?

    @Query("SELECT id, nfo FROM items WHERE id IN (:ids) AND nfo IS NOT NULL")
    suspend fun nfosBatch(ids: List<Long>): List<RowNfo>

    /** The NFO text of each row of [ids] that has one, by id: a query per [SQL_IN_LIMIT] rows. */
    suspend fun nfosOf(ids: List<Long>): Map<Long, String> =
        ids.chunked(SQL_IN_LIMIT).flatMap { nfosBatch(it) }.associate { it.id to it.nfo }

    /** A source's file rows without NFO text; used by tests to inspect a scan's result. */
    @Query("SELECT $ITEM_ROW FROM items WHERE sourceId = :sourceId AND filePath IS NOT NULL")
    suspend fun filesOf(sourceId: Long): List<ItemEntity>

    @Query(
        "SELECT id, filePath, parts, fileSize, missingSince, seriesId, tmdbId, userTmdbId, matchedAt, sidecarKey, probeVersion, " +
            "hintKey FROM items WHERE sourceId = :sourceId AND filePath IS NOT NULL",
    )
    suspend fun knownFiles(sourceId: Long): List<KnownFile>

    @Query("SELECT EXISTS(SELECT 1 FROM items WHERE sourceId = :sourceId AND filePath = :filePath)")
    suspend fun hasFile(sourceId: Long, filePath: String): Boolean

    @Query("SELECT * FROM items WHERE kind = 'SERIES' AND matchKey = :key LIMIT 1")
    suspend fun seriesByKey(key: String): ItemEntity?

    /** Shows of that name whose key has a year ([FilenameParser.showKey]); [nameKey] contains no LIKE wildcard. */
    @Query("SELECT * FROM items WHERE kind = 'SERIES' AND matchKey LIKE :nameKey || '${FilenameParser.YEAR_MARK}%'")
    suspend fun seriesNamed(nameKey: String): List<ItemEntity>

    /** Episodes under [dir] (never ""): paths between "dir/" and "dir0", a range scan on the index. */
    @Query(
        "SELECT $CARD_ROW FROM items WHERE kind = 'EPISODE' AND sourceId = :sourceId " +
            "AND filePath > :dir || '/' AND filePath < :dir || '0'",
    )
    suspend fun episodesUnder(sourceId: Long, dir: String): List<ItemEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM items WHERE kind = 'EPISODE' AND seriesId = :seriesId AND id != :episodeId)")
    suspend fun hasOtherEpisodes(seriesId: Long, episodeId: Long): Boolean

    @Query("SELECT * FROM items WHERE kind = 'SEASON' AND parentId = :seriesId AND seasonNumber = :season LIMIT 1")
    suspend fun season(seriesId: Long, season: Int): ItemEntity?

    /** Show [seriesId]'s episodes at season [season], episode [episode]: who holds a place ([EpisodeNumbering]). */
    @Query(
        "SELECT id, filePath FROM items WHERE kind = 'EPISODE' AND seriesId = :seriesId AND seasonNumber = :season " +
            "AND episodeNumber = :episode",
    )
    suspend fun episodesAt(seriesId: Long, season: Int, episode: Int): List<PlacedFile>

    /** Whether show [seriesId] has episodes numbered from its start ([ItemEntity.absoluteNumber]). */
    @Query("SELECT EXISTS(SELECT 1 FROM items WHERE kind = 'EPISODE' AND seriesId = :seriesId AND absoluteNumber IS NOT NULL)")
    suspend fun hasAbsoluteEpisodes(seriesId: Long): Boolean

    /** Moves episodes to their places, with what they show there ([EpisodePlace]); nothing of the user's changes. */
    @Update(entity = ItemEntity::class)
    suspend fun placeEpisodes(places: List<EpisodePlace>)

    /** The order show [id]'s episodes are now placed in ([ItemEntity.episodeOrder]); null for TMDB's own seasons. */
    @Query("UPDATE items SET episodeOrder = :order WHERE id = :id")
    suspend fun setEpisodeOrder(id: Long, order: String?)

    /** Show [id]'s episodes changed order at [at] ([ItemEntity.reorderedAt]). */
    @Query("UPDATE items SET reorderedAt = :at WHERE id = :id")
    suspend fun setReorderedAt(id: Long, at: Long)

    /** The order the user chose for show [id] ([ItemEntity.userEpisodeOrder]); null for the automatic one. */
    @Query("UPDATE items SET userEpisodeOrder = :order WHERE id = :id")
    suspend fun setUserEpisodeOrder(id: Long, order: String?)

    /**
     * Show [seriesId] read again at the next pass, through its first episode, as the 20→21 migration leaves a show whose
     * tvshow.nfo names an order: its episodes could not be placed in the order they follow, TMDB not answering.
     */
    @Query(
        "UPDATE items SET matchedAt = NULL WHERE id = :seriesId OR id = " +
            "(SELECT MIN(id) FROM items WHERE kind = 'EPISODE' AND seriesId = :seriesId)",
    )
    suspend fun readAgain(seriesId: Long)

    @Query("SELECT * FROM items WHERE parentId = :parentId ORDER BY seasonNumber, episodeNumber, sortTitle")
    suspend fun children(parentId: Long): List<ItemEntity>

    // --- Rows to show (see CARD_ROW) ---

    @Query("SELECT $CARD_ROW FROM items WHERE parentId = :parentId ORDER BY seasonNumber, episodeNumber, sortTitle")
    suspend fun childRows(parentId: Long): List<ItemEntity>

    @Query("SELECT $CARD_ROW FROM items WHERE id IN (:ids)")
    suspend fun rows(ids: List<Long>): List<ItemEntity>

    /** [rows] as a wall shows them, without the text only a page shows ([WALL_ROW]). */
    @Query("SELECT $WALL_ROW FROM items WHERE id IN (:ids)")
    suspend fun wallCards(ids: List<Long>): List<ItemEntity>

    /** Unwatched episodes, seasons, episodes and those held per show, for a whole page in one query. */
    @Query(
        "SELECT seriesId, SUM(played = 0) AS unplayed, COUNT(DISTINCT parentId) AS seasons, COUNT(*) AS episodes, " +
            "SUM(missingSince IS NOT NULL) AS held, MIN(missingSince) AS heldSince FROM items " +
            "WHERE kind = 'EPISODE' AND seriesId IN (:seriesIds) GROUP BY seriesId",
    )
    suspend fun seriesCounts(seriesIds: List<Long>): List<SeriesCounts>

    /** Episode and watched counts per season, in one query for the season track. */
    @Query(
        "SELECT parentId AS seasonId, COUNT(*) AS episodes, SUM(played) AS played FROM items " +
            "WHERE kind = 'EPISODE' AND seriesId = :seriesId AND parentId IS NOT NULL GROUP BY parentId",
    )
    suspend fun seasonCounts(seriesId: Long): List<SeasonCounts>

    @Query(
        "SELECT seriesId AS id, MAX(lastPlayedAt) AS at FROM items WHERE kind = 'EPISODE' AND seriesId IS NOT NULL " +
            "AND lastPlayedAt IS NOT NULL GROUP BY seriesId ORDER BY MAX(lastPlayedAt) DESC LIMIT :limit",
    )
    suspend fun recentSeries(limit: Int): List<Recent>

    /**
     * Films of the [limit] most recently watched sagas, by saga in release order, with copies' state merged: watched as
     * any copy is, at the furthest copy's resume point, which [LocalProvider] reads as the copy Lire plays.
     */
    @Query(
        "SELECT id, collectionId, (played = 1 OR EXISTS (SELECT 1 FROM items c WHERE c.versionOf = items.id AND c.played = 1)) " +
            "AS played, (SELECT MAX(c.positionMs) FROM items c WHERE c.id = items.id OR c.versionOf = items.id) AS positionMs, " +
            "(SELECT MAX(c.lastPlayedAt) FROM items c WHERE c.id = items.id OR c.versionOf = items.id) AS lastPlayedAt " +
            "FROM items WHERE kind = 'MOVIE' AND versionOf IS NULL " +
            "AND collectionId IN (SELECT collectionId FROM items WHERE kind = 'MOVIE' AND collectionId IS NOT NULL AND played = 1 " +
            "AND lastPlayedAt IS NOT NULL GROUP BY collectionId ORDER BY MAX(lastPlayedAt) DESC LIMIT :limit) " +
            "ORDER BY collectionId, year IS NULL, year, sortTitle, id",
    )
    suspend fun startedSagas(limit: Int): List<SagaFilmState>

    @Query("SELECT $CARD_ROW FROM items WHERE seriesId = :seriesId AND kind = 'EPISODE' ORDER BY seasonNumber, episodeNumber")
    suspend fun episodesOfSeries(seriesId: Long): List<ItemEntity>

    /** Watch state of every episode of [seriesIds], in order: finds next episodes without loading full rows. */
    @Query(
        "SELECT id, seriesId, seasonNumber, episodeNumber, played, positionMs FROM items " +
            "WHERE kind = 'EPISODE' AND seriesId IN (:seriesIds) ORDER BY seriesId, seasonNumber, episodeNumber, id",
    )
    suspend fun episodeStates(seriesIds: List<Long>): List<EpisodeState>

    @Query(
        "SELECT $CARD_ROW FROM items WHERE kind IN ('MOVIE','EPISODE') AND positionMs > 0 AND played = 0 " +
            "ORDER BY lastPlayedAt DESC LIMIT :limit",
    )
    suspend fun inProgress(limit: Int): List<ItemEntity>

    /** Most recently added titles, excluding secondary copies of films ([ItemEntity.versionOf]). */
    @Query("SELECT $CARD_ROW FROM items WHERE kind = :kind AND versionOf IS NULL ORDER BY addedAt DESC, id DESC LIMIT :limit")
    suspend fun latest(kind: ItemKind, limit: Int): List<ItemEntity>

    @Query("SELECT COUNT(*) FROM items WHERE kind = :kind")
    suspend fun count(kind: ItemKind): Int

    /** Whether the library holds anything: a pass that begins on none is its very first scan ([ScanState.homeFilling]). */
    @Query("SELECT EXISTS(SELECT 1 FROM items)")
    suspend fun anyItem(): Boolean

    /** Episodes added since [since], not watched, found after their folder's first scan, as a title counts as new. */
    @Query(
        "SELECT COUNT(*) FROM items WHERE kind = 'EPISODE' AND played = 0 AND addedAt >= :since " +
            "AND addedAt > (SELECT firstScanAt FROM sources WHERE sources.id = items.sourceId AND firstScanAt > 0)",
    )
    suspend fun newEpisodes(since: Long): Int

    /** Each title's genres, with the lower() form the genre filter compares against ([PipeList.distinctGenres]). */
    @Query("SELECT genres, lower(genres) AS folded FROM items WHERE kind = :kind AND genres != ''")
    suspend fun genresOf(kind: ItemKind): List<GenreList>

    @Query("SELECT DISTINCT year FROM items WHERE kind = :kind AND year IS NOT NULL")
    suspend fun yearsOf(kind: ItemKind): List<Int>

    @Query("SELECT DISTINCT badges FROM items WHERE kind = :kind AND badges != ''")
    suspend fun badgesOf(kind: ItemKind): List<String>

    @Query("SELECT COUNT(*) FROM items WHERE sourceId = :sourceId AND kind IN ('MOVIE','SERIES')")
    fun observeTitleCount(sourceId: Long): Flow<Int>

    /** How many films and shows are to identify ([TO_IDENTIFY]). */
    @Query("SELECT COUNT(*) FROM items WHERE $TO_IDENTIFY")
    fun observeToIdentifyCount(): Flow<Int>

    /** The films and shows to identify ([TO_IDENTIFY]), the latest added first. */
    @Query("SELECT $CARD_ROW FROM items WHERE $TO_IDENTIFY ORDER BY addedAt DESC, id DESC")
    suspend fun toIdentify(): List<ItemEntity>

    /** The title to identify after the one added at [addedAt] as row [id], in [toIdentify]'s order; null after the last. */
    @Query(
        "SELECT id FROM items WHERE $TO_IDENTIFY AND (addedAt < :addedAt OR (addedAt = :addedAt AND id < :id)) " +
            "ORDER BY addedAt DESC, id DESC LIMIT 1",
    )
    suspend fun nextToIdentify(addedAt: Long, id: Long): Long?

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

    /** A title still to identify once TMDB was asked again ([Reidentify]): what it made of it, and when. */
    @Query("UPDATE items SET matchState = :state, matchedAt = :at WHERE id = :id")
    suspend fun setMatchState(id: Long, state: MatchState, at: Long)

    @Query("SELECT COUNT(*) FROM items WHERE kind = 'SEASON' AND parentId = :seriesId")
    suspend fun seasonCount(seriesId: Long): Int

    /** The candidates of a title TMDB hesitated on ([MatchState.UNSURE]), the best first. */
    @Query("SELECT * FROM suggestions WHERE itemId = :itemId ORDER BY rank")
    suspend fun suggestions(itemId: Long): List<SuggestionEntity>

    /** The candidates of titles [ids] ([suggestions]), each title's the best first; at most [SQL_IN_LIMIT] ids. */
    @Query("SELECT * FROM suggestions WHERE itemId IN (:ids) ORDER BY itemId, rank")
    suspend fun suggestionsOf(ids: List<Long>): List<SuggestionEntity>

    /** A film or show left as it is by the user, or no longer ([ItemEntity.userNoMatch]). */
    @Query("UPDATE items SET userNoMatch = :kept WHERE id = :id")
    suspend fun setUserNoMatch(id: Long, kept: Boolean)

    /** Each show of [ids]'s first episode file by path, where its folder is; at most [SQL_IN_LIMIT] ids. */
    @Query("SELECT seriesId, MIN(filePath) AS filePath FROM items WHERE kind = 'EPISODE' AND seriesId IN (:ids) GROUP BY seriesId")
    suspend fun showFiles(ids: List<Long>): List<ShowFile>

    @Query("DELETE FROM suggestions WHERE itemId = :itemId")
    suspend fun forgetSuggestions(itemId: Long)

    @Insert
    suspend fun insertSuggestions(rows: List<SuggestionEntity>)

    /** Replaces [itemId]'s candidates with [rows], none when empty. */
    @Transaction
    suspend fun replaceSuggestions(itemId: Long, rows: List<SuggestionEntity>) {
        forgetSuggestions(itemId)
        if (rows.isNotEmpty()) insertSuggestions(rows)
    }

    /** Candidates of titles gone. */
    @Query("DELETE FROM suggestions WHERE itemId NOT IN (SELECT id FROM items)")
    suspend fun deleteOrphanSuggestions()

    /** Folder [sourceId]'s videos its names place nowhere, now [rows] ([IgnoredFileEntity]). */
    @Transaction
    suspend fun replaceIgnored(sourceId: Long, rows: List<IgnoredFileEntity>) {
        forgetIgnoredOf(sourceId)
        if (rows.isNotEmpty()) insertIgnored(rows)
    }

    @Query("SELECT COUNT(*) FROM items WHERE kind IN ('MOVIE','SERIES')")
    fun observeAllTitleCount(): Flow<Int>

    @Query(
        "SELECT COALESCE(NULLIF(userPosterPath, ''), posterPath) FROM items " +
            "WHERE sourceId = :sourceId AND kind IN ('MOVIE','SERIES') " +
            "AND COALESCE(NULLIF(userPosterPath, ''), posterPath) IS NOT NULL ORDER BY addedAt DESC LIMIT :limit",
    )
    fun observePosters(sourceId: Long, limit: Int): Flow<List<String>>

    @Query(
        "SELECT COALESCE(NULLIF(userPosterPath, ''), posterPath) FROM items " +
            "WHERE kind IN ('MOVIE','SERIES') " +
            "AND COALESCE(NULLIF(userPosterPath, ''), posterPath) IS NOT NULL ORDER BY addedAt DESC LIMIT :limit",
    )
    fun observeAllPosters(limit: Int): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM items WHERE sourceId = :sourceId AND filePath IS NOT NULL AND ${PipeList.BADGES_MATCH}")
    fun observeDefinitionCount(sourceId: Long, token: String): Flow<Int>

    /** The counts above, and shows and episodes, for every source in one query, as each Flow query reruns on every write to items. */
    @Query(
        "SELECT sourceId, SUM(kind IN ('MOVIE','SERIES')) AS titles, " +
            "SUM(filePath IS NOT NULL AND ${PipeList.BADGES_MATCH}) AS ultraHd, " +
            "SUM(kind = 'SERIES') AS shows, SUM(kind = 'EPISODE') AS episodes " +
            "FROM items GROUP BY sourceId",
    )
    fun observeFolderCounts(token: String): Flow<List<FolderCounts>>

    @RawQuery
    suspend fun query(query: SupportSQLiteQuery): List<ItemEntity>

    @RawQuery
    suspend fun countQuery(query: SupportSQLiteQuery): Int

    /** Titles of [kind] with [genre], best rated first; ties sort by id so the row order is stable. */
    @Query(
        "SELECT $CARD_ROW FROM items WHERE kind = :kind AND id != :excludeId AND versionOf IS NULL AND ${PipeList.GENRES_MATCH} " +
            "ORDER BY rating DESC, id LIMIT :limit",
    )
    suspend fun byGenre(kind: ItemKind, genre: String, excludeId: Long, limit: Int): List<ItemEntity>

    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun deleteItemBatch(ids: List<Long>)

    /** Deletes any number of items, [SQL_IN_LIMIT] per statement, atomically. */
    @Transaction
    suspend fun deleteItems(ids: List<Long>) = ids.chunked(SQL_IN_LIMIT).forEach { deleteItemBatch(it) }

    /**
     * Points a row at its replacement file ([MissingFiles]): another size resets the probe, the same size is a rename.
     * SQLite evaluates every expression against the old row, so fileSize is the old file's.
     */
    @Query(
        "UPDATE items SET filePath = :filePath, fileSize = :fileSize, " +
            "mediaInfo = CASE WHEN fileSize = :fileSize THEN mediaInfo ELSE NULL END, " +
            "probeVersion = CASE WHEN fileSize = :fileSize THEN probeVersion ELSE 0 END, " +
            "fileDurationMs = CASE WHEN fileSize = :fileSize THEN fileDurationMs ELSE NULL END, missingSince = NULL WHERE id = :id",
    )
    suspend fun relink(id: Long, filePath: String, fileSize: Long)

    /** Sets [ItemEntity.parts]; a film gaining parts is rematched, as it was matched by its first part ("Film CD1"). */
    @Query(
        "UPDATE items SET parts = :parts, " +
            "matchedAt = CASE WHEN parts = '' AND :parts != '' THEN NULL ELSE matchedAt END WHERE id = :id",
    )
    suspend fun setParts(id: Long, parts: String)

    @Query("UPDATE items SET missingSince = :since WHERE id IN (:ids)")
    suspend fun setMissingSinceBatch(ids: List<Long>, since: Long?)

    /** Sets [since] (first missed, see [MissingFiles]; null once back) on any number of rows, atomically. */
    @Transaction
    suspend fun setMissingSince(ids: List<Long>, since: Long?) =
        ids.chunked(SQL_IN_LIMIT).forEach { setMissingSinceBatch(it, since) }

    /**
     * The films and episodes held while their files are missing ([MissingFiles.isDrop]), by folder and title. Held rows
     * are dated, so `missingSince > 0` reads them through their index, where SQLite reads the whole library for an
     * `IS NOT NULL`; the same in [heldIn] and [heldOf].
     */
    @Query(
        "SELECT i.id, i.sourceId, i.kind, i.title, i.year, i.filePath, i.missingSince, s.title AS showTitle, i.seasonNumber, " +
            "i.episodeNumber FROM items i LEFT JOIN items s ON s.id = i.seriesId " +
            "WHERE i.missingSince > 0 AND i.filePath IS NOT NULL " +
            "ORDER BY i.sourceId, COALESCE(s.sortTitle, i.sortTitle), i.seasonNumber, i.episodeNumber, i.id",
    )
    fun observeHeld(): Flow<List<HeldRow>>

    /** The rows held in folder [sourceId] ([MissingFiles.isDrop]). */
    @Query("SELECT id FROM items WHERE sourceId = :sourceId AND missingSince > 0")
    suspend fun heldIn(sourceId: Long): List<Long>

    @Query("SELECT id FROM items WHERE missingSince IS NOT NULL AND id IN (:ids)")
    suspend fun heldAmongBatch(ids: List<Long>): List<Long>

    /** The rows held of title [id] ([MissingFiles.isDrop]): a film's copies, a show's episodes, or the episode itself. */
    @Query("SELECT id FROM items WHERE missingSince > 0 AND ($COPIES OR seriesId = :id)")
    suspend fun heldOf(id: Long): List<Long>

    /** The rows title [id] stands on: a film's copies ([COPIES]), else the row itself. */
    @Query("SELECT id FROM items WHERE $COPIES")
    suspend fun titleRows(id: Long): List<Long>

    /** The row among [ids] ([titleRows]) that holds their title, a film's copies grouped; null once none is left. */
    @Query("SELECT MIN(id) FROM items WHERE versionOf IS NULL AND id IN (:ids)")
    suspend fun titleAmong(ids: List<Long>): Long?

    /**
     * The films among main rows [ids] in several copies: held and started as the copy Lire plays is, watched as any copy
     * is ([LocalMapper.playedCopiesOf]).
     */
    @Query("SELECT DISTINCT versionOf FROM items WHERE versionOf IN (:ids)")
    suspend fun titlesInCopies(ids: List<Long>): List<Long>

    /**
     * The copies of the films [titles] ([titlesInCopies]), their main rows included, without NFO text, read whole as Lire
     * ranks them ([LocalMapper.playedCopiesOf]). [titles] is bound once, so [SQL_IN_LIMIT] of them stay under Android 9's
     * 999 arguments.
     */
    @Query("SELECT $ITEM_ROW FROM items WHERE kind = 'MOVIE' AND COALESCE(versionOf, id) IN (:titles) ORDER BY id")
    suspend fun copiesOf(titles: List<Long>): List<ItemEntity>

    /**
     * The films in several copies whose copies are not all watched, nor all started, as their main row is: the walls'
     * filters read them as the copy Lire plays is, watched as any copy is ([LocalFilters]).
     */
    @Query(
        "SELECT DISTINCT c.versionOf FROM items c JOIN items t ON t.id = c.versionOf " +
            "WHERE c.played != t.played OR (c.positionMs > 0) != (t.positionMs > 0)",
    )
    suspend fun copiesApart(): List<Long>

    /**
     * Removes the rows among [ids] still held while their files are missing ([MissingFiles.isDrop]), as a pass removes
     * files gone: what the user did is kept by title ([keepStateOf]); seasons and shows left empty go, kept too unless
     * [handedOver] ([deleteEmptyShows]), and sagas left without films. Rows whose files came back stay. How many went.
     */
    @Transaction
    suspend fun removeHeld(ids: Collection<Long>, handedOver: Set<Long> = emptySet()): Int {
        val held = ids.distinct().chunked(SQL_IN_LIMIT).flatMap { heldAmongBatch(it) }
        if (held.isEmpty()) return 0
        settleMissing(emptyList(), emptyList(), emptyList(), removed = held, parts = emptyList(), now = 0)
        deleteEmptyShows(handedOver)
        deleteEmptyCollections()
        return held.size
    }

    /**
     * Applies [MissingFiles] decisions in one transaction, as a library rename touches thousands of rows. What the user
     * did to the titles [removed] is kept ([keepStateOf]); rows [absorbed] into a film hold nothing of their own.
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
        absorbed: List<Long> = emptyList(),
    ) {
        relinked.forEach { (id, file) -> relink(id, file.relativePath, file.size) }
        parts.forEach { (id, files) -> setParts(id, PipeList.toText(files)) }
        setMissingSince(back, null)
        setMissingSince(held, now)
        keepStateOf(removed, alsoLeaving = absorbed)
        deleteItems(removed + absorbed)
        // If a film's main row was removed, its next copy becomes the main row.
        if (removed.isNotEmpty() || absorbed.isNotEmpty()) groupVersions()
    }

    /**
     * Keeps what the user did to rows [ids], about to be deleted with [alsoLeaving] ([KeptStates.plan]): a row staying
     * of the same title takes it, filling only what it lacks; else it is kept by title, replacing what an earlier
     * removal kept ([superseded] too), the newest [KEPT_MAX] entries kept. Runs in the deleting transaction; nothing
     * without ids.
     */
    suspend fun keepStateOf(ids: List<Long>, alsoLeaving: List<Long> = emptyList()) {
        if (ids.isEmpty()) return
        val rows = ids.chunked(SQL_IN_LIMIT).flatMap { keptRows(it) }
        val leaving = (ids + alsoLeaving).toHashSet()
        val plan = KeptStates.plan(rows, staying(rows).filterNot { it.id in leaving })
        plan.handed.forEach { (row, kept) -> restore(row, kept) }
        forgetKept(plan.emptied + superseded(rows))
        if (plan.kept.isEmpty()) return
        keep(plan.kept)
        trimKept(KEPT_MAX)
    }

    /**
     * Rows that may take the state of rows [leaving]: films and shows of their TMDB ids, episodes of their shows'; for
     * a title without one, films in its folder and episodes of its show ([KeptStates.plan] tells which).
     */
    suspend fun staying(leaving: List<KeptRow>): List<KeptRow> {
        fun ids(kind: ItemKind, of: (KeptRow) -> Int?) =
            leaving.filter { it.kind == kind }.mapNotNull(of).distinct().chunked(SQL_IN_LIMIT)
        val unnamed = leaving.filter { it.identity == null && it.showIdentity == null }
        val folders = unnamed.filter { it.kind == ItemKind.MOVIE }
            .mapNotNullTo(HashSet()) { row -> row.filePath?.let { row.sourceId to Sidecars.folderOf(it) } }
        val shows = unnamed.filter { it.kind == ItemKind.EPISODE }.mapNotNullTo(HashSet()) { it.seriesId }
        return ids(ItemKind.MOVIE) { it.identity }.flatMap { titlesKnownAs(ItemKind.MOVIE, it) } +
            ids(ItemKind.SERIES) { it.identity }.flatMap { titlesKnownAs(ItemKind.SERIES, it) } +
            ids(ItemKind.EPISODE) { it.showIdentity }.flatMap { episodesOfShowsKnownAs(it) } +
            folders.flatMap { (source, folder) -> keptRowsIn(source, folder) } + shows.flatMap { keptRowsOfSeries(it) }
    }

    /**
     * Entries that rows [leaving] without a TMDB id would have taken back by name in their folder ([KeptStates.fromHere]):
     * one TMDB matched waiting for TMDB to name the row, born while TMDB was out of reach. Their removal is the user's
     * latest state of the title, so an unmark made meanwhile is not undone once the title is back and named.
     */
    suspend fun superseded(leaving: List<KeptRow>): List<String> = leaving.filter { it.identity == null && it.showIdentity == null }
        .mapNotNull { row ->
            val (title, nameKey) = KeptKeys.of(row) ?: return@mapNotNull null
            val folder = row.filePath?.takeIf { row.kind == ItemKind.MOVIE }?.let(Sidecars::folderOf)
            KeptStates.fromHere(keptNamed(row.kind, nameKey).filter { it.title != title }, row.sourceId, folder)?.title
        }

    /**
     * Inserts [item], born with what [kept] held ([KeptStates.onto]), offers it [kept]'s choice of tracks and forgets
     * [kept], at once, as [giveBack] does.
     */
    @Transaction
    suspend fun insertGivenBack(item: ItemEntity, kept: KeptStateEntity): Long {
        val id = insert(item)
        kept.tracksAt?.let { offerTrackChoice(tracksKey(item.copy(id = id)), kept.audio, kept.subtitles, it) }
        forgetKept(listOf(kept.title))
        return id
    }

    /**
     * Gives row [id] what was kept under [title], filling only what it lacks, then forgets it; true when one was.
     * Called in the transaction that writes the row's TMDB id, by which Trakt names a title, so Trakt never sees the
     * title without what the user did. Written here, never reported ([WatchHistory]): nothing is queued for Trakt.
     */
    @Transaction
    suspend fun giveBack(id: Long, title: String): Boolean {
        val kept = keptState(title) ?: return false
        val row = keptRows(listOf(id)).singleOrNull() ?: return false
        restore(row, kept)
        forgetKept(listOf(title))
        return true
    }

    /** [giveBack] to show [seriesId], now known by TMDB as [show], and to its episodes by season and episodes. */
    @Transaction
    suspend fun giveBackShow(seriesId: Long, show: Int): Boolean {
        val own = giveBack(seriesId, KeptKeys.show(show))
        val kept = keptEpisodesOf(show).takeIf { it.isNotEmpty() } ?: return own
        val given = KeptStates.onEpisodes(kept, keptRowsOfSeries(seriesId))
        given.forEach { (episode, state) -> restore(episode, state) }
        forgetKept(given.map { it.second.title }.distinct())
        return own || given.isNotEmpty()
    }

    @Query(
        "DELETE FROM items WHERE kind = 'SEASON' AND id NOT IN " +
            "(SELECT parentId FROM items WHERE kind = 'EPISODE' AND parentId IS NOT NULL)",
    )
    suspend fun deleteEmptySeasons()

    @Query(
        "DELETE FROM items WHERE kind = 'SERIES' AND id NOT IN " +
            "(SELECT parentId FROM items WHERE kind = 'SEASON' AND parentId IS NOT NULL)",
    )
    suspend fun deleteEmptySeries()

    /** Shows of folders still there that have no episode left. */
    @Query(
        "SELECT id FROM items s WHERE kind = 'SERIES' AND sourceId IN (SELECT id FROM sources) " +
            "AND NOT EXISTS (SELECT 1 FROM items e WHERE e.kind = 'EPISODE' AND e.seriesId = s.id)",
    )
    suspend fun emptySeries(): List<Long>

    /**
     * Deletes seasons and shows left without episodes, keeping what the user did to a show ([keepStateOf]), unless
     * its folder went meanwhile: removing a folder forgets its titles ([removeSource]). Shows [handedOver], whose
     * episodes all went to another show along with their user state ([handOver]), keep nothing: a second copy would
     * undo, once that show is matched as they were, what the user changed on it meanwhile.
     */
    @Transaction
    suspend fun deleteEmptyShows(handedOver: Set<Long> = emptySet()) {
        keepStateOf(emptySeries().filterNot { it in handedOver })
        deleteEmptySeasons()
        deleteEmptySeries()
    }

    /** Deletes films and episodes of removed sources; shows and seasons, which can span sources, go once empty. */
    @Query("DELETE FROM items WHERE kind IN ('MOVIE', 'EPISODE') AND sourceId NOT IN (SELECT id FROM sources)")
    suspend fun deleteOrphanItems()

    /**
     * Moves shows and seasons of removed sources to a remaining episode's source, so its card counts them. Run after
     * [deleteEmptySeasons] and [deleteEmptySeries], so each has an episode left.
     */
    @Query(
        "UPDATE items SET sourceId = (SELECT e.sourceId FROM items e WHERE e.kind = 'EPISODE' " +
            "AND (e.seriesId = items.id OR e.parentId = items.id) ORDER BY e.id LIMIT 1) " +
            "WHERE kind IN ('SERIES', 'SEASON') AND sourceId NOT IN (SELECT id FROM sources) " +
            "AND EXISTS (SELECT 1 FROM items e WHERE e.kind = 'EPISODE' AND (e.seriesId = items.id OR e.parentId = items.id))",
    )
    suspend fun moveOrphanShows()

    /** Removes a source and its items atomically, forgetting what the user did to them and the files it left out. */
    @Transaction
    suspend fun removeSource(id: Long) {
        deleteSource(id)
        forgetKeptOf(id)
        forgetIgnoredOf(id)
        deleteOrphanItems()
        deleteEmptySeasons()
        deleteEmptySeries()
        moveOrphanShows()
        deleteEmptyCollections()
        groupVersions()
    }

    /** Runs [writes] atomically, e.g. an identification touching a title, its seasons and episodes. */
    @Transaction
    suspend fun inTransaction(writes: suspend () -> Unit) = writes()

    /**
     * Saves scan results, keeping columns the scan does not own, which may change meanwhile: user artwork and match, a
     * title left as it is, favourite, watched state, resume point, chosen copy, versionOf, creditsAt, a show's order of
     * episodes, placed ([setEpisodeOrder]) and chosen, and the arrival, which a title back takes again ([giveBack]) and a
     * new episode moves ([setAddedAt]). Reindexes search words on rename; a film matched anew takes its choice of tracks
     * to its new key.
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
                userNoMatch = current.userNoMatch,
                positionMs = current.positionMs,
                durationMs = current.durationMs,
                played = current.played,
                favorite = current.favorite,
                lastPlayedAt = current.lastPlayedAt,
                versionChoice = current.versionChoice,
                versionOf = current.versionOf,
                creditsAt = current.creditsAt,
                addedAt = current.addedAt,
                episodeOrder = current.episodeOrder,
                userEpisodeOrder = current.userEpisodeOrder,
                reorderedAt = current.reorderedAt,
            ),
        )
        if (item.title != current.title || item.originalTitle != current.originalTitle) indexTitle(item)
        if (item.tmdbId != current.tmdbId) moveTrackChoice(tracksKey(current), tracksKey(item))
    }

    /**
     * Gives [to] the choice of tracks kept under [from], unless it has its own, then forgets [from] unless a title
     * still has that key: another copy of the film, matched as before.
     */
    @Transaction
    suspend fun moveTrackChoice(from: String, to: String) {
        if (from == to) return
        copyTrackChoice(from, to)
        forgetTrackChoiceUnkeyed(from)
    }

    @Query(
        "INSERT OR IGNORE INTO track_choices (title, audio, subtitles, at) " +
            "SELECT :to, audio, subtitles, at FROM track_choices WHERE title = :from",
    )
    suspend fun copyTrackChoice(from: String, to: String)

    @Query("DELETE FROM track_choices WHERE title = :key AND :key NOT IN ($TRACK_KEYS)")
    suspend fun forgetTrackChoiceUnkeyed(key: String)

    /** Moves show [id] up in "recently added", as a new episode does. */
    @Query("UPDATE items SET addedAt = :at WHERE id = :id")
    suspend fun setAddedAt(id: Long, at: Long)

    /**
     * Copies favourite, user match, artwork, order of episodes and choice of tracks from [fromId] where [toId] lacks them
     * ([ShowIndexer]); a show left as it is leaves the show taking it over so.
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
                userNoMatch = to.userNoMatch || from.userNoMatch,
                userEpisodeOrder = to.userEpisodeOrder ?: from.userEpisodeOrder,
            ),
        )
        // Its episodes take the show's key ([tracksKey]); the former's choice would go with it ([deleteOrphanTrackChoices]).
        copyTrackChoice("local:$fromId", "local:$toId")
    }

    @Query("UPDATE items SET userPosterPath = :path WHERE id = :id")
    suspend fun setUserPoster(id: Long, path: String?)

    @Query("UPDATE items SET userBackdropPath = :path WHERE id = :id")
    suspend fun setUserBackdrop(id: Long, path: String?)

    @Query("UPDATE items SET userLogoPath = :path WHERE id = :id")
    suspend fun setUserLogo(id: Long, path: String?)

    @Query("UPDATE items SET userTmdbId = :tmdbId WHERE id = :id")
    suspend fun setUserTmdbId(id: Long, tmdbId: Int?)

    /** Clears user artwork after a new match, as it was chosen for the previous title. */
    @Query("UPDATE items SET userPosterPath = NULL, userBackdropPath = NULL, userLogoPath = NULL WHERE id = :id")
    suspend fun clearUserArtwork(id: Long)

    @Query(
        "SELECT posterPath FROM items WHERE posterPath LIKE 'file:%' " +
            "UNION SELECT backdropPath FROM items WHERE backdropPath LIKE 'file:%' " +
            "UNION SELECT logoPath FROM items WHERE logoPath LIKE 'file:%' " +
            "UNION SELECT stillPath FROM items WHERE stillPath LIKE 'file:%' " +
            "UNION SELECT userPosterPath FROM items WHERE userPosterPath LIKE 'file:%' " +
            "UNION SELECT userBackdropPath FROM items WHERE userBackdropPath LIKE 'file:%' " +
            "UNION SELECT userLogoPath FROM items WHERE userLogoPath LIKE 'file:%' " +
            "UNION SELECT profilePath FROM credits WHERE profilePath LIKE 'file:%' " +
            // Chosen for a title whose files left, which takes them again once back.
            "UNION SELECT userPosterPath FROM kept_states WHERE userPosterPath LIKE 'file:%' " +
            "UNION SELECT userBackdropPath FROM kept_states WHERE userBackdropPath LIKE 'file:%' " +
            "UNION SELECT userLogoPath FROM kept_states WHERE userLogoPath LIKE 'file:%' " +
            // Chosen for a saga from its films' (#48), kept by its key whatever becomes of the film that showed it.
            "UNION SELECT userPosterPath FROM saga_choices WHERE userPosterPath LIKE 'file:%' " +
            "UNION SELECT userBackdropPath FROM saga_choices WHERE userBackdropPath LIKE 'file:%'",
    )
    suspend fun cachedArtworkPaths(): List<String>

    @Query("DELETE FROM credits WHERE itemId NOT IN (SELECT id FROM items)")
    suspend fun deleteOrphanCredits(): Int

    /** Deletes the local titles' choices of tracks no film or show keys any more ([tracksKey]). */
    @Query("DELETE FROM track_choices WHERE title LIKE 'local:%' AND title NOT IN ($TRACK_KEYS)")
    suspend fun deleteOrphanTrackChoices(): Int

    /** Deletes search words of deleted titles and sagas, and of person keys no credit uses any more. */
    @Query(
        "DELETE FROM search_words WHERE (kind = 'TITLE' AND ref NOT IN (SELECT CAST(id AS TEXT) FROM items)) " +
            "OR (kind = 'PERSON' AND ref NOT IN (SELECT personKey FROM credits)) " +
            "OR (kind = 'SAGA' AND ref NOT IN (SELECT CAST(id AS TEXT) FROM collections))",
    )
    suspend fun deleteOrphanSearchWords(): Int

    /**
     * Groups copies ([ItemEntity.versionOf]) by TMDB id under the oldest row, which keeps favourite, resume point and
     * "recently added" position. Writes only changed rows and returns their count.
     */
    @Query(GROUP_VERSIONS)
    suspend fun groupVersions(): Int

    /** Films and shows whose sort title starts with [prefix] ([upper] from [SearchWords.upperBound]), in sort order. */
    @Query(
        "SELECT $CARD_ROW FROM items WHERE kind IN ('MOVIE', 'SERIES') AND versionOf IS NULL " +
            "AND sortTitle >= :prefix AND sortTitle < :upper ORDER BY sortTitle, id LIMIT :limit",
    )
    suspend fun titlesStarting(prefix: String, upper: String, limit: Int): List<ItemEntity>

    /** Best rated titles of [kind] with at least [minVotes] votes, or an unknown vote count. */
    @Query(
        "SELECT $CARD_ROW FROM items WHERE kind = :kind AND versionOf IS NULL AND rating IS NOT NULL " +
            "AND (votes IS NULL OR votes >= :minVotes) ORDER BY rating DESC, sortTitle, id LIMIT :limit",
    )
    suspend fun bestRated(kind: ItemKind, minVotes: Int, limit: Int): List<ItemEntity>

    @Query(
        "SELECT id, kind, versionOf, seriesId, badges, filePath, parts, fileSize, fileDurationMs, durationMs, runtimeMin " +
            "FROM items WHERE kind IN ('MOVIE', 'EPISODE')",
    )
    suspend fun fileFacts(): List<FileFacts>

    /** Distinct path endings of [kind]'s files, enough to tell their file types without loading every path. */
    @Query(
        "SELECT DISTINCT lower(substr(filePath, -5)) AS tail, filePath LIKE '%BDMV/STREAM/%' AS bluRay, " +
            "filePath LIKE '%VIDEO_TS/%' AS dvd FROM items WHERE kind = :kind AND filePath IS NOT NULL",
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

    /** Inserts a collection ([insertCollectionRow]) and indexes its search words. */
    @Transaction
    suspend fun insertCollection(collection: CollectionEntity): Long =
        insertCollectionRow(collection).also { indexSaga(it, collection.name) }

    /** Updates a collection ([updateCollectionRow]) and reindexes its search words. */
    @Transaction
    suspend fun updateCollection(collection: CollectionEntity) {
        updateCollectionRow(collection)
        indexSaga(collection.id, collection.name)
    }

    @Transaction
    suspend fun indexSaga(id: Long, name: String) {
        deleteSearchWords(SearchKind.SAGA, id.toString())
        insertSearchWords(SearchWords.rows(SearchKind.SAGA, id.toString(), name))
    }

    @Query(
        "SELECT $CARD_ROW FROM items WHERE collectionId = :collectionId AND kind = 'MOVIE' AND versionOf IS NULL " +
            "ORDER BY year IS NULL, year, sortTitle",
    )
    suspend fun collectionFilms(collectionId: Long): List<ItemEntity>

    /**
     * Card columns of the films of [collectionIds], by collection in release order: one query per page. A film is watched
     * as soon as any copy is, as on its walls ([LocalMapper.watchedAsAny]) and in [startedSagas].
     */
    @Query(
        "SELECT collectionId, year, rating, genres, " +
            "(played = 1 OR EXISTS (SELECT 1 FROM items c WHERE c.versionOf = items.id AND c.played = 1)) AS played, " +
            "posterPath, userPosterPath, backdropPath, userBackdropPath, " +
            "CASE WHEN addedAt > (SELECT firstScanAt FROM sources WHERE sources.id = items.sourceId AND firstScanAt > 0) " +
            "THEN addedAt END AS addedLater " +
            "FROM items WHERE kind = 'MOVIE' AND versionOf IS NULL AND collectionId IN (:collectionIds) " +
            "ORDER BY collectionId, year IS NULL, year, sortTitle, id",
    )
    suspend fun collectionMembers(collectionIds: List<Long>): List<CollectionMember>

    @Query(
        "SELECT COUNT(*) FROM (SELECT collectionId FROM items WHERE kind = 'MOVIE' AND collectionId IS NOT NULL " +
            "AND versionOf IS NULL GROUP BY collectionId HAVING COUNT(*) >= :minFilms)",
    )
    suspend fun collectionCount(minFilms: Int): Int

    /** A wall's rows in order, films and sagas ([SagaWall.page]). */
    @RawQuery
    suspend fun wallRows(query: SupportSQLiteQuery): List<WallRow>

    /** How many titles a wall holds, sagas among them ([SagaWall.count]). */
    @RawQuery
    suspend fun wallCount(query: SupportSQLiteQuery): WallCount

    @Query("DELETE FROM collections WHERE id NOT IN (SELECT collectionId FROM items WHERE collectionId IS NOT NULL)")
    suspend fun deleteEmptyCollectionRows()

    @Query("DELETE FROM saga_parts WHERE collectionId NOT IN (SELECT id FROM collections)")
    suspend fun deleteOrphanSagaParts()

    /** Deletes collections without films, and their saga parts. */
    @Transaction
    suspend fun deleteEmptyCollections() {
        deleteEmptyCollectionRows()
        deleteOrphanSagaParts()
    }

    @Query("SELECT * FROM collections WHERE id IN (:ids)")
    suspend fun collections(ids: List<Long>): List<CollectionEntity>

    /** Up to [limit] collections with a TMDB id whose parts ([SagaPartEntity]) were never fetched. */
    @Query("SELECT * FROM collections WHERE tmdbId IS NOT NULL AND fetchedAt IS NULL ORDER BY id LIMIT :limit")
    suspend fun sagasDue(limit: Int): List<CollectionEntity>

    // Credits, where they reach the titles or the search words (the credits table alone is CreditDao's)

    @Query("UPDATE items SET creditsAt = :at WHERE id = :id")
    suspend fun setCreditsAt(id: Long, at: Long)

    /**
     * Replaces [itemId]'s credits: all when [whole], else only the jobs in [credits]. [at] marks them complete (null:
     * NFO only, for a title TMDB knows). Name keys are unified with TMDB keys library-wide; search words are indexed.
     */
    @Transaction
    suspend fun replaceCredits(itemId: Long, credits: List<CreditEntity>, at: Long?, whole: Boolean = true) {
        val named = credits.filter { it.personKey.startsWith(PersonKeys.NAME) }.map { it.foldedName }.distinct()
        val tmdbKeys = named.chunked(SQL_IN_LIMIT).flatMap { tmdbKeysOf(it) }.associate { it.foldedName to it.personKey }
        val keyed = credits.map { c ->
            if (c.personKey.startsWith(PersonKeys.NAME)) tmdbKeys[c.foldedName]?.let { c.copy(personKey = it) } ?: c else c
        }
        val byName = keyed.filter { it.personKey.startsWith(PersonKeys.TMDB) }.associate { PersonKeys.name(it.foldedName) to it.personKey }
        byName.keys.toList().chunked(SQL_IN_LIMIT).flatMap { heldKeys(it) }.forEach { rekeyPerson(it, byName.getValue(it)) }
        if (whole) deleteCredits(itemId) else deleteCreditsIn(itemId, credits.map { it.job.name }.distinct())
        insertCredits(keyed)
        insertSearchWords(keyed.distinctBy { it.personKey }.flatMap { SearchWords.rows(SearchKind.PERSON, it.personKey, it.name) })
        at?.let { setCreditsAt(itemId, it) }
    }

    /** Saves credits: all if [tmdbAnswered], else by job; [complete] makes the backfill skip the title. */
    @Transaction
    suspend fun saveCredits(itemId: Long, credits: List<CreditEntity>?, tmdbAnswered: Boolean, complete: Boolean, at: Long) {
        if (credits != null) {
            replaceCredits(itemId, credits, at.takeIf { complete }, whole = tmdbAnswered)
        } else if (complete) {
            setCreditsAt(itemId, at)
        }
    }

    /**
     * Up to [limit] films and shows never credit-completed ([ItemEntity.creditsAt]), i.e. pre-v12 rows.
     * Rows with a TMDB id come only when [withTmdb] and matched; unmatched ones wait for the scan that matches them.
     */
    @Query(
        "SELECT * FROM items WHERE kind IN ('MOVIE', 'SERIES') AND creditsAt IS NULL " +
            "AND (tmdbId IS NULL OR (:withTmdb AND matchedAt IS NOT NULL)) ORDER BY id LIMIT :limit",
    )
    suspend fun creditsDue(withTmdb: Boolean, limit: Int): List<ItemEntity>

    /** Writes only the backfill's votes, edition and credits time, never user state. */
    @Query("UPDATE items SET votes = :votes, edition = :edition, creditsAt = :at WHERE id = :id")
    suspend fun setBackfilled(id: Long, votes: Int?, edition: String?, at: Long)
}
