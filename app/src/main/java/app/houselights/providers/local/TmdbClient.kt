package app.houselights.providers.local

import app.houselights.core.Http
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** TMDB could not be reached (no network, DNS, timeout), now or a moment ago. */
class TmdbUnreachableException(cause: IOException?) : IOException("TMDB is unreachable", cause)

/**
 * TMDB refused the API key (401): to be changed in the settings, not a server to sign in to again.
 * An [IOException], as anything an OkHttp interceptor throws must be.
 */
class TmdbKeyRefusedException : IOException("TMDB refused the API key")

/** What TMDB says of a key. */
enum class TmdbKeyCheck {
    ACCEPTED,
    REFUSED,

    /** TMDB could not say: out of reach, or failing. */
    UNCHECKED,
}

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
            val response = try {
                chain.proceed(chain.request().newBuilder().url(url).build())
            } catch (e: IOException) {
                unreachableUntil = System.currentTimeMillis() + UNREACHABLE_PAUSE_MS
                throw TmdbUnreachableException(e)
            }
            if (response.code == HTTP_UNAUTHORIZED) {
                response.close()
                throw TmdbKeyRefusedException()
            }
            response
        }
        .build()

    private val api: TmdbApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(http)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(TmdbApi::class.java)

    /**
     * Whether TMDB takes this client's key, asked by its lightest call: `authentication` answers 200
     * for a key it takes, 401 for one it refuses.
     */
    suspend fun checkKey(): TmdbKeyCheck = try {
        val request = Request.Builder().url(BASE_URL + "authentication").build()
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { if (it.isSuccessful) TmdbKeyCheck.ACCEPTED else TmdbKeyCheck.UNCHECKED }
        }
    } catch (_: TmdbKeyRefusedException) {
        TmdbKeyCheck.REFUSED
    } catch (_: IOException) {
        TmdbKeyCheck.UNCHECKED
    }

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
        private const val BASE_URL = "https://api.themoviedb.org/3/"
        private const val HTTP_UNAUTHORIZED = 401
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
