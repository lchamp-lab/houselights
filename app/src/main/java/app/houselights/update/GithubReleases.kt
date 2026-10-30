package app.houselights.update

import app.houselights.BuildConfig
import app.houselights.core.Http
import app.houselights.core.HttpStatus
import app.houselights.core.decodeEach
import app.houselights.core.lenientJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A release as GitHub's API lists it, reduced to what the updater reads (and keeps between checks). */
@Serializable
data class ListedRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<ListedAsset> = emptyList(),
)

/** A file of a release: its [digest] is GitHub's own SHA-256 of it ("sha256:…"), when GitHub gives one. */
@Serializable
data class ListedAsset(
    val name: String,
    val size: Long,
    @SerialName("browser_download_url") val url: String,
    val digest: String? = null,
)

/** GitHub's answer to the list of releases: [Fresh], or [Unchanged] since the list its ETag names. */
sealed interface Listing {
    data class Fresh(val releases: List<ListedRelease>, val etag: String?) : Listing

    data object Unchanged : Listing
}

/** Where the releases are listed and their manifests read: GitHub ([GithubReleases]). */
interface ReleaseSource {
    /** The last releases; [etag] of the list kept, so an unchanged one answers [Listing.Unchanged]. */
    suspend fun list(etag: String?): Listing

    /** A release's manifest, as text, from its asset's address. */
    suspend fun manifest(url: String): String
}

/** GitHub answered [code]; [waitUntil] (the device's clock) when it asks to wait: its rate limit. */
class GithubStatusException(val code: Int, val waitUntil: Long?) : IOException("GitHub answered $code")

/** A request, or one of its redirects, would leave GitHub's hosts or HTTPS ([GithubHops]). */
class RefusedHopException(host: String) : IOException("Refused a hop to $host, outside GitHub")

/** An answer longer than the updater reads of it. */
class TooLargeException(max: Long) : IOException("Answer over $max bytes")

/**
 * The releases of [repo] on GitHub, read anonymously: one list of the last releases (with an ETag), then a release's
 * manifest (update.json), both small and capped; [http] downloads its APK too. Every request names the app and carries
 * no credential; every hop, redirects included, stays on GitHub over HTTPS ([GithubHops]).
 */
class GithubReleases(
    val repo: String = BuildConfig.UPDATE_REPO,
    /** The client [http] derives from; null for the app's ([Http.client]), not built before the first request. */
    base: OkHttpClient? = null,
    private val api: HttpUrl = API.toHttpUrl(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Tests only: a local server speaks plain HTTP. */
    plainHttp: Boolean = false,
) : ReleaseSource {
    val http: OkHttpClient by lazy {
        (base ?: Http.client).newBuilder()
            .connectTimeout(CONNECT_S, TimeUnit.SECONDS)
            // Also the download's stall timeout: a body that stops for this long fails, however long it is.
            .readTimeout(READ_S, TimeUnit.SECONDS)
            // A redirect from HTTPS to plain HTTP is refused; one to GitHub's download hosts is followed.
            .followSslRedirects(false)
            .apply { interceptors().add(0, headers()) }
            .addNetworkInterceptor(GithubHops(plainHttp))
            .build()
    }

    /** Where the files of a release are served: what a manifest's and an APK's addresses must start with. */
    fun downloads(tag: String): String = "https://github.com/$repo/releases/download/$tag/"

    override suspend fun list(etag: String?): Listing {
        val url = api.newBuilder().addPathSegments("repos/$repo/releases").addQueryParameter("per_page", "$LISTED").build()
        val request = Request.Builder().url(url).apply { if (etag != null) header("If-None-Match", etag) }.build()
        return http.newCall(request).await().use { response ->
            if (response.code == NOT_MODIFIED && etag != null) return@use Listing.Unchanged
            if (!response.isSuccessful) throw statusFailure(response, clock())
            val text = response.text(LIST_MAX_BYTES)
            // Up to a megabyte, each release with its notes: read off the caller's thread, which may be the main one.
            val releases = withContext(Dispatchers.Default) {
                val array = lenientJson.parseToJsonElement(text) as? JsonArray
                    ?: throw SerializationException("Expected a list of releases")
                lenientJson.decodeEach(ListedRelease.serializer(), array, "Release")
            }
            Listing.Fresh(releases, response.header("ETag"))
        }
    }

    /** [url] is the manifest's asset's address, under [downloads]. */
    override suspend fun manifest(url: String): String = http.newCall(Request.Builder().url(url).build()).await().use { response ->
        if (!response.isSuccessful) throw statusFailure(response, clock())
        response.text(MANIFEST_MAX_BYTES)
    }

    /** The headers GitHub asks of every request; the API's own only to the API, and never an Authorization. */
    private fun headers() = Interceptor { chain ->
        val request = chain.request()
        val named = request.newBuilder().header("User-Agent", "Houselights/${BuildConfig.VERSION_NAME}")
        if (request.url.host == api.host) {
            named.header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", API_VERSION)
        }
        chain.proceed(named.build())
    }

    companion object {
        const val API = "https://api.github.com/"
        const val API_VERSION = "2022-11-28"

        /** The releases listed: a beta channel finds its newest among them, a stable one its newest final. */
        const val LISTED = 10
        const val LIST_MAX_BYTES = 1L shl 20
        const val MANIFEST_MAX_BYTES = 64L shl 10
        private const val CONNECT_S = 10L
        private const val READ_S = 30L
        private const val NOT_MODIFIED = 304
    }
}

/**
 * The hosts the updater talks to, on every hop a request makes (a network interceptor sees each redirect): GitHub's API
 * and pages, and the hosts its downloads redirect to, over HTTPS only. Anything else fails with [RefusedHopException].
 */
class GithubHops(private val plainHttp: Boolean = false) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!allowed(url, plainHttp)) throw RefusedHopException(url.host)
        return chain.proceed(chain.request())
    }

    companion object {
        /** Whether [url] is on GitHub's hosts, over HTTPS unless [plainHttp] (a test's local server). */
        fun allowed(url: HttpUrl, plainHttp: Boolean = false): Boolean {
            val host = url.host
            val github = host == "github.com" || host == "api.github.com" || host.endsWith(".githubusercontent.com")
            return github && (url.isHttps || plainHttp)
        }
    }
}

/**
 * GitHub's refusal as the updater reads it: its rate limit (a 403 with no request left, a 429, or a 403 with
 * Retry-After, a secondary limit) waits until its reset, clamped to between a minute and an hour from [now].
 */
internal fun statusFailure(response: Response, now: Long): GithubStatusException {
    val code = response.code
    val reset = response.header("x-ratelimit-reset")?.toLongOrNull()?.let { it * MS_PER_S }
    val retryAfter = response.header("Retry-After")?.toLongOrNull()?.let { now + it * MS_PER_S }
    val exhausted = response.header("x-ratelimit-remaining") == "0"
    val waits = exhausted || retryAfter != null
    val limited = code == HttpStatus.TOO_MANY_REQUESTS || code == HttpStatus.FORBIDDEN && waits
    if (!limited) return GithubStatusException(code, null)
    val until = (if (exhausted) reset ?: retryAfter else retryAfter ?: reset) ?: (now + MIN_WAIT_MS)
    return GithubStatusException(code, until.coerceIn(now + MIN_WAIT_MS, now + MAX_WAIT_MS))
}

/** The body as text, failing past [max] bytes rather than reading a whole unexpected answer into memory. */
private suspend fun Response.text(max: Long): String = withContext(Dispatchers.IO) {
    val source = body?.source() ?: return@withContext ""
    if (source.request(max + 1)) throw TooLargeException(max)
    source.buffer.readUtf8()
}

/** Runs the call, cancelled with the coroutine; the response is closed if it comes after. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        },
    )
}

private const val MS_PER_S = 1_000L
private const val MIN_WAIT_MS = 60_000L

/** The longest GitHub is waited for: its rate limit resets within the hour. */
internal const val MAX_WAIT_MS = 60 * 60_000L
