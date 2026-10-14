package app.houselights.providers.local

import app.houselights.core.Http
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

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
)

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

interface TmdbApi {
    @GET("search/movie")
    suspend fun searchMovie(
        @Query("query") query: String,
        @Query("year") year: Int?,
        @Query("language") language: String,
    ): TmdbPage<TmdbSearchResult>

    @GET("search/tv")
    suspend fun searchTv(
        @Query("query") query: String,
        @Query("first_air_date_year") year: Int?,
        @Query("language") language: String,
    ): TmdbPage<TmdbSearchResult>

    @GET("movie/{id}")
    suspend fun movie(
        @Path("id") id: Int,
        @Query("language") language: String,
        @Query("include_image_language") imageLanguages: String,
        @Query("append_to_response") append: String = "credits,images,release_dates",
    ): TmdbMovie

    @GET("tv/{id}")
    suspend fun tv(
        @Path("id") id: Int,
        @Query("language") language: String,
        @Query("include_image_language") imageLanguages: String,
        @Query("append_to_response") append: String = "credits,images,content_ratings",
    ): TmdbTv

    @GET("find/{id}")
    suspend fun find(
        @Path("id") externalId: String,
        @Query("external_source") source: String = "imdb_id",
    ): TmdbFindResult

    @GET("movie/{id}/images")
    suspend fun movieImages(@Path("id") id: Int, @Query("include_image_language") languages: String): TmdbImageSet

    @GET("tv/{id}/images")
    suspend fun tvImages(@Path("id") id: Int, @Query("include_image_language") languages: String): TmdbImageSet

    @GET("collection/{id}")
    suspend fun collection(@Path("id") id: Int, @Query("language") language: String): TmdbCollection

    @GET("trending/movie/week")
    suspend fun trendingMovies(@Query("language") language: String): TmdbPage<TmdbSearchResult>

    @GET("tv/{id}/season/{season}")
    suspend fun season(@Path("id") id: Int, @Path("season") season: Int, @Query("language") language: String): TmdbSeason
}

/** TMDB could not be reached (no network, DNS, timeout), now or a moment ago. */
class TmdbUnreachableException(cause: IOException?) : IOException("TMDB is unreachable", cause)

/** TMDB v3 client; metadata in the device language. */
class TmdbClient(private val apiKey: String) {
    private val locale: Locale = Locale.getDefault()

    /** Still right for [key] and the device language: a client lives as long as both stay the same. */
    fun fits(key: String): Boolean = key == apiKey && locale == Locale.getDefault()

    val language: String = locale.toLanguageTag().ifBlank { "en-US" }
    val languageCode: String = locale.language.ifBlank { "en" }
    val region: String = locale.country.ifBlank { "US" }
    private val imageLanguages = listOf(languageCode, "en", "null").distinct().joinToString(",")

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    /** Until when calls fail at once, after TMDB could not be reached. */
    @Volatile
    private var unreachableUntil = 0L

    private val http = Http.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            // Once TMDB is out of reach, the next files of a scan don't each wait for a timeout.
            if (System.currentTimeMillis() < unreachableUntil) throw TmdbUnreachableException(null)
            val url = chain.request().url.newBuilder().addQueryParameter("api_key", apiKey).build()
            try {
                chain.proceed(chain.request().newBuilder().url(url).build())
            } catch (e: IOException) {
                unreachableUntil = System.currentTimeMillis() + UNREACHABLE_PAUSE_MS
                throw TmdbUnreachableException(e)
            }
        }
        .build()

    private val api: TmdbApi = Retrofit.Builder()
        .baseUrl("https://api.themoviedb.org/3/")
        .client(http)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(TmdbApi::class.java)

    /** Best movie match; retries without the year, which is often off by one in file names. */
    suspend fun findMovie(title: String, year: Int?): TmdbMovie? {
        val hit = api.searchMovie(title, year, language).results.firstOrNull()
            ?: year?.let { api.searchMovie(title, null, language).results.firstOrNull() }
            ?: return null
        return api.movie(hit.id, language, imageLanguages)
    }

    /** null when TMDB has no such film. A network failure throws: it must never read as "not found". */
    suspend fun movieById(id: Int): TmdbMovie? = unlessMissing { movie(id) }

    suspend fun tvById(id: Int): TmdbTv? = unlessMissing { tv(id) }

    /** Like [movieById], but an unknown id throws instead of returning null. */
    suspend fun movie(id: Int): TmdbMovie = api.movie(id, language, imageLanguages)

    suspend fun tv(id: Int): TmdbTv = api.tv(id, language, imageLanguages)

    /** Search results, for manual identification. */
    suspend fun searchMovies(query: String, year: Int?): List<TmdbSearchResult> = api.searchMovie(query, year, language).results

    suspend fun searchShows(query: String, year: Int?): List<TmdbSearchResult> = api.searchTv(query, year, language).results

    /** The film or show an IMDb id points to, as a search result. */
    suspend fun findImdb(imdbId: String, show: Boolean): TmdbSearchResult? {
        val r = api.find(imdbId)
        return if (show) r.tvResults.firstOrNull() else r.movieResults.firstOrNull()
    }

    /** Every poster, backdrop and logo of a title: in the device language, in English and without text. */
    suspend fun images(tmdbId: Int, show: Boolean): TmdbImageSet =
        if (show) api.tvImages(tmdbId, imageLanguages) else api.movieImages(tmdbId, imageLanguages)

    /** TMDB ids of the film and show matching an IMDb id. */
    suspend fun findByImdb(imdbId: String): Pair<Int?, Int?> = unlessMissing {
        val r = api.find(imdbId)
        r.movieResults.firstOrNull()?.id to r.tvResults.firstOrNull()?.id
    } ?: (null to null)

    suspend fun findTv(title: String, year: Int?): TmdbTv? {
        val hit = api.searchTv(title, year, language).results.firstOrNull()
            ?: year?.let { api.searchTv(title, null, language).results.firstOrNull() }
            ?: return null
        return api.tv(hit.id, language, imageLanguages)
    }

    suspend fun season(tvId: Int, season: Int): TmdbSeason? = unlessMissing { api.season(tvId, season, language) }

    suspend fun collection(id: Int): TmdbCollection? = unlessMissing { api.collection(id, language) }

    /** The answer, or null when TMDB says it has no such entry (404); any other failure throws. */
    private inline fun <T> unlessMissing(block: () -> T): T? = try {
        block()
    } catch (e: HttpException) {
        if (e.code() == HTTP_NOT_FOUND) null else throw e
    }

    /** Posters of this week's trending films, for the welcome screen's wall. */
    suspend fun trendingPosterPaths(): List<String> = api.trendingMovies(language).results.mapNotNull { it.posterPath }

    /** Logo in the device language, else English, else language-neutral. */
    fun pickLogo(images: TmdbImages): String? =
        listOf(languageCode, "en", null).firstNotNullOfOrNull { lang ->
            images.logos.filter { it.iso6391 == lang }.maxByOrNull { it.voteAverage }?.filePath
        }

    fun movieCertification(movie: TmdbMovie): String? =
        movie.releaseDates.results.firstOrNull { it.iso31661 == region }
            ?.releaseDates?.firstOrNull { it.certification.isNotBlank() }?.certification

    fun tvCertification(tv: TmdbTv): String? =
        tv.contentRatings.results.firstOrNull { it.iso31661 == region }?.rating?.takeIf { it.isNotBlank() }

    companion object {
        private const val HTTP_NOT_FOUND = 404
        private const val UNREACHABLE_PAUSE_MS = 60_000L
        private const val IMAGE_BASE = "https://image.tmdb.org/t/p/"
        private val POSTER_SIZES = listOf(92, 154, 185, 342, 500, 780)
        private val BACKDROP_SIZES = listOf(300, 780, 1280)
        private val LOGO_SIZES = listOf(92, 154, 185, 300, 500)
        private val PROFILE_SIZES = listOf(45, 185)
        private val STILL_SIZES = listOf(92, 185, 300)

        enum class ImageType(val sizes: List<Int>) {
            POSTER(POSTER_SIZES), BACKDROP(BACKDROP_SIZES), LOGO(LOGO_SIZES), PROFILE(PROFILE_SIZES), STILL(STILL_SIZES)
        }

        /** Smallest TMDB size bucket covering [widthPx], else the original file. */
        fun imageUrl(path: String, type: ImageType, widthPx: Int): String {
            val size = type.sizes.firstOrNull { it >= widthPx }?.let { "w$it" } ?: "original"
            return IMAGE_BASE + size + path
        }
    }
}
