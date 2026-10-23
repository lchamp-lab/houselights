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

/** Max ids bound per statement: Android 9's SQLite (3.22) allows 999 variables; Robolectric more, so tests check it. */
internal const val SQL_IN_LIMIT = 500

/** Watched or not, back to the start; unwatched also forgets when it was played, as Jellyfin does, so it leaves À suivre. */
private const val SET_PLAYED =
    "UPDATE items SET played = :played, positionMs = 0, lastPlayedAt = CASE WHEN :played THEN :now ELSE NULL END"

/** Row :id plus, for a film, its other copies ([ItemEntity.versionOf]), which share watched and favourite state. */
private const val COPIES =
    "(id = :id OR id = (SELECT versionOf FROM items WHERE id = :id) " +
        "OR versionOf = (SELECT COALESCE(versionOf, id) FROM items WHERE id = :id AND kind = 'MOVIE'))"

/** Main row of a film's copies ([LibraryDao.groupVersions]): the lowest id with its TMDB id. */
private const val TITLE_OF_COPY = "NULLIF((SELECT MIN(o.id) FROM items o WHERE o.kind = 'MOVIE' AND o.tmdbId = items.tmdbId), id)"

/** Groups each film's copies, writing only rows whose versionOf changes (see [LibraryDao.groupVersions]). */
internal const val GROUP_VERSIONS = "UPDATE items SET versionOf = $TITLE_OF_COPY WHERE kind = 'MOVIE' AND versionOf IS NOT $TITLE_OF_COPY"

/** The keys of the local films' and shows' choices of tracks ([tracksKey]). */
private const val TRACK_KEYS =
    "SELECT CASE WHEN kind = 'MOVIE' AND tmdbId IS NOT NULL THEN 'local:film:' || tmdbId ELSE 'local:' || id END " +
        "FROM items WHERE kind IN ('MOVIE', 'SERIES')"

/** Number of titles crediting c's person, counting a multi-copy film once (see [LibraryDao.creditsOf]). */
private const val TITLES_OF_PERSON =
    "(SELECT COUNT(DISTINCT o.itemId) FROM credits o JOIN items oi ON oi.id = o.itemId " +
        "WHERE o.personKey = c.personKey AND oi.versionOf IS NULL)"

private const val PERSON_SUMMARY =
    "SELECT c.personKey, MIN(c.name) AS name, MAX(c.profilePath) AS profilePath, COUNT(DISTINCT c.itemId) AS titles " +
        "FROM credits c JOIN items i ON i.id = c.itemId"
/** A source password as stored: sealed, or plain text not yet sealed ([LibraryDatabase.sealPlainPasswords]). */
data class StoredPassword(val id: Long, val password: String) {
    override fun toString() = "StoredPassword($id, ***)"
}

/** Counts shown on a source's settings showcase: titles (films and shows), Ultra HD files, shows and their episodes. */
data class FolderCounts(val sourceId: Long, val titles: Int, val ultraHd: Int, val shows: Int = 0, val episodes: Int = 0)

/** A credit of a title, with the number of library titles crediting that person ([LibraryDao.creditsOf]). */
data class TitleCredit(
    val personKey: String,
    val name: String,
    val job: Job,
    val ord: Int,
    val character: String?,
    val profilePath: String?,
    val titles: Int,
)

data class PersonCredit(val itemId: Long, val job: Job, val ord: Int, val name: String, val character: String?, val profilePath: String?)

data class PersonSummary(val personKey: String, val name: String, val profilePath: String?, val titles: Int)

/** A person's TMDB key for a folded name ([LibraryDao.tmdbKeysOf]). */
data class NamedKey(val foldedName: String, val personKey: String)

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

    /** Updates address and credentials in place, keeping id, kind and scan dates, so titles keep their user state. */
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

    /** Raw stored passwords, sealed or not, read without unsealing ([LibraryDatabase.sealPlainPasswords]). */
    @Query("SELECT id, password FROM sources WHERE password != ''")
    suspend fun storedPasswords(): List<StoredPassword>

    /** Replaces the [plain] password with [sealed], unless the source was saved since it was read. */
    @Query("UPDATE sources SET password = :sealed WHERE id = :id AND password = :plain")
    suspend fun sealPassword(id: Long, plain: String, sealed: String)

    /** Writes only the scan columns: the row may have changed meanwhile, and rewriting it would reseal the password. */
    @Query("UPDATE sources SET lastScanAt = :at, firstScanAt = CASE WHEN firstScanAt = 0 THEN :at ELSE firstScanAt END WHERE id = :id")
    suspend fun markScanned(id: Long, at: Long)

    /** Asks every folder to be read again whole in [language] ([SourceEntity.refreshIn]). */
    @Query("UPDATE sources SET refreshIn = :language")
    suspend fun refreshAllIn(language: String)

    /** Clears [id]'s request once read whole in [language], unless another language was asked meanwhile. */
    @Query("UPDATE sources SET refreshIn = NULL WHERE id = :id AND refreshIn = :language")
    suspend fun refreshedIn(id: Long, language: String)

    // User changes are written column by column so they never overwrite a scan's work.
    // Watched state is set by parent, not by id list: a long show has thousands of episodes.

    /** Applies to all copies of a film ([COPIES]). */
    @Query("$SET_PLAYED WHERE $COPIES")
    suspend fun setPlayed(id: Long, played: Boolean, now: Long)

    /** Marks the other copies of film [id] watched, keeping their resume points, after one copy was finished. */
    @Query("UPDATE items SET played = 1 WHERE played = 0 AND $COPIES")
    suspend fun setCopiesPlayed(id: Long)

    @Query("$SET_PLAYED WHERE kind = 'EPISODE' AND seriesId = :seriesId")
    suspend fun setSeriesPlayed(seriesId: Long, played: Boolean, now: Long)

    @Query("$SET_PLAYED WHERE kind = 'EPISODE' AND parentId = :seasonId")
    suspend fun setSeasonPlayed(seasonId: Long, played: Boolean, now: Long)

    @Query("$SET_PLAYED WHERE kind = 'MOVIE' AND collectionId = :collectionId")
    suspend fun setCollectionPlayed(collectionId: Long, played: Boolean, now: Long)

    /** Applies to all copies of a film ([COPIES]). */
    @Query("UPDATE items SET favorite = :favorite WHERE $COPIES")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    /** Sets the copy to play on main row [id] ([ItemEntity.versionChoice]); null plays the best copy. */
    @Query("UPDATE items SET versionChoice = :choice WHERE id = :id")
    suspend fun setVersionChoice(id: Long, choice: Long?)

    /** Writes only lastPlayedAt, since a late report from the previous session may update the rest meanwhile. */
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

    /** Columns needed by playback progress reports, read every ten seconds during playback. */
    @Query("SELECT parts, positionMs, durationMs, fileDurationMs, runtimeMin, played FROM items WHERE id = :id")
    suspend fun playState(id: Long): PlayState?

    /** A source's file rows without NFO text; used by tests to inspect a scan's result. */
    @Query("SELECT $ITEM_ROW FROM items WHERE sourceId = :sourceId AND filePath IS NOT NULL")
    suspend fun filesOf(sourceId: Long): List<ItemEntity>

    @Query(
        "SELECT id, filePath, parts, fileSize, missingSince, seriesId, tmdbId, userTmdbId, matchedAt, sidecarKey, probeVersion " +
            "FROM items WHERE sourceId = :sourceId AND filePath IS NOT NULL"
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
            "AND filePath > :dir || '/' AND filePath < :dir || '0'"
    )
    suspend fun episodesUnder(sourceId: Long, dir: String): List<ItemEntity>

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

    /** Unwatched episodes and seasons per show, for a whole page in one query. */
    @Query(
        "SELECT seriesId, SUM(played = 0) AS unplayed, COUNT(DISTINCT parentId) AS seasons FROM items " +
            "WHERE kind = 'EPISODE' AND seriesId IN (:seriesIds) GROUP BY seriesId"
    )
    suspend fun seriesCounts(seriesIds: List<Long>): List<SeriesCounts>

    /** Episode and watched counts per season, in one query for the season track. */
    @Query(
        "SELECT parentId AS seasonId, COUNT(*) AS episodes, SUM(played) AS played FROM items " +
            "WHERE kind = 'EPISODE' AND seriesId = :seriesId AND parentId IS NOT NULL GROUP BY parentId"
    )
    suspend fun seasonCounts(seriesId: Long): List<SeasonCounts>

    @Query(
        "SELECT seriesId AS id, MAX(lastPlayedAt) AS at FROM items WHERE kind = 'EPISODE' AND seriesId IS NOT NULL " +
            "AND lastPlayedAt IS NOT NULL GROUP BY seriesId ORDER BY MAX(lastPlayedAt) DESC LIMIT :limit"
    )
    suspend fun recentSeries(limit: Int): List<Recent>

    /** Films of the [limit] most recently watched sagas, by saga in release order, with copies' state merged. */
    @Query(
        "SELECT id, collectionId, (played = 1 OR EXISTS (SELECT 1 FROM items c WHERE c.versionOf = items.id AND c.played = 1)) " +
            "AS played, (SELECT MAX(c.positionMs) FROM items c WHERE c.id = items.id OR c.versionOf = items.id) AS positionMs, " +
            "(SELECT MAX(c.lastPlayedAt) FROM items c WHERE c.id = items.id OR c.versionOf = items.id) AS lastPlayedAt " +
            "FROM items WHERE kind = 'MOVIE' AND versionOf IS NULL " +
            "AND collectionId IN (SELECT collectionId FROM items WHERE kind = 'MOVIE' AND collectionId IS NOT NULL AND played = 1 " +
            "AND lastPlayedAt IS NOT NULL GROUP BY collectionId ORDER BY MAX(lastPlayedAt) DESC LIMIT :limit) " +
            "ORDER BY collectionId, year IS NULL, year, sortTitle, id"
    )
    suspend fun startedSagas(limit: Int): List<SagaFilmState>

    @Query("SELECT $CARD_ROW FROM items WHERE seriesId = :seriesId AND kind = 'EPISODE' ORDER BY seasonNumber, episodeNumber")
    suspend fun episodesOfSeries(seriesId: Long): List<ItemEntity>

    /** Watch state of every episode of [seriesIds], in order: finds next episodes without loading full rows. */
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

    /** Most recently added titles, excluding secondary copies of films ([ItemEntity.versionOf]). */
    @Query("SELECT $CARD_ROW FROM items WHERE kind = :kind AND versionOf IS NULL ORDER BY addedAt DESC, id DESC LIMIT :limit")
    suspend fun latest(kind: ItemKind, limit: Int): List<ItemEntity>

    @Query("SELECT COUNT(*) FROM items WHERE kind = :kind")
    suspend fun count(kind: ItemKind): Int

    /** Each title's genres, with the lower() form the genre filter compares against ([PipeList.distinctGenres]). */
    @Query("SELECT genres, lower(genres) AS folded FROM items WHERE kind = :kind AND genres != ''")
    suspend fun genresOf(kind: ItemKind): List<GenreList>

    @Query("SELECT DISTINCT year FROM items WHERE kind = :kind AND year IS NOT NULL")
    suspend fun yearsOf(kind: ItemKind): List<Int>

    @Query("SELECT DISTINCT badges FROM items WHERE kind = :kind AND badges != ''")
    suspend fun badgesOf(kind: ItemKind): List<String>

    @Query("SELECT COUNT(*) FROM items WHERE sourceId = :sourceId AND kind IN ('MOVIE','SERIES')")
    fun observeTitleCount(sourceId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM items WHERE kind IN ('MOVIE','SERIES')")
    fun observeAllTitleCount(): Flow<Int>

    @Query(
        "SELECT COALESCE(NULLIF(userPosterPath, ''), posterPath) FROM items " +
            "WHERE sourceId = :sourceId AND kind IN ('MOVIE','SERIES') " +
            "AND COALESCE(NULLIF(userPosterPath, ''), posterPath) IS NOT NULL ORDER BY addedAt DESC LIMIT :limit"
    )
    fun observePosters(sourceId: Long, limit: Int): Flow<List<String>>

    @Query(
        "SELECT COALESCE(NULLIF(userPosterPath, ''), posterPath) FROM items " +
            "WHERE kind IN ('MOVIE','SERIES') " +
            "AND COALESCE(NULLIF(userPosterPath, ''), posterPath) IS NOT NULL ORDER BY addedAt DESC LIMIT :limit"
    )
    fun observeAllPosters(limit: Int): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM items WHERE sourceId = :sourceId AND filePath IS NOT NULL AND ${PipeList.BADGES_MATCH}")
    fun observeDefinitionCount(sourceId: Long, token: String): Flow<Int>

    /** The counts above, and shows and episodes, for every source in one query, as each Flow query reruns on every write to items. */
    @Query(
        "SELECT sourceId, SUM(kind IN ('MOVIE','SERIES')) AS titles, " +
            "SUM(filePath IS NOT NULL AND ${PipeList.BADGES_MATCH}) AS ultraHd, " +
            "SUM(kind = 'SERIES') AS shows, SUM(kind = 'EPISODE') AS episodes " +
            "FROM items GROUP BY sourceId"
    )
    fun observeFolderCounts(token: String): Flow<List<FolderCounts>>

    @RawQuery
    suspend fun query(query: SupportSQLiteQuery): List<ItemEntity>

    @RawQuery
    suspend fun countQuery(query: SupportSQLiteQuery): Int

    /** Titles of [kind] with [genre], best rated first; ties sort by id so the row order is stable. */
    @Query(
        "SELECT $CARD_ROW FROM items WHERE kind = :kind AND id != :excludeId AND versionOf IS NULL AND ${PipeList.GENRES_MATCH} " +
            "ORDER BY rating DESC, id LIMIT :limit"
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
            "fileDurationMs = CASE WHEN fileSize = :fileSize THEN fileDurationMs ELSE NULL END, missingSince = NULL WHERE id = :id"
    )
    suspend fun relink(id: Long, filePath: String, fileSize: Long)

    /** Sets [ItemEntity.parts]; a film gaining parts is rematched, as it was matched by its first part ("Film CD1"). */
    @Query(
        "UPDATE items SET parts = :parts, " +
            "matchedAt = CASE WHEN parts = '' AND :parts != '' THEN NULL ELSE matchedAt END WHERE id = :id"
    )
    suspend fun setParts(id: Long, parts: String)

    @Query("UPDATE items SET missingSince = :since WHERE id IN (:ids)")
    suspend fun setMissingSinceBatch(ids: List<Long>, since: Long?)

    /** Sets [since] (first missed, see [MissingFiles]; null once back) on any number of rows, atomically. */
    @Transaction
    suspend fun setMissingSince(ids: List<Long>, since: Long?) =
        ids.chunked(SQL_IN_LIMIT).forEach { setMissingSinceBatch(it, since) }

    /** Applies [MissingFiles] decisions in one transaction, as a library rename touches thousands of rows. */
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
        // If a film's main row was removed, its next copy becomes the main row.
        if (removed.isNotEmpty()) groupVersions()
    }

    @Query("DELETE FROM items WHERE kind = 'SEASON' AND id NOT IN (SELECT parentId FROM items WHERE kind = 'EPISODE' AND parentId IS NOT NULL)")
    suspend fun deleteEmptySeasons()

    @Query("DELETE FROM items WHERE kind = 'SERIES' AND id NOT IN (SELECT parentId FROM items WHERE kind = 'SEASON' AND parentId IS NOT NULL)")
    suspend fun deleteEmptySeries()

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
            "AND EXISTS (SELECT 1 FROM items e WHERE e.kind = 'EPISODE' AND (e.seriesId = items.id OR e.parentId = items.id))"
    )
    suspend fun moveOrphanShows()

    /** Removes a source and its items atomically. */
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

    /** Runs [writes] atomically, e.g. an identification touching a title, its seasons and episodes. */
    @Transaction
    suspend fun inTransaction(writes: suspend () -> Unit) = writes()

    /**
     * Saves scan results, keeping columns the scan does not own, which may change meanwhile: user artwork and match,
     * favourite, watched state, resume point, chosen copy, versionOf, creditsAt. Reindexes search words on rename; a
     * film matched anew takes its choice of tracks to its new key.
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
            "SELECT :to, audio, subtitles, at FROM track_choices WHERE title = :from"
    )
    suspend fun copyTrackChoice(from: String, to: String)

    @Query("DELETE FROM track_choices WHERE title = :key AND :key NOT IN ($TRACK_KEYS)")
    suspend fun forgetTrackChoiceUnkeyed(key: String)

    /** Copies favourite, user match and artwork from [fromId] where [toId] lacks them ([ShowIndexer]). */
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
            "UNION SELECT profilePath FROM credits WHERE profilePath LIKE 'file:%'"
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
            "OR (kind = 'SAGA' AND ref NOT IN (SELECT CAST(id AS TEXT) FROM collections))"
    )
    suspend fun deleteOrphanSearchWords(): Int

    /**
     * Groups copies ([ItemEntity.versionOf]) by TMDB id under the oldest row, which keeps favourite, resume point and
     * "recently added" position. Writes only changed rows and returns their count.
     */
    @Query(GROUP_VERSIONS)
    suspend fun groupVersions(): Int

    /** Copies of the film with main row [titleId], that row first, without NFO text. */
    @Query("SELECT $ITEM_ROW FROM items WHERE kind = 'MOVIE' AND (id = :titleId OR versionOf = :titleId) ORDER BY id")
    suspend fun copies(titleId: Long): List<ItemEntity>

    /** Films and shows whose sort title starts with [prefix] ([upper] from [SearchWords.upperBound]), in sort order. */
    @Query(
        "SELECT $CARD_ROW FROM items WHERE kind IN ('MOVIE', 'SERIES') AND versionOf IS NULL " +
            "AND sortTitle >= :prefix AND sortTitle < :upper ORDER BY sortTitle, id LIMIT :limit"
    )
    suspend fun titlesStarting(prefix: String, upper: String, limit: Int): List<ItemEntity>

    /** Best rated titles of [kind] with at least [minVotes] votes, or an unknown vote count. */
    @Query(
        "SELECT $CARD_ROW FROM items WHERE kind = :kind AND versionOf IS NULL AND rating IS NOT NULL " +
            "AND (votes IS NULL OR votes >= :minVotes) ORDER BY rating DESC, sortTitle, id LIMIT :limit"
    )
    suspend fun bestRated(kind: ItemKind, minVotes: Int, limit: Int): List<ItemEntity>

    @Query(
        "SELECT id, kind, versionOf, seriesId, badges, filePath, parts, fileSize, fileDurationMs, durationMs, runtimeMin " +
            "FROM items WHERE kind IN ('MOVIE', 'EPISODE')"
    )
    suspend fun fileFacts(): List<FileFacts>

    /** Distinct path endings of [kind]'s files, enough to tell their file types without loading every path. */
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
            "ORDER BY year IS NULL, year, sortTitle"
    )
    suspend fun collectionFilms(collectionId: Long): List<ItemEntity>

    /** Card columns of the films of [collectionIds], by collection in release order: one query per page. */
    @Query(
        "SELECT collectionId, year, rating, genres, played, posterPath, userPosterPath, backdropPath, userBackdropPath " +
            "FROM items WHERE kind = 'MOVIE' AND versionOf IS NULL AND collectionId IN (:collectionIds) " +
            "ORDER BY collectionId, year IS NULL, year, sortTitle, id"
    )
    suspend fun collectionMembers(collectionIds: List<Long>): List<CollectionMember>

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

    // Saga parts

    @Query("DELETE FROM saga_parts WHERE collectionId = :collectionId")
    suspend fun deleteSagaParts(collectionId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSagaParts(parts: List<SagaPartEntity>)

    @Transaction
    suspend fun replaceSagaParts(collectionId: Long, parts: List<SagaPartEntity>) {
        deleteSagaParts(collectionId)
        insertSagaParts(parts)
    }

    /** TMDB parts of a saga in release order, each with the library's main row when present. */
    @Query(
        "SELECT p.*, (SELECT i.id FROM items i WHERE i.kind = 'MOVIE' AND i.tmdbId = p.tmdbId AND i.versionOf IS NULL " +
            "ORDER BY i.id LIMIT 1) AS itemId FROM saga_parts p WHERE p.collectionId = :collectionId ORDER BY p.ord"
    )
    suspend fun sagaParts(collectionId: Long): List<SagaPartRow>

    // Credits

    @Query("SELECT * FROM credits WHERE itemId = :itemId AND job = 'CAST' ORDER BY ord")
    suspend fun cast(itemId: Long): List<CreditEntity>

    /** Credits of title [itemId] by job in billing order, each with its person's title count. */
    @Query(
        "SELECT c.personKey, c.name, c.job, c.ord, c.character, c.profilePath, $TITLES_OF_PERSON AS titles " +
            "FROM credits c WHERE c.itemId = :itemId ORDER BY c.job, c.ord"
    )
    suspend fun creditsOf(itemId: Long): List<TitleCredit>

    @Query(
        "SELECT c.itemId, c.job, c.ord, c.name, c.character, c.profilePath FROM credits c JOIN items i ON i.id = c.itemId " +
            "WHERE c.personKey = :personKey AND i.versionOf IS NULL"
    )
    suspend fun creditsOfPerson(personKey: String): List<PersonCredit>

    /** People of [job] credited on at least [minTitles] titles of [kind] (any if null), most titles first. */
    @Query(
        "$PERSON_SUMMARY WHERE c.job = :job AND i.versionOf IS NULL AND (:kind IS NULL OR i.kind = :kind) " +
            "GROUP BY c.personKey HAVING COUNT(DISTINCT c.itemId) >= :minTitles ORDER BY titles DESC, name LIMIT :limit"
    )
    suspend fun topPeople(job: Job, kind: ItemKind?, minTitles: Int, limit: Int): List<PersonSummary>

    @Query("$PERSON_SUMMARY WHERE c.personKey IN (:personKeys) AND i.versionOf IS NULL GROUP BY c.personKey")
    suspend fun people(personKeys: List<String>): List<PersonSummary>

    @Query("DELETE FROM credits WHERE itemId = :itemId")
    suspend fun deleteCredits(itemId: Long)

    /** Deletes the credits of [itemId] for [jobs] (enum names). */
    @Query("DELETE FROM credits WHERE itemId = :itemId AND job IN (:jobs)")
    suspend fun deleteCreditsIn(itemId: Long, jobs: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCredits(credits: List<CreditEntity>)

    @Query("SELECT DISTINCT personKey FROM credits WHERE personKey IN (:personKeys)")
    suspend fun heldKeys(personKeys: List<String>): List<String>

    /** TMDB keys already used in credits for [foldedNames]. */
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
            "AND (tmdbId IS NULL OR (:withTmdb AND matchedAt IS NOT NULL)) ORDER BY id LIMIT :limit"
    )
    suspend fun creditsDue(withTmdb: Boolean, limit: Int): List<ItemEntity>

    /** Writes only the backfill's votes, edition and credits time, never user state. */
    @Query("UPDATE items SET votes = :votes, edition = :edition, creditsAt = :at WHERE id = :id")
    suspend fun setBackfilled(id: Long, votes: Int?, edition: String?, at: Long)

    // Search

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSearchWords(words: List<SearchWordEntity>)

    @Query("DELETE FROM search_words WHERE kind = :kind AND ref = :ref")
    suspend fun deleteSearchWords(kind: SearchKind, ref: String)

    /** Runs the search query built by [LocalCatalog]: one primary-key range per word and kind. */
    @RawQuery
    suspend fun searchRefs(query: SupportSQLiteQuery): List<SearchRef>
}
