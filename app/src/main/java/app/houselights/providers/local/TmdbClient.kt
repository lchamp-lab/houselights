package app.houselights.providers.local

import android.os.SystemClock
import app.houselights.core.Http
import app.houselights.core.NotFoundException
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

/**
 * TMDB could not be reached (no network, DNS, timeout), now or a moment ago, or stayed busy when
 * asked again (429, a gateway failure).
 */
open class TmdbUnreachableException(cause: Throwable?, message: String = "TMDB is unreachable") : IOException(message, cause)

/**
 * TMDB refused the API key (401, 403): to be changed in the settings, not a server to sign in to again.
 * An [IOException], as anything an OkHttp interceptor throws must be.
 */
class TmdbKeyRefusedException : IOException("TMDB refused the API key")

/** TMDB is busy (429) or behind a failing gateway (502, 503, 504): asked again once, after [retryAfterMs]. */
internal class TmdbBusyException(code: Int, val retryAfterMs: Long) : TmdbUnreachableException(null, "TMDB answered $code")

/**
 * Not asked: a scan's client pauses for a minute after the network failed ([TmdbClient]'s
 * pauseWhenUnreachable). A failure of a moment ago, not a new one: a pass does not count it.
 */
internal class TmdbPausedException : TmdbUnreachableException(null, "TMDB is not asked for a minute after it could not be reached")

/** TMDB has no such entry (404); [TmdbClient] says so with a [NotFoundException]. */
internal class TmdbNotFoundException : IOException("TMDB has no such entry")

/** What TMDB says of a key. */
enum class TmdbKeyCheck {
    ACCEPTED,
    REFUSED,

    /** TMDB could not say: out of reach, or failing. */
    UNCHECKED,
}

/** TMDB v3 client; metadata in the device language. */
class TmdbClient(
    private val apiKey: String,
    /**
     * Once the network to TMDB failed, or TMDB stayed busy, calls fail at once for a minute
     * ([TmdbPausedException]): for scans, whose next files would each wait for a timeout. Someone
     * asking (a search, artwork, a key to check) is answered by TMDB itself every time.
     */
    private val pauseWhenUnreachable: Boolean = false,
    /** Answers in place of the network: TMDB as tests play it. */
    transport: Interceptor? = null,
) {
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

    /**
     * Until when calls fail at once, after TMDB could not be reached ([pauseWhenUnreachable]); on the
     * clock that only moves forward, as a clock set back would hold the pause for as long.
     */
    @Volatile
    private var unreachableUntil = 0L

    private val http = Http.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(::intercept)
        .apply { transport?.let(::addInterceptor) }
        .build()

    /**
     * Adds the key, and turns what TMDB answers into what it means: a key refused, an entry it does
     * not have, a moment to wait. Every body is read here, a failure's too, within the timeouts that
     * tell TMDB is out of reach: read later by Retrofit, a stalled or reset answer would reach the
     * scan as is, and pass for a NAS that stopped answering.
     */
    private fun intercept(chain: Interceptor.Chain): Response {
        val response = answer(chain)
        if (response.isSuccessful) return response
        val failure = when (val code = response.code) {
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> TmdbKeyRefusedException()
            HTTP_NOT_FOUND -> TmdbNotFoundException()
            HTTP_TOO_MANY_REQUESTS -> TmdbBusyException(code, retryAfterMs(response.header("Retry-After")))
            in GATEWAY_FAILURES -> TmdbBusyException(code, GATEWAY_RETRY_MS)
            // Anything else reaches the caller as an HTTP failure (Retrofit's HttpException).
            else -> return response
        }
        response.close()
        throw failure
    }

    /**
     * TMDB's answer, its body read; TMDB out of reach throws. The network failing pauses a scan's
     * client: the next files would each wait for the same timeout. Any other failure (a stream
     * reset, an answer too large) tells nothing of the next request.
     */
    private fun answer(chain: Interceptor.Chain): Response {
        if (pauseWhenUnreachable && SystemClock.elapsedRealtime() < unreachableUntil) throw TmdbPausedException()
        val url = chain.request().url.newBuilder().addQueryParameter("api_key", apiKey).build()
        return try {
            chain.proceed(chain.request().newBuilder().url(url).build()).buffered()
        } catch (e: IOException) {
            // A call given up on (a search replaced, a screen left) says nothing of TMDB.
            val canceled = chain.call().isCanceled()
            if (!canceled && isNetworkFailure(e)) pause()
            throw if (canceled) e else TmdbUnreachableException(e)
        }
    }

    /** No route, no name, no connection, or no answer in time: ConnectException and NoRouteToHostException are SocketExceptions. */
    private fun isNetworkFailure(e: Throwable) = generateSequence(e) { it.cause }.any {
        it is UnknownHostException || it is SocketException || it is SocketTimeoutException
    }

    /** The response with its body read, [MAX_BODY_BYTES] at most. */
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

    /**
     * One request: asked once more when TMDB is busy, after the wait it asks for. An entry TMDB does
     * not have throws [NotFoundException].
     */
    private suspend fun <T> call(request: suspend (TmdbApi) -> T): T = try {
        try {
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
    } catch (_: TmdbNotFoundException) {
        throw NotFoundException("TMDB entry")
    }

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
        val hit = call { it.searchMovie(title, year, language) }.results.firstOrNull()
            ?: year?.let { call { it.searchMovie(title, null, language) }.results.firstOrNull() }
            ?: return null
        return call { it.movie(hit.id, language, imageLanguages) }
    }

    /** null when TMDB has no such film. A network failure throws: it must never read as "not found". */
    suspend fun movieById(id: Int): TmdbMovie? = unlessMissing { movie(id) }

    suspend fun tvById(id: Int): TmdbTv? = unlessMissing { tv(id) }

    /** Like [movieById], but an unknown id throws ([NotFoundException]) instead of returning null. */
    suspend fun movie(id: Int): TmdbMovie = call { it.movie(id, language, imageLanguages) }

    suspend fun tv(id: Int): TmdbTv = call { it.tv(id, language, imageLanguages) }

    /** Search results, for manual identification. */
    suspend fun searchMovies(query: String, year: Int?): List<TmdbSearchResult> = call { it.searchMovie(query, year, language) }.results

    suspend fun searchShows(query: String, year: Int?): List<TmdbSearchResult> = call { it.searchTv(query, year, language) }.results

    /** The film or show an IMDb id points to, as a search result. */
    suspend fun findImdb(imdbId: String, show: Boolean): TmdbSearchResult? {
        val r = call { it.find(imdbId) }
        return if (show) r.tvResults.firstOrNull() else r.movieResults.firstOrNull()
    }

    /** Every poster, backdrop and logo of a title: in the device language, in English and without text. */
    suspend fun images(tmdbId: Int, show: Boolean): TmdbImageSet =
        call { if (show) it.tvImages(tmdbId, imageLanguages) else it.movieImages(tmdbId, imageLanguages) }

    /** TMDB ids of the film and show matching an IMDb id. */
    suspend fun findByImdb(imdbId: String): Pair<Int?, Int?> = unlessMissing {
        val r = call { it.find(imdbId) }
        r.movieResults.firstOrNull()?.id to r.tvResults.firstOrNull()?.id
    } ?: (null to null)

    suspend fun findTv(title: String, year: Int?): TmdbTv? {
        val hit = call { it.searchTv(title, year, language) }.results.firstOrNull()
            ?: year?.let { call { it.searchTv(title, null, language) }.results.firstOrNull() }
            ?: return null
        return call { it.tv(hit.id, language, imageLanguages) }
    }

    suspend fun season(tvId: Int, season: Int): TmdbSeason? = unlessMissing { call { it.season(tvId, season, language) } }

    suspend fun collection(id: Int): TmdbCollection? = unlessMissing { call { it.collection(id, language) } }

    /** The answer, or null when TMDB says it has no such entry (404); any other failure throws. */
    private inline fun <T> unlessMissing(block: () -> T): T? = try {
        block()
    } catch (_: NotFoundException) {
        null
    }

    /** Posters of this week's trending films, for the welcome screen's wall. */
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
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val GATEWAY_FAILURES = setOf(502, 503, 504)
        private const val UNREACHABLE_PAUSE_MS = 60_000L

        /** The most of an answer held in memory: a film with its cast and images is well under 1 MB. */
        private const val MAX_BODY_BYTES = 8L * 1024 * 1024

        /** The wait before asking again a gateway that failed, and the longest wait a 429 is granted. */
        private const val GATEWAY_RETRY_MS = 1_000L
        private const val MAX_RETRY_AFTER_MS = 5_000L
        private const val MS_PER_SECOND = 1_000L
        private const val IMAGE_BASE = "https://image.tmdb.org/t/p/"
        private val POSTER_SIZES = listOf(92, 154, 185, 342, 500, 780)
        private val BACKDROP_SIZES = listOf(300, 780, 1280)
        private val LOGO_SIZES = listOf(92, 154, 185, 300, 500)
        private val PROFILE_SIZES = listOf(45, 185)
        private val STILL_SIZES = listOf(92, 185, 300)

        /** A 429's `Retry-After`, in seconds (TMDB does not send a date): a second without one, five at most. */
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
