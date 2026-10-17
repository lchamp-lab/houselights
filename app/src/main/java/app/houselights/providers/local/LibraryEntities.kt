package app.houselights.providers.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter
import app.houselights.core.MediaInfo
import app.houselights.core.Secrets

enum class SourceKind { MOVIES, SHOWS }

enum class Protocol { SMB, NFS }

/** A password as the app uses it. The database keeps it sealed (see [SecretConverter]); it never prints. */
data class Secret(val value: String) {
    override fun toString() = "Secret(***)"
}

/**
 * Lists stored as "A|B|C": genres, badges, a film's parts. Queries match one of their entries by
 * [matches] alone, so that a filter, a count and a page's similar titles agree on what a genre holds.
 */
object PipeList {
    @TypeConverter
    fun toText(list: List<String>): String = list.joinToString("|")

    @TypeConverter
    fun fromText(text: String): List<String> = text.split('|').filter { it.isNotBlank() }

    /**
     * SQL that holds when [column]'s list has [value] for one of its entries, whole and exact: "Drame"
     * is neither "Mélodrame" nor "drame", "4K" not "4Kx". [value] is SQL too: a parameter (`?`,
     * `:genre`) or a quoted text.
     */
    fun matches(column: String, value: String = "?"): String = "instr('|' || $column || '|', '|' || $value || '|') > 0"

    /** [matches] for Room's queries, which take constants only: the genres hold `:genre`. A test holds the two alike. */
    const val GENRES_MATCH = "instr('|' || genres || '|', '|' || :genre || '|') > 0"

    /** [matches] for Room's queries: the badges hold `:token`. */
    const val BADGES_MATCH = "instr('|' || badges || '|', '|' || :token || '|') > 0"
}

/** Seals [Secret] columns on the way to the database, opens them on the way back. */
@ProvidedTypeConverter
class SecretConverter(private val secrets: Secrets) {
    @TypeConverter
    fun seal(secret: Secret): String = secrets.seal(secret.value)

    /** A secret that can no longer be opened reads as empty: the folder then asks for its password. */
    @TypeConverter
    fun open(stored: String): Secret = Secret(secrets.open(stored) ?: "")
}

/** A network folder the user added, holding either movies or TV shows. */
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: SourceKind,
    val protocol: Protocol = Protocol.SMB,
    val host: String,
    /** SMB share name, or NFS export path; stored without leading or trailing slash. */
    val share: String,
    /** Folder inside the share, without leading or trailing slash ("" for the share root). */
    val path: String,
    val username: String,
    val password: Secret,
    val domain: String = "",
    val lastScanAt: Long = 0,
    /**
     * When its first complete scan ended: what that scan found was already there; titles added
     * after it are new for a while. 0 until then.
     */
    val firstScanAt: Long = 0,
)

/** When a folder's first complete scan ended (see [SourceEntity.firstScanAt]). */
data class SourceFirstScan(val id: Long, val firstScanAt: Long)

/** The folder's address as people read it, never with credentials: "nfs://nas/volume1/media/films". */
fun SourceEntity.address(withScheme: Boolean = true): String {
    val folder = listOf(share, path).map { it.trim('/') }.filter { it.isNotEmpty() }.joinToString("/")
    val address = listOf(host, folder).filter { it.isNotEmpty() }.joinToString("/")
    return if (withScheme) "${protocol.name.lowercase()}://$address" else address
}

enum class ItemKind { MOVIE, SERIES, SEASON, EPISODE }

/** The columns of an item before what its file holds ([ItemEntity.mediaInfo]); see [ITEM_ROW]. */
private const val ROW_HEAD =
    "id, kind, sourceId, parentId, seriesId, matchKey, tmdbId, title, sortTitle, originalTitle, " +
        "overview, tagline, year, endYear, continuing, runtimeMin, certification, rating, genres, " +
        "directors, posterPath, backdropPath, logoPath, stillPath, seasonNumber, episodeNumber, lastEpisodeNumber, filePath, " +
        "parts, fileSize, missingSince, badges, "

/** The columns of an item after what its file holds, the NFO text left out; see [ITEM_ROW]. */
private const val ROW_TAIL =
    ", probeVersion, fileDurationMs, nfoPath, NULL AS nfo, sidecarKey, userTmdbId, userPosterPath, " +
        "userBackdropPath, userLogoPath, collectionId, addedAt, matchedAt, positionMs, durationMs, played, favorite, " +
        "lastPlayedAt"

/**
 * Every column of an item but the NFO text, which can weigh up to 1 MB: what a title's page reads. Rows read
 * this way have a null [ItemEntity.nfo]: they are shown, never written back.
 */
const val ITEM_ROW = ROW_HEAD + "mediaInfo" + ROW_TAIL

/**
 * [ITEM_ROW] without what the file holds either ([ItemEntity.mediaInfo], kilobytes of JSON to decode for
 * each card), which only a title's page shows: what lists read. Their length is the file's own column
 * ([ItemEntity.fileDurationMs]). Rows read this way are shown, never written back either.
 */
const val CARD_ROW = ROW_HEAD + "NULL AS mediaInfo" + ROW_TAIL

/**
 * A file of a folder, as a scan sorts what to do with it ([LibraryDao.knownFiles]): what tells a file renamed,
 * missing, replaced or due. Never its details, its NFO or what it holds, which a pass that finds nothing new
 * would read and decode for each of thousands of files, every ten minutes.
 */
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
)

/** As [ItemEntity.sizeChanged]: the file is not the one read last. */
internal fun KnownFile.sizeChanged(file: RemoteFile): Boolean = fileSize != null && fileSize != file.size

/** [info] as the row keeps it: its JSON, and the columns read without decoding it. */
internal fun ItemEntity.withMedia(info: MediaInfo): ItemEntity =
    copy(mediaInfo = MediaProbe.encode(info), probeVersion = info.probedWith, fileDurationMs = info.durationMs)

/** What a show's card tells about its episodes (see [LibraryDao.seriesCounts]). */
data class SeriesCounts(val seriesId: Long, val unplayed: Int, val seasons: Int)

/** A title's NFO, where it lies and what it says ([LibraryDao.nfoOf]): what only the screen of the NFO reads. */
data class ItemNfo(val sourceId: Long, val nfoPath: String?, val nfo: String?)

/** What the player's reports of a film or an episode weigh against ([LibraryDao.playState], [LocalReporter]). */
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
 * How long a film or an episode lasts, as the library knows it: its file's length as the player told it,
 * else as the file was read, else as TMDB or its NFO states it. Its card and the player's "watched" weigh
 * by this one rule. A film in several files lasts what TMDB or its NFO states, its parts together: the
 * library knows its first file's length only.
 */
internal fun ItemEntity.runtimeMs(): Long? = runtimeMs(parts, durationMs, fileDurationMs, runtimeMin)

/** As [ItemEntity.runtimeMs], from what a player's report reads of the row. */
internal fun PlayState.runtimeMs(): Long? = runtimeMs(parts, durationMs, fileDurationMs, runtimeMin)

private fun runtimeMs(parts: List<String>, playerMs: Long?, fileMs: Long?, runtimeMin: Int?): Long? {
    val stated = runtimeMin?.let { it * MS_PER_MINUTE }
    return if (parts.isNotEmpty()) stated else playerMs ?: fileMs ?: stated
}

private const val MS_PER_MINUTE = 60_000L

/** Where an episode stands for the viewer ([LibraryDao.episodeStates]): what tells the next one to watch. */
data class EpisodeState(
    val id: Long,
    val seriesId: Long,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val played: Boolean,
    val positionMs: Long,
)

/** What a saga's card tells of one of its films ([LibraryDao.collectionMembers]). */
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
)

@Entity(
    tableName = "items",
    indices = [
        Index("parentId"), Index("seriesId"), Index(value = ["sourceId", "filePath"]), Index("matchKey"), Index("collectionId"),
        Index(value = ["kind", "sortTitle"]), Index(value = ["kind", "addedAt"]), Index(value = ["kind", "lastPlayedAt"]),
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
    /** The last episode a file holds when it holds several (S01E01-E02); null for one. */
    val lastEpisodeNumber: Int? = null,
    /** Path relative to the source folder, for movies and episodes. */
    val filePath: String? = null,
    /**
     * A film split in several files (CD1, CD2, disc 1…): the parts after the first, in order, relative
     * like [filePath], which is the first.
     */
    val parts: List<String> = emptyList(),
    val fileSize: Long? = null,
    /** When a scan first missed the file: its row waits a while (see [MissingFiles]); null while the file is listed. */
    val missingSince: Long? = null,
    val badges: List<String> = emptyList(),
    /** [app.houselights.core.MediaInfo] as JSON; null until the file has been read. Written with [withMedia]. */
    val mediaInfo: String? = null,
    /**
     * The probe that read [mediaInfo] ([MediaInfo.probedWith]; 0 until read), in a column of its own: a scan
     * tells the files to read again without decoding what each one holds.
     */
    val probeVersion: Int = 0,
    /** The length [mediaInfo] gives, in a column of its own: cards show it without decoding what the file holds. */
    val fileDurationMs: Long? = null,
    /** The NFO file next to the video (or the show), and its raw content. */
    val nfoPath: String? = null,
    val nfo: String? = null,
    /**
     * What the files beside it (NFO, artwork) were when last read: role, path, size and date of each.
     * One changed in place is read again; null until read.
     */
    val sidecarKey: String? = null,
    /** Choices made by the user; scans never override them. */
    val userTmdbId: Int? = null,
    val userPosterPath: String? = null,
    val userBackdropPath: String? = null,
    val userLogoPath: String? = null,
    /** The collection a film belongs to ([CollectionEntity]). */
    val collectionId: Long? = null,
    val addedAt: Long,
    /** Last TMDB lookup (successful or not); null = never tried. */
    val matchedAt: Long? = null,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val played: Boolean = false,
    val favorite: Boolean = false,
    val lastPlayedAt: Long? = null,
)

/**
 * A collection of films (a saga): from the NFO's set or TMDB's "belongs to collection". Its films
 * point to it ([ItemEntity.collectionId]); it is removed once none is left.
 */
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
    /** Films of the collection released so far, owned or not; null until TMDB was asked. */
    val partCount: Int? = null,
    /** Last time TMDB was asked for the collection; null = never. */
    val fetchedAt: Long? = null,
)

@Entity(tableName = "cast", primaryKeys = ["itemId", "ord"])
data class CastEntity(
    val itemId: Long,
    val ord: Int,
    val name: String,
    val character: String?,
    val profilePath: String?,
)
