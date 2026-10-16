package app.houselights.providers.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter
import app.houselights.core.Secrets

enum class SourceKind { MOVIES, SHOWS }

enum class Protocol { SMB, NFS }

/** A password as the app uses it. The database keeps it sealed (see [SecretConverter]); it never prints. */
data class Secret(val value: String) {
    override fun toString() = "Secret(***)"
}

/**
 * Lists stored as "A|B|C": SQL filters still match one entry with ('|' || genres || '|') LIKE '%|A|%'.
 */
object PipeList {
    @TypeConverter
    fun toText(list: List<String>): String = list.joinToString("|")

    @TypeConverter
    fun fromText(text: String): List<String> = text.split('|').filter { it.isNotBlank() }
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

/**
 * Every column of an item but the NFO text, which can weigh up to 1 MB: what lists and the scanner's
 * file index read. Rows read this way have a null [ItemEntity.nfo]: they are shown, never written back.
 */
const val ITEM_ROW =
    "id, kind, sourceId, parentId, seriesId, matchKey, tmdbId, title, sortTitle, originalTitle, " +
    "overview, tagline, year, endYear, continuing, runtimeMin, certification, rating, genres, " +
    "directors, posterPath, backdropPath, logoPath, stillPath, seasonNumber, episodeNumber, lastEpisodeNumber, filePath, " +
    "parts, fileSize, missingSince, badges, mediaInfo, nfoPath, NULL AS nfo, sidecarKey, userTmdbId, userPosterPath, " +
    "userBackdropPath, userLogoPath, collectionId, addedAt, matchedAt, positionMs, durationMs, played, favorite, " +
    "lastPlayedAt"

/** What a show's card tells about its episodes (see [LibraryDao.seriesCounts]). */
data class SeriesCounts(val seriesId: Long, val unplayed: Int, val seasons: Int)

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
     * A film split in several files (CD1, CD2, part1…): the parts after the first, in order, relative
     * like [filePath], which is the first.
     */
    val parts: List<String> = emptyList(),
    val fileSize: Long? = null,
    /** When a scan first missed the file: its row waits a while (see [MissingFiles]); null while the file is listed. */
    val missingSince: Long? = null,
    val badges: List<String> = emptyList(),
    /** [app.houselights.core.MediaInfo] as JSON; null until the file has been read. */
    val mediaInfo: String? = null,
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
