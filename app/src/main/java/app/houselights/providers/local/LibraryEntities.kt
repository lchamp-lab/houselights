package app.houselights.providers.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter
import app.houselights.core.Job
import app.houselights.core.MediaInfo
import app.houselights.core.Opened
import app.houselights.core.SecretUnreadException
import app.houselights.core.Secrets

enum class SourceKind { MOVIES, SHOWS }

/**
 * How a folder is read, stored by name (TEXT): SMB or NFS on a NAS, or [LOCAL], a folder on the player's own disks
 * ([LocalBrowser]), which needed no new column: its [SourceEntity.host] holds the disk's id ([Volume.id]), its
 * [SourceEntity.share] stays empty and its [SourceEntity.path] is the folder inside the disk, its credentials empty.
 */
enum class Protocol { SMB, NFS, LOCAL }

/**
 * A plain-text password in memory; stored sealed (see [SecretConverter]) and redacted from toString. [unopened] is the
 * sealed text the Keystore did not open for now: saved back as it was, never as an empty password.
 */
data class Secret(val value: String, val unopened: String? = null) {
    /** The password to sign in with; one not opened for now throws [SecretUnreadException], never tried empty on the NAS. */
    fun forSignIn(): String = if (unopened != null) throw SecretUnreadException() else value

    override fun toString() = "Secret(***)"
}

/** Lists stored as "A|B|C"; queries match entries only via [matches], so filters, counts and menus always agree. */
object PipeList {
    @TypeConverter
    fun toText(list: List<String>): String = list.joinToString("|")

    @TypeConverter
    fun fromText(text: String): List<String> = text.split('|').filter { it.isNotBlank() }

    /**
     * SQL: [column]'s list has an entry exactly [value] ("Drame" matches neither "Mélodrame" nor "drame"). [value]
     * is SQL: a parameter (`?`, `:genre`) or a quoted literal.
     */
    fun matches(column: String, value: String = "?"): String = "instr('|' || $column || '|', '|' || $value || '|') > 0"

    /** [matches] under SQLite's lower(), so an NFO's "drame" equals TMDB's "Drame". */
    fun matchesAnyCase(column: String, value: String = "?"): String = matches("lower($column)", "lower($value)")

    /** [matchesAnyCase] on genres and `:genre`, as a constant for Room queries; a test keeps the two identical. */
    const val GENRES_MATCH = "instr('|' || lower(genres) || '|', '|' || lower(:genre) || '|') > 0"

    /** [matches] on badges and `:token`, as a constant for Room queries. */
    const val BADGES_MATCH = "instr('|' || badges || '|', '|' || :token || '|') > 0"

    /**
     * One spelling per genre, grouped by SQLite's lower() form ([GenreList.folded]) exactly as [matchesAnyCase]
     * groups, whatever lower() does with "É"; sorted ignoring accents and case.
     */
    fun distinctGenres(lists: List<GenreList>): List<String> =
        lists.flatMap { it.genres.split('|').zip(it.folded.split('|')) }
            .filter { (genre, _) -> genre.isNotBlank() }
            .groupBy({ it.second }, { it.first })
            .values.map(::spellingOf)
            .sortedBy { MetadataMerge.fold(it) }

    /** The most common spelling; on a tie "Comédie", then "COMÉDIE", then "comédie". */
    fun spellingOf(spellings: List<String>): String =
        spellings.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { caseRank(it.key) }.thenBy { it.key })
            .first().key

    private fun caseRank(spelling: String): Int = when {
        spelling.first().isUpperCase() && spelling.any { it.isLowerCase() } -> 0
        spelling.none { it.isLowerCase() } -> 1
        else -> 2
    }
}

/** A title's genres as stored by [PipeList], with their SQLite lower() form used by the genre filter. */
data class GenreList(val genres: String, val folded: String)

@ProvidedTypeConverter
class SecretConverter(private val secrets: Secrets) {
    @TypeConverter
    fun seal(secret: Secret): String = secret.unopened ?: secrets.seal(secret.value)

    /**
     * A secret that can no longer be unsealed reads as empty, so the source asks for its password again; one the
     * Keystore did not open for now reads as empty too, its sealed text kept.
     */
    @TypeConverter
    fun open(stored: String): Secret = when (val read = secrets.read(stored)) {
        is Opened.Text -> Secret(read.plain)
        Opened.Unavailable -> Secret("", unopened = stored)
        Opened.Undecryptable -> Secret("")
    }
}

/** A folder holding either movies or TV shows, on a NAS or on one of the player's own disks ([Protocol.LOCAL]). */
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: SourceKind,
    val protocol: Protocol = Protocol.SMB,
    /** The NAS's address; for [Protocol.LOCAL], the disk's id ([Volume.id]: its UUID, or "internal"), wherever it is mounted. */
    val host: String,
    /** SMB share name, or NFS export path; stored without leading or trailing slash; empty for [Protocol.LOCAL]. */
    val share: String,
    /** Folder inside the share, or inside the disk, without leading or trailing slash ("" for its root). */
    val path: String,
    val username: String,
    val password: Secret,
    val domain: String = "",
    val lastScanAt: Long = 0,
    /** End of the first complete scan (0 until then); only titles added later count as new. */
    val firstScanAt: Long = 0,
    /** Language tag its titles are to be read again in, whole ([LibraryScanner.refreshAllIn]); null when none. */
    val refreshIn: String? = null,
    /** NFS: the user the app presents itself as to the NAS (AUTH_SYS), with [gid]; both null for root. */
    val uid: Int? = null,
    val gid: Int? = null,
    /**
     * The user kept the titles a sudden drop holds in this folder (« Garder », [MissingFiles.isDrop]): they wait for their
     * files, and nothing asks again until another drop holds more.
     */
    val heldKept: Boolean = false,
)

data class SourceFirstScan(val id: Long, val firstScanAt: Long)

/** Human-readable address without credentials: "nfs://nas/volume1/media/films". */
fun SourceEntity.address(withScheme: Boolean = true): String {
    val folder = listOf(share, path).map { it.trim('/') }.filter { it.isNotEmpty() }.joinToString("/")
    val address = listOf(host, folder).filter { it.isNotEmpty() }.joinToString("/")
    return if (withScheme) "${protocol.name.lowercase()}://$address" else address
}

enum class ItemKind { MOVIE, SERIES, SEASON, EPISODE }

/** Item columns before [ItemEntity.overview]; see [ITEM_ROW]. */
private const val ROW_HEAD = "id, kind, sourceId, parentId, seriesId, matchKey, tmdbId, title, sortTitle, originalTitle, "

/** Item columns between [ItemEntity.tagline] and [ItemEntity.mediaInfo]; see [ITEM_ROW]. */
private const val ROW_BODY =
    "year, endYear, continuing, runtimeMin, certification, rating, genres, " +
        "directors, posterPath, backdropPath, logoPath, stillPath, seasonNumber, episodeNumber, lastEpisodeNumber, filePath, " +
        "parts, fileSize, missingSince, badges, "

/** Item columns after [ItemEntity.mediaInfo], with the NFO text left out; see [ITEM_ROW]. */
private const val ROW_TAIL =
    ", probeVersion, fileDurationMs, nfoPath, NULL AS nfo, sidecarKey, userTmdbId, userPosterPath, " +
        "userBackdropPath, userLogoPath, collectionId, addedAt, matchedAt, positionMs, durationMs, played, favorite, " +
        "lastPlayedAt, edition, versionOf, versionChoice, votes, creditsAt, matchState, hintKey, userNoMatch"

/** Every item column except the NFO text, which can reach 1 MB; such rows must never be written back. */
const val ITEM_ROW = ROW_HEAD + "overview, tagline, " + ROW_BODY + "mediaInfo" + ROW_TAIL

/** [ITEM_ROW] without [ItemEntity.mediaInfo] (kilobytes of JSON per card), for lists; never written back either. */
const val CARD_ROW = ROW_HEAD + "overview, tagline, " + ROW_BODY + "NULL AS mediaInfo" + ROW_TAIL

/** [CARD_ROW] without the overview and tagline, which walls and home's doors never show: neither read nor typeset. */
const val WALL_ROW = ROW_HEAD + "NULL AS overview, NULL AS tagline, " + ROW_BODY + "NULL AS mediaInfo" + ROW_TAIL

/** The columns a scan needs per known file, so an idle pass does not load thousands of full rows. */
data class KnownFile(
    val id: Long,
    val filePath: String,
    /** See [ItemEntity.parts]. */
    val parts: List<String> = emptyList(),
    val fileSize: Long? = null,
    val missingSince: Long? = null,
    val seriesId: Long? = null,
    val tmdbId: Int? = null,
    val userTmdbId: Int? = null,
    val matchedAt: Long? = null,
    val sidecarKey: String? = null,
    val probeVersion: Int = 0,
    /** See [ItemEntity.hintKey]. */
    val hintKey: String? = null,
)

internal fun KnownFile.sizeChanged(file: RemoteFile): Boolean = fileSize != null && fileSize != file.size

/** Stores [info] as JSON, plus the columns read without decoding it. */
internal fun ItemEntity.withMedia(info: MediaInfo): ItemEntity =
    copy(mediaInfo = MediaProbe.encode(info), probeVersion = info.probedWith, fileDurationMs = info.durationMs)

/** A show's episodes: those not watched, its seasons, how many, how many held ([ItemEntity.missingSince]) and since when. */
data class SeriesCounts(
    val seriesId: Long,
    val unplayed: Int,
    val seasons: Int,
    val episodes: Int = 0,
    val held: Int = 0,
    val heldSince: Long? = null,
)

data class SeasonCounts(val seasonId: Long, val episodes: Int, val played: Int)

data class ItemNfo(val sourceId: Long, val nfoPath: String?, val nfo: String?)

data class PlayState(
    /** See [ItemEntity.parts]. */
    val parts: List<String>,
    val positionMs: Long,
    val durationMs: Long?,
    val fileDurationMs: Long?,
    val runtimeMin: Int?,
    val played: Boolean,
)

/**
 * Duration from the player, else the probe, else TMDB or the NFO; shared by cards and the watched threshold. A
 * multi-part film uses the stated runtime, as only the first file's duration is known.
 */
internal fun ItemEntity.runtimeMs(): Long? = runtimeMs(parts, durationMs, fileDurationMs, runtimeMin)

internal fun PlayState.runtimeMs(): Long? = runtimeMs(parts, durationMs, fileDurationMs, runtimeMin)

internal fun runtimeMs(parts: List<String>, playerMs: Long?, fileMs: Long?, runtimeMin: Int?): Long? {
    val stated = runtimeMin?.let { it * MS_PER_MINUTE }
    return if (parts.isNotEmpty()) stated else playerMs ?: fileMs ?: stated
}

private const val MS_PER_MINUTE = 60_000L

data class EpisodeState(
    val id: Long,
    val seriesId: Long,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val played: Boolean,
    val positionMs: Long,
)

/** A wall's title: a film's row ([ItemEntity.id]) or a saga ([CollectionEntity.id]). */
data class WallRow(val saga: Boolean, val id: Long)

/** A wall's [total] titles, of which [sagas] are sagas. */
data class WallCount(val total: Int, val sagas: Int)

data class CollectionMember(
    val collectionId: Long,
    val year: Int?,
    val rating: Double?,
    val genres: List<String>,
    val played: Boolean,
    val posterPath: String?,
    val userPosterPath: String?,
    val backdropPath: String?,
    val userBackdropPath: String?,
    /** When it was added after its folder's first scan; null for a film that scan found ([LocalMapper]'s rule). */
    val addedLater: Long?,
)

@Entity(
    tableName = "items",
    indices = [
        Index("parentId"), Index("seriesId"), Index(value = ["sourceId", "filePath"]), Index("matchKey"), Index("collectionId"),
        Index(value = ["kind", "sortTitle"]), Index(value = ["kind", "addedAt"]), Index(value = ["kind", "lastPlayedAt"]),
        Index(value = ["kind", "tmdbId"]), Index("versionOf"), Index("matchState"),
    ],
)
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: ItemKind,
    val sourceId: Long,
    /** Season for an episode, series for a season. */
    val parentId: Long? = null,
    val seriesId: Long? = null,
    /** Groups files of the same show ("severance"), or "series:season" for seasons. */
    val matchKey: String? = null,
    val tmdbId: Int? = null,
    val title: String,
    val sortTitle: String,
    val originalTitle: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val year: Int? = null,
    val endYear: Int? = null,
    val continuing: Boolean = false,
    val runtimeMin: Int? = null,
    val certification: String? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val logoPath: String? = null,
    val stillPath: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    /** Last episode of a multi-episode file (S01E01-E02); null for a single episode. */
    val lastEpisodeNumber: Int? = null,
    /** Path relative to the source folder, for movies and episodes. */
    val filePath: String? = null,
    /** Later parts of a multi-file film (CD2, disc 2…), in order; [filePath] is the first. */
    val parts: List<String> = emptyList(),
    val fileSize: Long? = null,
    /**
     * When a scan first missed the file, among many of its folder gone at once: the row is held, shown, until its file is
     * back or the user answers ([MissingFiles.isDrop]). Null while listed.
     */
    val missingSince: Long? = null,
    val badges: List<String> = emptyList(),
    /** [app.houselights.core.MediaInfo] as JSON; null until probed. Written with [withMedia]. */
    val mediaInfo: String? = null,
    /** [MediaInfo.probedWith] (0 until probed), in its own column so scans need not decode the JSON. */
    val probeVersion: Int = 0,
    /** Duration from [mediaInfo], in its own column so cards show it without decoding the JSON. */
    val fileDurationMs: Long? = null,
    /** The NFO next to the video (or the show's), and its raw content. */
    val nfoPath: String? = null,
    val nfo: String? = null,
    /** Sidecar fingerprint at the last read ([SidecarKey]); null until read. */
    val sidecarKey: String? = null,
    /** Choices made by the user; scans never override them. */
    val userTmdbId: Int? = null,
    val userPosterPath: String? = null,
    val userBackdropPath: String? = null,
    val userLogoPath: String? = null,
    val collectionId: Long? = null,
    val addedAt: Long,
    /** Last TMDB lookup (successful or not); null = never tried. */
    val matchedAt: Long? = null,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val played: Boolean = false,
    val favorite: Boolean = false,
    val lastPlayedAt: Long? = null,
    /** Edition of this copy of a film ([app.houselights.core.Editions]), from its NFO or file name. */
    val edition: String? = null,
    /** Main row (the oldest) of this film's copies; null for main rows. Set by [LibraryDao.groupVersions]. */
    val versionOf: Long? = null,
    /** User's choice of the copy to play (a row id), stored on the main row. Scans never override it. */
    val versionChoice: Long? = null,
    /** Number of votes behind [rating], when TMDB or the NFO gives it. */
    val votes: Int? = null,
    /** When credits became complete (TMDB answered or knows no such title); null for pre-v12 rows until [Backfill]. */
    val creditsAt: Long? = null,
    /** What TMDB's last answer made of a film or show ([MatchState]); null for others, and while TMDB has not answered. */
    val matchState: MatchState? = null,
    /** The id its name gave when its last lookup tried it ([NameIds.key]); a name giving another has it looked up again. */
    val hintKey: String? = null,
    /** The user's: a film or show left as it is, never to identify ([TO_IDENTIFY]). Scans never override it. */
    val userNoMatch: Boolean = false,
)

/**
 * A film or show as TMDB's last answer left it: [MATCHED] whenever it has a TMDB id; else [NOT_FOUND] when TMDB had
 * nothing for it, [UNSURE] when several candidates came and none was clearly ahead ([SuggestionEntity] holds them).
 */
enum class MatchState { MATCHED, NOT_FOUND, UNSURE }

/**
 * One of a film's or show's best TMDB candidates when none was clearly ahead ([MatchState.UNSURE]), [rank] 0 the best,
 * so they can be offered without asking TMDB again; replaced at each answer, deleted with the title.
 */
@Entity(tableName = "suggestions", primaryKeys = ["itemId", "rank"])
data class SuggestionEntity(
    val itemId: Long,
    val rank: Int,
    val tmdbId: Int,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    /** Its score, from 0 to 1, the best candidates scoring highest. */
    val score: Double,
)

/** A saga, from the NFO's set or TMDB's "belongs to collection"; deleted once no film is left. */
@Entity(tableName = "collections", indices = [Index(value = ["matchKey"], unique = true)])
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "tmdb:<id>" when TMDB knows the collection, else "name:<normalised name>". */
    val matchKey: String,
    val tmdbId: Int? = null,
    val name: String,
    val sortTitle: String,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    /** Number of the collection's films released so far, owned or not; null until fetched from TMDB. */
    val partCount: Int? = null,
    /** Last TMDB fetch of the collection; null if never. */
    val fetchedAt: Long? = null,
)

/** One credit of a film or show, [ord] being the billing order within [job]; [personKey] is a [PersonKeys] key. */
@Entity(
    tableName = "credits",
    primaryKeys = ["itemId", "job", "ord"],
    indices = [Index(value = ["personKey", "itemId"]), Index(value = ["job", "personKey"]), Index("foldedName")],
)
data class CreditEntity(
    /** The film or show (never a season or episode). */
    val itemId: Long,
    val job: Job,
    val ord: Int,
    val personKey: String,
    val name: String,
    /** [name] folded ([PersonKeys.fold]), used to move name-keyed people to their TMDB key. */
    val foldedName: String,
    val character: String? = null,
    val profilePath: String? = null,
)

/** A saga film as TMDB lists it, owned or not, so the saga page can show missing ones; [ord] is release order. */
@Entity(tableName = "saga_parts", primaryKeys = ["collectionId", "tmdbId"], indices = [Index("tmdbId")])
data class SagaPartEntity(
    val collectionId: Long,
    val tmdbId: Int,
    val ord: Int,
    val title: String,
    /** "2021-10-22"; null when TMDB has none yet. */
    val releaseDate: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
)

enum class SearchKind { TITLE, PERSON, SAGA }

/** A folded name word ([SearchWords]), prefix-searched by [kind]; [ref] is a row id or person key. */
@Entity(tableName = "search_words", primaryKeys = ["kind", "word", "ref"], indices = [Index(value = ["kind", "ref"])])
data class SearchWordEntity(val kind: SearchKind, val word: String, val ref: String)
