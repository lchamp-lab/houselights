package app.houselights.providers.local

import android.os.SystemClock
import app.houselights.core.Http
import app.houselights.core.HttpStatus
import app.houselights.core.TmdbKeyRefusedException
import app.houselights.core.TmdbNotFoundException
import app.houselights.core.TmdbUnreachableException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** TMDB rate-limited (429) or gateway failure (502, 503, 504); retried once after [retryAfterMs]. */
internal class TmdbBusyException(code: Int, val retryAfterMs: Long) : TmdbUnreachableException(null, "TMDB answered $code")

/** Thrown without a request while a scan client pauses after a network failure; a pass does not count it as new. */
internal class TmdbPausedException : TmdbUnreachableException(null, "TMDB is not asked for a minute after it could not be reached")

enum class TmdbKeyCheck {
    ACCEPTED,
    REFUSED,

    /** TMDB unreachable or failing. */
    UNCHECKED,
}

/** TMDB v3 client; metadata in the device language. */
class TmdbClient(
    private val apiKey: String,
    /**
     * For scans: after a network failure or repeated 429, calls fail at once for a minute ([TmdbPausedException])
     * instead of each file waiting for a timeout. Interactive clients always query TMDB.
     */
    private val pauseWhenUnreachable: Boolean = false,
    /** Fake TMDB for tests. */
    transport: Interceptor? = null,
) {
    private val locale: Locale = Locale.getDefault()

    /** Whether this client still matches [key] and the device locale; otherwise a new one is needed. */
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

    /** End of the pause ([pauseWhenUnreachable]) on the monotonic clock, so a clock set back cannot extend it. */
    @Volatile
    private var unreachableUntil = 0L

    private val http = Http.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(::intercept)
        .apply { transport?.let(::addInterceptor) }
        .build()

    /**
     * Adds the key and maps statuses to typed failures (key refused, not found, busy). Bodies are read here, within
     * the timeouts, because a stalled body read later by Retrofit would surface as a generic IO error and look like
     * an unreachable NAS.
     */
    private fun intercept(chain: Interceptor.Chain): Response {
        val response = answer(chain)
        if (response.isSuccessful) return response
        val failure = when (val code = response.code) {
            HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN -> TmdbKeyRefusedException()
            HttpStatus.NOT_FOUND -> TmdbNotFoundException()
            HttpStatus.TOO_MANY_REQUESTS -> TmdbBusyException(code, retryAfterMs(response.header("Retry-After")))
            in GATEWAY_FAILURES -> TmdbBusyException(code, GATEWAY_RETRY_MS)
            // Other statuses surface as Retrofit's HttpException.
            else -> return response
        }
        response.close()
        throw failure
    }

    /**
     * Proceeds with the body buffered; throws [TmdbUnreachableException] on IO failure. Only network failures pause a
     * scan client; others (stream reset, oversized answer) say nothing about the next request.
     */
    private fun answer(chain: Interceptor.Chain): Response {
        if (pauseWhenUnreachable && SystemClock.elapsedRealtime() < unreachableUntil) throw TmdbPausedException()
        val url = chain.request().url.newBuilder().addQueryParameter("api_key", apiKey).build()
        return try {
            chain.proceed(chain.request().newBuilder().url(url).build()).buffered()
        } catch (e: IOException) {
            // A cancelled call (search replaced, screen left) says nothing about TMDB.
            val canceled = chain.call().isCanceled()
            if (!canceled && isNetworkFailure(e)) pause()
            throw if (canceled) e else TmdbUnreachableException(e)
        }
    }

    /** No route, DNS, refused or timeout; ConnectException and NoRouteToHostException are SocketExceptions. */
    private fun isNetworkFailure(e: Throwable) = generateSequence(e) { it.cause }.any {
        it is UnknownHostException || it is SocketException || it is SocketTimeoutException
    }

    /** The response with its body read into memory, at most [MAX_BODY_BYTES]. */
    private fun Response.buffered(): Response {
        val body = body ?: return this
        val bytes = body.use {
            val source = it.source()
            if (source.request(MAX_BODY_BYTES + 1)) throw IOException("TMDB answered more than $MAX_BODY_BYTES bytes")
            source.buffer.readByteArray()
        }
        return newBuilder().body(bytes.toResponseBody(body.contentType())).build()
    }

    private fun pause() {
        if (pauseWhenUnreachable) unreachableUntil = SystemClock.elapsedRealtime() + UNREACHABLE_PAUSE_MS
    }

    private val api: TmdbApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(http)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(TmdbApi::class.java)

    /** Runs [request], retrying once after the requested wait when TMDB is busy; 404 throws [TmdbNotFoundException]. */
    private suspend fun <T> call(request: suspend (TmdbApi) -> T): T = try {
        request(api)
    } catch (busy: TmdbBusyException) {
        delay(busy.retryAfterMs)
        try {
            request(api)
        } catch (again: TmdbBusyException) {
            pause()
            throw again
        }
    }

    /** Checks the key with TMDB's lightest call: `authentication` answers 200 for a valid key, 401 otherwise. */
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

    /** Best match, retrying without the year (often off by one in file names); a hit removed from TMDB gives null. */
    suspend fun findMovie(title: String, year: Int?): TmdbMovie? {
        val hit = call { it.searchMovie(title, year, language) }.results.firstOrNull()
            ?: year?.let { call { api -> api.searchMovie(title, null, language) }.results.firstOrNull() }
            ?: return null
        return unlessMissing { call { it.movie(hit.id, language, imageLanguages) } }
    }

    /** Null when TMDB has no such film; a network failure throws and must never read as "not found". */
    suspend fun movieById(id: Int): TmdbMovie? = unlessMissing { movie(id) }

    suspend fun tvById(id: Int): TmdbTv? = unlessMissing { tv(id) }

    /** Like [movieById], but an unknown id throws [TmdbNotFoundException]. */
    suspend fun movie(id: Int): TmdbMovie = call { it.movie(id, language, imageLanguages) }

    suspend fun tv(id: Int): TmdbTv = call { it.tv(id, language, imageLanguages) }

    suspend fun searchMovies(query: String, year: Int?): List<TmdbSearchResult> = call { it.searchMovie(query, year, language) }.results

    suspend fun searchShows(query: String, year: Int?): List<TmdbSearchResult> = call { it.searchTv(query, year, language) }.results

    /** The film or show for an IMDb id, as a search result. */
    suspend fun findImdb(imdbId: String, show: Boolean): TmdbSearchResult? {
        val r = call { it.find(imdbId) }
        return if (show) r.tvResults.firstOrNull() else r.movieResults.firstOrNull()
    }

    /** All posters, backdrops and logos in the device language, English, or without text. */
    suspend fun images(tmdbId: Int, show: Boolean): TmdbImageSet =
        call { if (show) it.tvImages(tmdbId, imageLanguages) else it.movieImages(tmdbId, imageLanguages) }

    /** TMDB ids of the film and show matching an IMDb id. */
    suspend fun findByImdb(imdbId: String): Pair<Int?, Int?> = unlessMissing {
        val r = call { it.find(imdbId) }
        r.movieResults.firstOrNull()?.id to r.tvResults.firstOrNull()?.id
    } ?: (null to null)

    suspend fun findTv(title: String, year: Int?): TmdbTv? {
        val hit = call { it.searchTv(title, year, language) }.results.firstOrNull()
            ?: year?.let { call { api -> api.searchTv(title, null, language) }.results.firstOrNull() }
            ?: return null
        return unlessMissing { call { it.tv(hit.id, language, imageLanguages) } }
    }

    suspend fun season(tvId: Int, season: Int): TmdbSeason? = unlessMissing { call { it.season(tvId, season, language) } }

    suspend fun collection(id: Int): TmdbCollection? = unlessMissing { call { it.collection(id, language) } }

    /** Null on 404; any other failure throws. */
    private inline fun <T> unlessMissing(block: () -> T): T? = try {
        block()
    } catch (_: TmdbNotFoundException) {
        null
    }

    /** This week's trending film posters, for the welcome screen. */
    suspend fun trendingPosterPaths(): List<String> = call { it.trendingMovies(language) }.results.mapNotNull { it.posterPath }

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
        private val GATEWAY_FAILURES = setOf(502, 503, 504)
        private const val UNREACHABLE_PAUSE_MS = 60_000L

        /** Cap on a buffered body; a film with cast and images is well under 1 MB. */
        private const val MAX_BODY_BYTES = 8L * 1024 * 1024

        /** Retry delay after a gateway failure, and the cap on a 429's Retry-After. */
        private const val GATEWAY_RETRY_MS = 1_000L
        private const val MAX_RETRY_AFTER_MS = 5_000L
        private const val MS_PER_SECOND = 1_000L
        private const val IMAGE_BASE = "https://image.tmdb.org/t/p/"
        private val POSTER_SIZES = listOf(92, 154, 185, 342, 500, 780)
        private val BACKDROP_SIZES = listOf(300, 780, 1280)
        private val LOGO_SIZES = listOf(92, 154, 185, 300, 500)
        private val PROFILE_SIZES = listOf(45, 185)
        private val STILL_SIZES = listOf(92, 185, 300)

        /** A 429's `Retry-After` in seconds (TMDB never sends a date); one second if absent, five at most. */
        internal fun retryAfterMs(header: String?): Long =
            (header?.trim()?.toLongOrNull()?.times(MS_PER_SECOND) ?: GATEWAY_RETRY_MS).coerceIn(0, MAX_RETRY_AFTER_MS)

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
