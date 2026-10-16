package app.houselights.providers.jellyfin

import android.os.Build
import app.houselights.BuildConfig
import app.houselights.core.Http
import app.houselights.core.SourceConfig
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/** What was typed is not a server address ([input] is empty when nothing was): the network was not asked. */
class InvalidServerAddress(val input: String) : IllegalArgumentException("Not a server address: \"$input\"")

/**
 * No address tried for what was typed answered as a Jellyfin server. [url] is the one whose failure
 * ([cause]) says the most: a certificate refused over nothing answering, for one.
 */
class ReachFailure(val url: String, override val cause: Exception) : IOException("$url: ${cause.message}", cause)

/** A server answered at [url], but not one Houselights works with: a Jellyfin before 10.9, or another product. */
class UnsupportedServer(val url: String, val product: String?, val version: String?) :
    Exception("$url runs ${product ?: "an unknown server"} ${version.orEmpty()}")

/** True when TLS failed on the server's certificate: expired, self-signed, or made out to another name. */
fun Throwable.isCertificateFailure(): Boolean = generateSequence(this) { it.cause }
    .any { it is CertificateException || it is CertPathValidatorException || it is SSLPeerUnverifiedException }

/** HTTP access to one Jellyfin server, with the MediaBrowser authorization header. */
class JellyfinClient(
    serverUrl: String,
    private val deviceId: String,
    private val token: String?,
    base: OkHttpClient = Http.client,
    connectTimeoutMs: Long = CONNECT_MS,
) {
    val baseUrl: String = normalizeUrl(serverUrl)

    private val http: OkHttpClient = base.newBuilder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val auth = buildString {
                append("MediaBrowser Client=\"Houselights\", Device=\"")
                append(Build.MODEL.orEmpty().replace("\"", ""))
                append("\", DeviceId=\"").append(deviceId)
                append("\", Version=\"").append(BuildConfig.VERSION_NAME).append('"')
                token?.let { append(", Token=\"").append(it).append('"') }
            }
            chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", auth)
                    .header("X-Emby-Authorization", auth)
                    .build()
            )
        }
        .build()

    val api: JellyfinApi = Retrofit.Builder()
        .baseUrl("$baseUrl/")
        .client(http)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(JellyfinApi::class.java)

    fun imageUrl(itemId: String, type: String, tag: String?, maxWidth: Int): String = buildString {
        append(baseUrl).append("/Items/").append(itemId).append("/Images/").append(type)
        append("?maxWidth=").append(maxWidth).append("&quality=90")
        if (tag != null) append("&tag=").append(tag)
    }

    fun streamUrl(itemId: String, mediaSourceId: String?, container: String?): String = buildString {
        append(baseUrl).append("/Videos/").append(itemId).append("/stream")
        container?.let { append('.').append(it) }
        append("?static=true")
        mediaSourceId?.let { append("&mediaSourceId=").append(it) }
        token?.let { append("&api_key=").append(it) }
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
            encodeDefaults = true
        }

        private const val HTTP_PORT = 8096
        private const val HTTPS_PORT = 8920
        private const val SIGN_OUT_MS = 3_000L
        private const val CONNECT_MS = 10_000L

        /**
         * The encrypted address tried first is a probe: a firewall that drops it without a word must not
         * hold the plain address behind it for long.
         */
        private const val PROBE_CONNECT_MS = 3_000L

        /** What answers System/Info/Public on a Jellyfin server. */
        private const val PRODUCT = "Jellyfin Server"

        /** The first version with the routes of [JellyfinApi] (UserViews, UserItems, UserPlayedItems): 10.9. */
        private const val MIN_MAJOR = 10
        private const val MIN_MINOR = 9

        /** "nas:8096" and "[fd00::5]:8096" name their port; "nas" and "[fd00::5]" do not. */
        private fun hasPort(host: String) = if (host.startsWith("[")) host.substringAfter(']').startsWith(":") else ':' in host

        /** Adds the scheme and the default Jellyfin port when missing. */
        fun normalizeUrl(input: String): String {
            var url = input.trim().trimEnd('/')
            if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            val hostPart = url.substringAfter("://").substringBefore('/')
            if (url.startsWith("http://") && hostPart.isNotEmpty() && !hasPort(hostPart)) {
                url = url.replaceFirst(hostPart, "$hostPart:$HTTP_PORT")
            }
            return url
        }

        /**
         * Addresses to try for what the user typed, encrypted first. "nas" gives https://nas:8920 then
         * http://nas:8096 (Jellyfin's default ports); "nas:8096" gives https://nas:8096 then
         * http://nas:8096. An address typed with its scheme is used as it is.
         */
        fun candidateUrls(input: String): List<String> {
            val typed = input.trim().trimEnd('/')
            if (typed.isEmpty()) return emptyList()
            if (typed.startsWith("http://") || typed.startsWith("https://")) return listOf(normalizeUrl(typed))
            val host = typed.substringBefore('/')
            val rest = typed.removePrefix(host)
            val https = if (hasPort(host)) "https://$typed" else "https://$host:$HTTPS_PORT$rest"
            return listOf(https, normalizeUrl(typed))
        }

        /**
         * The first of [candidateUrls] that answers as a Jellyfin server Houselights works with, with
         * what it says about itself. What was typed is checked first ([InvalidServerAddress]); a server
         * too old or of another product is [UnsupportedServer]; when none answers, [ReachFailure]
         * names the address whose failure says the most.
         */
        suspend fun reach(input: String, deviceId: String, http: OkHttpClient = Http.client): Pair<String, PublicSystemInfo> {
            val candidates = checkedCandidates(input)
            val failures = mutableListOf<Pair<String, Exception>>()
            for ((i, url) in candidates.withIndex()) {
                val connect = if (i < candidates.lastIndex) PROBE_CONNECT_MS else CONNECT_MS
                val info = publicInfo(url, deviceId, http, connect).onFailure { failures += url to it as Exception }.getOrNull() ?: continue
                if (!isSupported(info)) throw UnsupportedServer(url, info.productName, info.version)
                return url to info
            }
            // On a tie, the address tried last: the plain one, as typed.
            val (url, cause) = failures.asReversed().maxBy { (_, e) -> e.meaning() }
            throw ReachFailure(url, cause)
        }

        /** The addresses to try for [input], all of them addresses: else nothing is asked of the network. */
        private fun checkedCandidates(input: String): List<String> {
            val candidates = candidateUrls(input)
            if (candidates.isEmpty() || candidates.any { "$it/".toHttpUrlOrNull() == null }) throw InvalidServerAddress(input.trim())
            return candidates
        }

        /** What the server at [url] says about itself; a failure that a server can cause, else it is thrown. */
        private suspend fun publicInfo(url: String, deviceId: String, http: OkHttpClient, connectMs: Long): Result<PublicSystemInfo> =
            try {
                Result.success(JellyfinClient(url, deviceId, token = null, http, connectMs).api.publicInfo())
            } catch (e: IOException) {
                Result.failure(e) // Nothing answers there, or not over TLS.
            } catch (e: HttpException) {
                Result.failure(e)
            } catch (e: SerializationException) {
                Result.failure(e) // Something answers, but not Jellyfin.
            }

        /** How much a failure at an address says, from least to most. */
        private enum class Meaning { SILENCE, TLS_FAILED, ANSWER, CERTIFICATE_REFUSED }

        private fun Exception.meaning(): Meaning = when {
            isCertificateFailure() -> Meaning.CERTIFICATE_REFUSED
            this is HttpException || this is SerializationException -> Meaning.ANSWER
            this is SSLException -> Meaning.TLS_FAILED
            else -> Meaning.SILENCE
        }

        /** Jellyfin 10.9 or later; a server that does not say its version clearly is given the benefit of the doubt. */
        internal fun isSupported(info: PublicSystemInfo): Boolean {
            val jellyfin = info.productName == null || info.productName == PRODUCT
            val (major, minor) = info.version?.split('.', '-')?.take(2)?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }
                ?: return jellyfin
            return jellyfin && (major > MIN_MAJOR || major == MIN_MAJOR && minor >= MIN_MINOR)
        }

        fun isEncrypted(url: String): Boolean = url.startsWith("https://")

        /** Asks the server to forget the token, without waiting long for a server that is away. */
        suspend fun signOut(config: SourceConfig.Jellyfin, deviceId: String) {
            withTimeoutOrNull(SIGN_OUT_MS) {
                try {
                    JellyfinClient(config.serverUrl, deviceId, config.accessToken).api.logout()
                } catch (_: IOException) {
                    // The token stays valid on the server; it can be revoked from Jellyfin's dashboard.
                } catch (_: HttpException) {
                    // The server had already forgotten it.
                }
            }
        }
    }
}
