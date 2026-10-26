package app.houselights.providers.local

import app.houselights.core.EntryByEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- DTOs (snake_case JSON, mapped by the naming strategy; ISO codes need explicit names) ------
// A list is read entry by entry: an entry TMDB left incomplete (a person without a name, an image without a file) is
// left out. A title's name may be missing: the NFO's or the file's stands in.

@Serializable
data class TmdbPage<T>(@Serializable(with = EntryByEntry::class) val results: List<T> = emptyList())

@Serializable
data class TmdbSearchResult(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val popularity: Double = 0.0,
    val posterPath: String? = null,
    val originalTitle: String? = null,
    val originalName: String? = null,
    val overview: String? = null,
    val backdropPath: String? = null,
)

@Serializable
data class TmdbGenre(val name: String)

@Serializable
data class TmdbFindResult(
    @Serializable(with = EntryByEntry::class) val movieResults: List<TmdbSearchResult> = emptyList(),
    @Serializable(with = EntryByEntry::class) val tvResults: List<TmdbSearchResult> = emptyList(),
)

/** A cast member; [id] is the TMDB person id, used as the library's person key. */
@Serializable
data class TmdbCastMember(
    val name: String,
    val character: String? = null,
    val profilePath: String? = null,
    val order: Int = 0,
    val id: Int? = null,
)

/** A crew member; [job] is e.g. "Director", "Screenplay", "Original Music Composer". */
@Serializable
data class TmdbCrewMember(val name: String, val job: String? = null, val id: Int? = null, val profilePath: String? = null)

@Serializable
data class TmdbCreator(val name: String, val id: Int? = null, val profilePath: String? = null)

@Serializable
data class TmdbCredits(
    @Serializable(with = EntryByEntry::class) val cast: List<TmdbCastMember> = emptyList(),
    @Serializable(with = EntryByEntry::class) val crew: List<TmdbCrewMember> = emptyList(),
)

@Serializable
data class TmdbImage(
    val filePath: String,
    @SerialName("iso_639_1") val iso6391: String? = null,
    val voteAverage: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
)

/** All images of a title, for the artwork pickers. */
@Serializable
data class TmdbImageSet(
    @Serializable(with = EntryByEntry::class) val posters: List<TmdbImage> = emptyList(),
    @Serializable(with = EntryByEntry::class) val backdrops: List<TmdbImage> = emptyList(),
    @Serializable(with = EntryByEntry::class) val logos: List<TmdbImage> = emptyList(),
)

@Serializable
data class TmdbImages(@Serializable(with = EntryByEntry::class) val logos: List<TmdbImage> = emptyList())

@Serializable
data class TmdbReleaseDate(val certification: String = "")

@Serializable
data class TmdbCountryReleases(@SerialName("iso_3166_1") val iso31661: String, val releaseDates: List<TmdbReleaseDate> = emptyList())

@Serializable
data class TmdbReleaseDates(@Serializable(with = EntryByEntry::class) val results: List<TmdbCountryReleases> = emptyList())

@Serializable
data class TmdbContentRating(@SerialName("iso_3166_1") val iso31661: String, val rating: String = "")

@Serializable
data class TmdbContentRatings(@Serializable(with = EntryByEntry::class) val results: List<TmdbContentRating> = emptyList())

/** The collection reference in a film's details. */
@Serializable
data class TmdbCollectionRef(val id: Int, val name: String? = null, val posterPath: String? = null, val backdropPath: String? = null)

/** A collection with its films ("parts"), released or announced. */
@Serializable
data class TmdbCollection(
    val id: Int,
    val name: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    @Serializable(with = EntryByEntry::class) val parts: List<TmdbSearchResult> = emptyList(),
)

@Serializable
data class TmdbMovie(
    val id: Int,
    val title: String? = null,
    val imdbId: String? = null,
    val originalTitle: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val releaseDate: String? = null,
    val runtime: Int? = null,
    val voteAverage: Double? = null,
    val voteCount: Int? = null,
    @Serializable(with = EntryByEntry::class) val genres: List<TmdbGenre> = emptyList(),
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val credits: TmdbCredits = TmdbCredits(),
    val images: TmdbImages = TmdbImages(),
    val releaseDates: TmdbReleaseDates = TmdbReleaseDates(),
    val belongsToCollection: TmdbCollectionRef? = null,
)

@Serializable
data class TmdbTv(
    val id: Int,
    val name: String? = null,
    val originalName: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val firstAirDate: String? = null,
    val lastAirDate: String? = null,
    val status: String? = null,
    val episodeRunTime: List<Int> = emptyList(),
    val voteAverage: Double? = null,
    val voteCount: Int? = null,
    @Serializable(with = EntryByEntry::class) val createdBy: List<TmdbCreator> = emptyList(),
    @Serializable(with = EntryByEntry::class) val genres: List<TmdbGenre> = emptyList(),
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val credits: TmdbCredits = TmdbCredits(),
    val images: TmdbImages = TmdbImages(),
    val contentRatings: TmdbContentRatings = TmdbContentRatings(),
    val externalIds: TmdbExternalIds = TmdbExternalIds(),
)

/** A film's other titles, by country ([titles]), or a show's ([results]): its name abroad, its working titles. */
@Serializable
data class TmdbAlternativeTitles(
    @Serializable(with = EntryByEntry::class) val titles: List<TmdbAlternativeTitle> = emptyList(),
    @Serializable(with = EntryByEntry::class) val results: List<TmdbAlternativeTitle> = emptyList(),
)

@Serializable
data class TmdbAlternativeTitle(val title: String, @SerialName("iso_3166_1") val iso31661: String? = null)

/** A show's IMDb and TheTVDB ids, which may be all a (Sonarr) NFO has. */
@Serializable
data class TmdbExternalIds(val imdbId: String? = null, val tvdbId: Int? = null)

@Serializable
data class TmdbEpisode(
    val episodeNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    val stillPath: String? = null,
    val airDate: String? = null,
    val runtime: Int? = null,
    val voteAverage: Double? = null,
)

@Serializable
data class TmdbSeason(
    val seasonNumber: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val airDate: String? = null,
    @Serializable(with = EntryByEntry::class) val episodes: List<TmdbEpisode> = emptyList(),
)
