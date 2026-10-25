package app.houselights.providers.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/** Entries kept at most, the oldest kept first to go ([KeptStateDao.trimKept]): far more titles than a library loses. */
const val KEPT_MAX = 10_000

/**
 * What the user did to a title whose files left the library, kept by title rather than by row ([KeptStates]), so a
 * missing file's row can go at once and the title, back, takes it again. [title] names it ([KeptKeys]): a film by its
 * TMDB id, else its folder, name and year; a show by its TMDB id, else its key; an episode by its show's, its season
 * and its episodes. Applied once, then forgotten; forgotten too with its folder ([LibraryDao.removeSource]).
 */
@Entity(tableName = "kept_states", indices = [Index("nameKey"), Index("sourceId"), Index("showTmdbId")])
data class KeptStateEntity(
    @PrimaryKey val title: String,
    val kind: ItemKind,
    /** The name a title born without a TMDB id finds it by ([KeptKeys.filmName], a show's key, an episode's). */
    val nameKey: String,
    /** A film's or show's TMDB id (the user's match first); null for an episode. */
    val tmdbId: Int?,
    /** An episode's show's TMDB id, by which a show matched since gives back its episodes ([LibraryDao.giveBackShow]). */
    val showTmdbId: Int?,
    val sourceId: Long,
    /** A film's folder, where a match by hand is given back ([KeptStates.atBirth]); null for shows and episodes. */
    val folder: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val lastEpisodeNumber: Int?,
    val played: Boolean,
    val positionMs: Long,
    val durationMs: Long?,
    val lastPlayedAt: Long?,
    val favorite: Boolean,
    val userTmdbId: Int?,
    val userPosterPath: String?,
    val userBackdropPath: String?,
    val userLogoPath: String?,
    val addedAt: Long,
    /** The title's choice of tracks ([TrackChoiceEntity]); [tracksAt] is null without one. */
    val audio: String?,
    val subtitles: String?,
    val tracksAt: Long?,
) {
    /** The TMDB id naming the title: a film's or show's, an episode's show's. */
    val identity: Int? get() = if (kind == ItemKind.EPISODE) showTmdbId else tmdbId

    /** Anything the user did: an arrival alone is not worth an entry. */
    val holdsAnything: Boolean
        get() = played || positionMs > 0 || favorite || userTmdbId != null || userPosterPath != null ||
            userBackdropPath != null || userLogoPath != null || tracksAt != null
}

/** A row's columns that are the user's, and its arrival; written alone ([KeptStateDao.restoreState]). */
data class UserState(
    val id: Long,
    val played: Boolean,
    val positionMs: Long,
    val durationMs: Long?,
    val lastPlayedAt: Long?,
    val favorite: Boolean,
    val userTmdbId: Int?,
    val userPosterPath: String?,
    val userBackdropPath: String?,
    val userLogoPath: String?,
    val addedAt: Long,
)

internal fun ItemEntity.userState() = UserState(
    id, played, positionMs, durationMs, lastPlayedAt, favorite, userTmdbId, userPosterPath, userBackdropPath, userLogoPath, addedAt,
)

internal fun ItemEntity.withUserState(state: UserState) = copy(
    played = state.played,
    positionMs = state.positionMs,
    durationMs = state.durationMs,
    lastPlayedAt = state.lastPlayedAt,
    favorite = state.favorite,
    userTmdbId = state.userTmdbId,
    userPosterPath = state.userPosterPath,
    userBackdropPath = state.userBackdropPath,
    userLogoPath = state.userLogoPath,
    addedAt = state.addedAt,
)

/** A row as [KeptStates] keeps and gives back state: what the user did, what names the title, its choice of tracks. */
data class KeptRow(
    @Embedded val state: UserState,
    val kind: ItemKind,
    val sourceId: Long,
    val seriesId: Long?,
    val filePath: String?,
    val parts: List<String>,
    val tmdbId: Int?,
    /** The user's match, else TMDB's. */
    val identity: Int?,
    val matchKey: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val lastEpisodeNumber: Int?,
    val versionOf: Long?,
    /** An episode's show's [identity] and key. */
    val showIdentity: Int?,
    val showKey: String?,
    val audio: String?,
    val subtitles: String?,
    val tracksAt: Long?,
) {
    val id: Long get() = state.id

    val tracksKey: String get() = tracksKey(id, kind, seriesId, tmdbId)
}

/** [KeptRow]'s columns: the row, its show's for an episode, and its choice of tracks ([tracksKey]). */
private const val KEPT_ROW =
    "SELECT i.id, i.played, i.positionMs, i.durationMs, i.lastPlayedAt, i.favorite, i.userTmdbId, i.userPosterPath, " +
        "i.userBackdropPath, i.userLogoPath, i.addedAt, i.kind, i.sourceId, i.seriesId, i.filePath, i.parts, i.tmdbId, " +
        "COALESCE(i.userTmdbId, i.tmdbId) AS identity, i.matchKey, i.seasonNumber, i.episodeNumber, i.lastEpisodeNumber, " +
        "i.versionOf, COALESCE(s.userTmdbId, s.tmdbId) AS showIdentity, s.matchKey AS showKey, " +
        "t.audio, t.subtitles, t.at AS tracksAt FROM items i " +
        "LEFT JOIN items s ON i.kind = 'EPISODE' AND s.id = i.seriesId " +
        "LEFT JOIN track_choices t ON i.kind IN ('MOVIE', 'SERIES') AND t.title = " +
        "CASE WHEN i.kind = 'MOVIE' AND i.tmdbId IS NOT NULL THEN 'local:film:' || i.tmdbId ELSE 'local:' || i.id END"

/** What the user did to titles whose files left, kept and given back ([KeptStates]; [LibraryDao] extends it). */
@Suppress("TooManyFunctions") // A list of queries, as LibraryDao is.
interface KeptStateDao {
    @Query("$KEPT_ROW WHERE i.id IN (:ids)")
    suspend fun keptRows(ids: List<Long>): List<KeptRow>

    /** Films or shows ([kind]) known by TMDB as one of [identities]. */
    @Query("$KEPT_ROW WHERE i.kind = :kind AND COALESCE(i.userTmdbId, i.tmdbId) IN (:identities)")
    suspend fun titlesKnownAs(kind: ItemKind, identities: List<Int>): List<KeptRow>

    /** Episodes of the shows known by TMDB as one of [shows]. */
    @Query(
        "$KEPT_ROW WHERE i.kind = 'EPISODE' AND i.seriesId IN " +
            "(SELECT id FROM items WHERE kind = 'SERIES' AND COALESCE(userTmdbId, tmdbId) IN (:shows))"
    )
    suspend fun episodesOfShowsKnownAs(shows: List<Int>): List<KeptRow>

    @Query("$KEPT_ROW WHERE i.kind = 'EPISODE' AND i.seriesId = :seriesId")
    suspend fun keptRowsOfSeries(seriesId: Long): List<KeptRow>

    /** Films at the root of folder [sourceId]. */
    @Query("$KEPT_ROW WHERE i.kind = 'MOVIE' AND i.sourceId = :sourceId AND instr(i.filePath, '/') = 0")
    suspend fun keptRowsAtRoot(sourceId: Long): List<KeptRow>

    /** Films under [dir] (never ""): paths between "dir/" and "dir0", a range scan on the index. */
    @Query("$KEPT_ROW WHERE i.kind = 'MOVIE' AND i.sourceId = :sourceId AND i.filePath > :dir || '/' AND i.filePath < :dir || '0'")
    suspend fun keptRowsUnder(sourceId: Long, dir: String): List<KeptRow>

    /** Films in [folder] of folder [sourceId] itself, not in its subfolders. */
    suspend fun keptRowsIn(sourceId: Long, folder: String): List<KeptRow> =
        (if (folder.isEmpty()) keptRowsAtRoot(sourceId) else keptRowsUnder(sourceId, folder))
            .filter { row -> row.filePath?.let(Sidecars::folderOf) == folder }

    @Query("SELECT * FROM kept_states WHERE title = :title")
    suspend fun keptState(title: String): KeptStateEntity?

    @Query("SELECT * FROM kept_states WHERE nameKey = :nameKey AND kind = :kind")
    suspend fun keptNamed(kind: ItemKind, nameKey: String): List<KeptStateEntity>

    @Query("SELECT * FROM kept_states WHERE kind = 'EPISODE' AND showTmdbId = :show")
    suspend fun keptEpisodesOf(show: Int): List<KeptStateEntity>

    /** Replaces an entry of the same title, which then counts as the newest ([trimKept]). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun keep(states: List<KeptStateEntity>)

    @Query("DELETE FROM kept_states WHERE title IN (:titles)")
    suspend fun forgetKeptBatch(titles: List<String>)

    /** Forgets entries [titles], [SQL_IN_LIMIT] per statement. */
    suspend fun forgetKept(titles: List<String>) = titles.chunked(SQL_IN_LIMIT).forEach { forgetKeptBatch(it) }

    /** Forgets what was kept of folder [sourceId]'s titles, as removing it forgets them ([LibraryDao.removeSource]). */
    @Query("DELETE FROM kept_states WHERE sourceId = :sourceId")
    suspend fun forgetKeptOf(sourceId: Long)

    /** Keeps the [max] newest entries, by when each was kept ([keep]); not by the wall clock, which can jump. */
    @Query("DELETE FROM kept_states WHERE rowid NOT IN (SELECT rowid FROM kept_states ORDER BY rowid DESC LIMIT :max)")
    suspend fun trimKept(max: Int)

    /** Gives [title] a choice of tracks unless it has its own. */
    @Query("INSERT OR IGNORE INTO track_choices (title, audio, subtitles, at) VALUES (:title, :audio, :subtitles, :at)")
    suspend fun offerTrackChoice(title: String, audio: String?, subtitles: String?, at: Long)

    @Update(entity = ItemEntity::class)
    suspend fun restoreState(state: UserState)

    /** Row [id] to be looked up again at the next pass. */
    @Query("UPDATE items SET matchedAt = NULL WHERE id = :id")
    suspend fun unmatch(id: Long)

    /**
     * Fills [row] with what [kept] holds that it lacks ([KeptStates.onto]), and offers it [kept]'s choice of tracks. A
     * match by hand it takes, not the one TMDB gave it, has it looked up by it at the next pass, as when a show hands
     * one over ([ShowIndexer]): scans look up only titles not matched yet.
     */
    suspend fun restore(row: KeptRow, kept: KeptStateEntity) {
        val state = KeptStates.onto(row.state, kept)
        restoreState(state)
        if (state.userTmdbId != row.state.userTmdbId && state.userTmdbId != row.tmdbId) unmatch(row.id)
        val at = kept.tracksAt ?: return
        offerTrackChoice(row.tracksKey, kept.audio, kept.subtitles, at)
    }
}

/**
 * The titles of kept states ([KeptStateEntity.title]) and the names they are found by ([KeptStateEntity.nameKey]).
 * Prefixed by kind and by key, so no film is ever taken for a show, nor a name for a TMDB id.
 */
internal object KeptKeys {
    fun film(identity: Int) = "film:tmdb:$identity"

    fun show(identity: Int) = "show:tmdb:$identity"

    fun episode(show: Int, span: String) = "ep:tmdb:$show:$span"

    /** An episode file's season and episodes ("1:1-2" for S01E01E02), from its name, as both sides are parsed alike. */
    fun span(season: Int?, episode: Int?, last: Int?): String? =
        if (season == null || episode == null) null else "$season:$episode-${last ?: episode}"

    fun episodeName(showKey: String, span: String) = "$showKey:$span"

    /**
     * A film's name key: title and year as relinks pair files ([MissingFiles.filmName]); without a year, in its folder
     * alone, as a title alone is often shared.
     */
    fun filmName(path: String, stacked: Boolean): String {
        val name = MissingFiles.filmName(path, stacked)
        return if (name.year != null) titled(name) else "${Sidecars.folderOf(path)}:${titled(name)}"
    }

    /** "dune|2021", or "home video" without a year. */
    private fun titled(name: ParsedMovie) = name.year?.let { "${name.title}${FilenameParser.YEAR_MARK}$it" } ?: name.title

    /** A film's title by its name in its folder ([of] without a TMDB id), whatever its TMDB id. */
    fun place(row: KeptRow): String? =
        row.filePath?.takeIf { row.kind == ItemKind.MOVIE }?.let { place(row.sourceId, it, row.parts.isNotEmpty()) }

    private fun place(sourceId: Long, path: String, stacked: Boolean) =
        "film:name:$sourceId:${Sidecars.folderOf(path)}:${titled(MissingFiles.filmName(path, stacked))}"

    /**
     * [row]'s title and name key. Without a TMDB id, a title is kept per folder: two films sharing a name and a year
     * elsewhere are two entries, which a row born under that name then cannot tell apart ([KeptStates.usable]).
     */
    fun of(row: KeptRow): Pair<String, String>? = when (row.kind) {
        ItemKind.MOVIE -> row.filePath?.let { path ->
            val stacked = row.parts.isNotEmpty()
            (row.identity?.let(::film) ?: place(row.sourceId, path, stacked)) to filmName(path, stacked)
        }
        ItemKind.SERIES -> row.matchKey?.let { key -> (row.identity?.let(::show) ?: "show:name:${row.sourceId}:$key") to key }
        ItemKind.EPISODE -> {
            val key = row.showKey
            val span = span(row.seasonNumber, row.episodeNumber, row.lastEpisodeNumber)
            if (key == null || span == null) {
                null
            } else {
                (row.showIdentity?.let { episode(it, span) } ?: "ep:name:${row.sourceId}:$key:$span") to episodeName(key, span)
            }
        }
        ItemKind.SEASON -> null
    }
}

/**
 * What the user did to a title, kept by title when its files leave and given back when it returns, as Plex and
 * Jellyfin keep it ([KeptStateEntity]). Kept: watched state, resume point, favourite, a match by hand, chosen artwork,
 * the choice of tracks and the arrival; never a copy's choice, a row id with no meaning for a new file. Given back:
 * - at birth by name, when TMDB cannot confirm the title otherwise ([atBirth]);
 * - once TMDB or the NFO names the title ([LibraryDao.giveBack]), its episodes with a show ([LibraryDao.giveBackShow]);
 * - by name in its folder once TMDB knows it by no id ([fromHere]).
 * A row staying with the same identity takes the state instead ([plan]), and a later removal replaces what an
 * earlier one kept, never merged with it, as unmarking or unfavouring leaves nothing to merge. Not covered: Sonarr
 * switching between absolute and seasonal numbering, and an episode file split in two or two joined.
 */
internal object KeptStates {
    /** [row]'s entry, if it has a title; it may hold nothing ([KeptStateEntity.holdsAnything]). */
    fun of(row: KeptRow): KeptStateEntity? {
        val (title, nameKey) = KeptKeys.of(row) ?: return null
        val episode = row.kind == ItemKind.EPISODE
        val state = row.state
        return KeptStateEntity(
            title = title, kind = row.kind, nameKey = nameKey,
            tmdbId = row.identity.takeUnless { episode }, showTmdbId = row.showIdentity.takeIf { episode },
            sourceId = row.sourceId, folder = row.filePath?.takeIf { row.kind == ItemKind.MOVIE }?.let(Sidecars::folderOf),
            seasonNumber = row.seasonNumber.takeIf { episode }, episodeNumber = row.episodeNumber.takeIf { episode },
            lastEpisodeNumber = row.lastEpisodeNumber.takeIf { episode },
            played = state.played, positionMs = state.positionMs, durationMs = state.durationMs, lastPlayedAt = state.lastPlayedAt,
            favorite = state.favorite, userTmdbId = state.userTmdbId, userPosterPath = state.userPosterPath,
            userBackdropPath = state.userBackdropPath, userLogoPath = state.userLogoPath, addedAt = state.addedAt,
            audio = row.audio, subtitles = row.subtitles, tracksAt = row.tracksAt,
        )
    }

    /**
     * Two copies of a title leaving together, [a] the main one: the latest played gives watched state and resume
     * point, either's favourite counts, [a]'s match and artwork first, the earliest arrival.
     */
    fun merge(a: KeptStateEntity, b: KeptStateEntity): KeptStateEntity {
        val play = if ((b.lastPlayedAt ?: Long.MIN_VALUE) > (a.lastPlayedAt ?: Long.MIN_VALUE)) b else a
        val tracks = if (a.tracksAt != null) a else b
        return a.copy(
            played = play.played, positionMs = play.positionMs, durationMs = play.durationMs, lastPlayedAt = play.lastPlayedAt,
            favorite = a.favorite || b.favorite, userTmdbId = a.userTmdbId ?: b.userTmdbId,
            userPosterPath = a.userPosterPath ?: b.userPosterPath, userBackdropPath = a.userBackdropPath ?: b.userBackdropPath,
            userLogoPath = a.userLogoPath ?: b.userLogoPath, addedAt = minOf(a.addedAt, b.addedAt),
            audio = tracks.audio, subtitles = tracks.subtitles, tracksAt = tracks.tracksAt,
        )
    }

    /**
     * The entry a row of [identity] (null: none yet) takes among those [named] like it: the only one not naming
     * another title. Two under one name ("Pinocchio (2022)", twice) give nothing: which is which cannot be told.
     */
    fun usable(named: List<KeptStateEntity>, identity: Int?): KeptStateEntity? =
        named.filter { identity == null || it.identity == null || it.identity == identity }.singleOrNull()

    /**
     * What a film or show born under [named]'s name takes at once ([usable]). A match by hand only in its own folder
     * of [sourceId] (a show's key standing for its folder), as Radarr and Sonarr upgrade in place: elsewhere a file
     * of that name may be another film, the very case a match by hand settles. A title TMDB matched only when nothing
     * will name the new row ([namedLater] false): neither TMDB, to be asked, nor an NFO beside it, which may name
     * another film of that name; else its TMDB id confirms it ([LibraryDao.giveBack]) or TMDB knows none ([fromHere]).
     */
    fun atBirth(named: List<KeptStateEntity>, sourceId: Long, folder: String?, namedLater: Boolean): KeptStateEntity? =
        usable(named, identity = null)?.takeIf { kept ->
            when {
                kept.userTmdbId != null -> kept.sourceId == sourceId && kept.folder == folder
                kept.identity != null -> !namedLater
                else -> true
            }
        }

    /** What a title TMDB knows by no id takes by name: one alone under it, from its own folder of [sourceId]. */
    fun fromHere(named: List<KeptStateEntity>, sourceId: Long, folder: String?): KeptStateEntity? =
        usable(named, identity = null)?.takeIf { it.sourceId == sourceId && it.folder == folder }

    /**
     * What an episode of show [show] (null: unmatched) takes among those [named] by its show's key: one of a show
     * TMDB matched waits while TMDB may still name this one ([showPending]), as for films ([atBirth]).
     */
    fun forEpisode(named: List<KeptStateEntity>, show: Int?, showPending: Boolean): KeptStateEntity? =
        usable(named, show)?.takeIf { it.identity == null || !showPending }

    /**
     * [row] with what [kept] holds that it lacks: watched state and resume point onto a row not yet played (a play
     * of its own is newer), watched onto one under way; favourite; match and artwork where it has none; the
     * earliest arrival, so a title back keeps its place in "recently added".
     */
    fun onto(row: UserState, kept: KeptStateEntity): UserState {
        val untouched = !row.played && row.positionMs == 0L && (kept.played || kept.positionMs > 0)
        val play = when {
            untouched -> row.copy(
                played = kept.played, positionMs = kept.positionMs, durationMs = kept.durationMs ?: row.durationMs,
                lastPlayedAt = kept.lastPlayedAt,
            )
            !row.played && kept.played ->
                row.copy(played = true, lastPlayedAt = listOfNotNull(row.lastPlayedAt, kept.lastPlayedAt).maxOrNull())
            else -> row
        }
        return play.copy(
            favorite = row.favorite || kept.favorite,
            userTmdbId = row.userTmdbId ?: kept.userTmdbId,
            userPosterPath = row.userPosterPath ?: kept.userPosterPath,
            userBackdropPath = row.userBackdropPath ?: kept.userBackdropPath,
            userLogoPath = row.userLogoPath ?: kept.userLogoPath,
            addedAt = minOf(row.addedAt, kept.addedAt),
        )
    }

    /** A row about to be inserted, born with what [kept] holds ([onto]). */
    fun onto(row: ItemEntity, kept: KeptStateEntity): ItemEntity = row.withUserState(onto(row.userState(), kept))

    /**
     * What becomes of rows [leaving], before they are deleted: a row [staying] of the same title ([heirs]) takes
     * their state, else it is kept by title, rows leaving together merged ([merge]), the main copy first. A title
     * whose rows held nothing forgets what was kept before, the latest removal being the user's latest state.
     */
    fun plan(leaving: List<KeptRow>, staying: List<KeptRow>): Plan {
        val heirs = heirs(staying)
        val entries = leaving.sortedBy { it.versionOf != null }.mapNotNull(::of)
        val (handed, alone) = entries.partition { it.title in heirs }
        val merged = alone.groupBy { it.title }.values.map { it.reduce(::merge) }
        return Plan(
            handed = handed.groupBy { heirs.getValue(it.title) }.mapValues { (_, all) -> all.reduce(::merge) }
                .filterValues { it.holdsAnything }.toList(),
            kept = merged.filter { it.holdsAnything },
            emptied = merged.filterNot { it.holdsAnything }.map { it.title },
        )
    }

    /**
     * Rows [staying] by the title whose state they take, the oldest of each: of the same TMDB identity (a copy, the
     * film in another folder, a new version imported first; an episode of the show's in another folder); for a
     * title without one, of its name in its folder ([KeptKeys.place]) or, for an episode, in its show, as when a new
     * version was imported a pass before the old one went. Rows of one name naming two titles take nothing.
     */
    private fun heirs(staying: List<KeptRow>): Map<String, KeptRow> =
        staying.flatMap { row -> listOfNotNull(KeptKeys.of(row)?.first, KeptKeys.place(row)).map { it to row } }
            .groupBy({ it.first }, { it.second })
            .filterValues { rows -> rows.mapNotNullTo(HashSet(), ::named).size <= 1 }
            .mapValues { (_, rows) -> rows.minBy { it.id } }

    /** The TMDB id naming [row]'s title: a film's or show's, an episode's show's. */
    private fun named(row: KeptRow): Int? = if (row.kind == ItemKind.EPISODE) row.showIdentity else row.identity

    /** [plan]'s writes. */
    class Plan(
        /** Rows staying, each with what it takes. */
        val handed: List<Pair<KeptRow, KeptStateEntity>>,
        /** Entries to keep, each replacing an older one of its title. */
        val kept: List<KeptStateEntity>,
        /** Titles whose latest rows held nothing, whose older entries go. */
        val emptied: List<String>,
    )

    /** Episodes of a show taking back entries [kept] of its TMDB id, by season and episodes. */
    fun onEpisodes(kept: List<KeptStateEntity>, episodes: List<KeptRow>): List<Pair<KeptRow, KeptStateEntity>> {
        val bySpan = kept.associateBy { KeptKeys.span(it.seasonNumber, it.episodeNumber, it.lastEpisodeNumber) }
        return episodes.mapNotNull { row ->
            bySpan[KeptKeys.span(row.seasonNumber, row.episodeNumber, row.lastEpisodeNumber)]?.let { row to it }
        }
    }
}
