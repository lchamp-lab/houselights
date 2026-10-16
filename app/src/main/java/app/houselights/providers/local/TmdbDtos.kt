package app.houselights.providers.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- DTOs (snake_case JSON, mapped by the naming strategy; ISO codes need explicit names) ------

@Serializable
data class TmdbPage<T>(val results: List<T> = emptyList())

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
)

@Serializable
data class TmdbGenre(val name: String)

@Serializable
data class TmdbFindResult(
    val movieResults: List<TmdbSearchResult> = emptyList(),
    val tvResults: List<TmdbSearchResult> = emptyList(),
)

@Serializable
data class TmdbCastMember(val name: String, val character: String? = null, val profilePath: String? = null, val order: Int = 0)

@Serializable
data class TmdbCrewMember(val name: String, val job: String? = null)

@Serializable
data class TmdbCredits(val cast: List<TmdbCastMember> = emptyList(), val crew: List<TmdbCrewMember> = emptyList())

@Serializable
data class TmdbImage(
    val filePath: String,
    @SerialName("iso_639_1") val iso6391: String? = null,
    val voteAverage: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
)

/** Every image of a title, for the artwork pickers. */
@Serializable
data class TmdbImageSet(
    val posters: List<TmdbImage> = emptyList(),
    val backdrops: List<TmdbImage> = emptyList(),
    val logos: List<TmdbImage> = emptyList(),
)

@Serializable
data class TmdbImages(val logos: List<TmdbImage> = emptyList())

@Serializable
data class TmdbReleaseDate(val certification: String = "")

@Serializable
data class TmdbCountryReleases(@SerialName("iso_3166_1") val iso31661: String, val releaseDates: List<TmdbReleaseDate> = emptyList())

@Serializable
data class TmdbReleaseDates(val results: List<TmdbCountryReleases> = emptyList())

@Serializable
data class TmdbContentRating(@SerialName("iso_3166_1") val iso31661: String, val rating: String = "")

@Serializable
data class TmdbContentRatings(val results: List<TmdbContentRating> = emptyList())

/** The collection a film belongs to, as its details name it. */
@Serializable
data class TmdbCollectionRef(val id: Int, val name: String, val posterPath: String? = null, val backdropPath: String? = null)

/** A collection: its films ("parts"), released or announced. */
@Serializable
data class TmdbCollection(
    val id: Int,
    val name: String,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val parts: List<TmdbSearchResult> = emptyList(),
)

@Serializable
data class TmdbMovie(
    val id: Int,
    val title: String,
    val imdbId: String? = null,
    val originalTitle: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val releaseDate: String? = null,
    val runtime: Int? = null,
    val voteAverage: Double? = null,
    val genres: List<TmdbGenre> = emptyList(),
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
    val name: String,
    val originalName: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val firstAirDate: String? = null,
    val lastAirDate: String? = null,
    val status: String? = null,
    val episodeRunTime: List<Int> = emptyList(),
    val voteAverage: Double? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val credits: TmdbCredits = TmdbCredits(),
    val images: TmdbImages = TmdbImages(),
    val contentRatings: TmdbContentRatings = TmdbContentRatings(),
    val externalIds: TmdbExternalIds = TmdbExternalIds(),
)

/** A show's ids at IMDb and TheTVDB, which an NFO may name it by alone (Sonarr's often). */
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
    val seasonNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val airDate: String? = null,
    val episodes: List<TmdbEpisode> = emptyList(),
)
