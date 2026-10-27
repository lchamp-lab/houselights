package app.houselights.providers.jellyfin

import android.os.Build
import app.houselights.BuildConfig
import app.houselights.core.Http
import app.houselights.core.SourceConfig
import app.houselights.core.serverJson
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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

/** The input is not a valid server address ([input] is empty if nothing was typed); no request was made. */
class InvalidServerAddress(val input: String) : IllegalArgumentException("Not a server address: \"$input\"")

/**
 * No candidate answered as Jellyfin, or one's certificate was refused, which ends the search; [url] has the most
 * informative failure (bad certificate over timeout).
 */
class ReachFailure(val url: String, override val cause: Exception) : IOException("$url: ${cause.message}", cause)

/** A server answered at [url] but is unsupported: Jellyfin older than 10.9, or another product. */
class UnsupportedServer(val url: String, val product: String?, val version: String?) :
    Exception("$url runs ${product ?: "an unknown server"} ${version.orEmpty()}")

/** True when TLS failed on the server's certificate: expired, self-signed, or issued for another host. */
fun Throwable.isCertificateFailure(): Boolean = generateSequence(this) { it.cause }
    .any { it is CertificateException || it is CertPathValidatorException || it is SSLPeerUnverifiedException }

/** A client of the server at [serverUrl], as the device [deviceId], or anonymously when null. */
class JellyfinClient(
    serverUrl: String,
    private val deviceId: String?,
    private val token: String?,
    base: OkHttpClient = Http.client,
    connectTimeoutMs: Long = CONNECT_MS,
) {
    val baseUrl: String = normalizeUrl(serverUrl)

    private val server = "$baseUrl/".toHttpUrl()

    private val http: OkHttpClient = base.newBuilder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            // The device's name and id go only to a server it signs in to.
            if (deviceId == null) return@addInterceptor chain.proceed(chain.request())
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
                    .build(),
            )
        }
        // Every request sent, redirects included: the token, a password or the device's name go to the server only.
        // OkHttp would carry X-Emby-Authorization and a 307's body to another host.
        .addNetworkInterceptor { chain ->
            val url = chain.request().url
            if (!staysOn(server, url)) throw IOException("Redirected away from the server, to ${url.redact()}")
            chain.proceed(chain.request())
        }
        .build()

    val api: JellyfinApi = Retrofit.Builder()
        .baseUrl("$baseUrl/")
        .client(http)
        .addConverterFactory(serverJson.asConverterFactory("application/json".toMediaType()))
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
        private const val HTTP_PORT = 8096
        private const val HTTPS_PORT = 8920
        private const val SIGN_OUT_MS = 3_000L
        private const val CONNECT_MS = 10_000L

        /** Short connect timeout for the HTTPS probe, so a firewall dropping it does not delay the HTTP fallback. */
        private const val PROBE_CONNECT_MS = 3_000L

        /** How long checking a server found waits on each of its addresses, lookup and answer included. */
        private const val CHECK_MS = 3_000L

        /** ProductName reported by System/Info/Public on Jellyfin. */
        private const val PRODUCT = "Jellyfin Server"

        /** 10.9 is the first version with the routes of [JellyfinApi] (UserViews, UserItems, UserPlayedItems). */
        private const val MIN_MAJOR = 10
        private const val MIN_MINOR = 9

        /** True for "nas:8096" and "[fd00::5]:8096", false for "nas" and "[fd00::5]". */
        private fun hasPort(host: String) = if (host.startsWith("[")) host.substringAfter(']').startsWith(":") else ':' in host

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
         * HTTPS first: "nas" gives https://nas:8920 then http://nas:8096 (default ports); "nas:8096" gives both schemes
         * on 8096. Input with a scheme is used as is.
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
         * Checks a server found on the network as signing in will reach it, anonymously, [CHECK_MS] at most an address:
         * its HTTPS address first, the one it announces or else port 8920 on its host, then the HTTP one it announces.
         * Encrypted only when HTTPS answers with a certificate the player trusts and as that server, its id the one
         * discovery gave, so another service on 8920 does not pass; plain when only HTTP answers, a refused
         * certificate included; silent when neither does.
         */
        suspend fun check(found: DiscoveredServer, http: OkHttpClient = Http.client, waitMs: Long = CHECK_MS): ServerCheck {
            val announced = normalizeUrl(found.address)
            val url = "$announced/".toHttpUrlOrNull() ?: return ServerCheck.Silent

            // Cancelling the coroutine, not OkHttp's call timeout, which cannot cut a name's lookup short.
            suspend fun info(at: String) = withTimeoutOrNull(waitMs) { publicInfo(at, deviceId = null, http, waitMs) }?.getOrNull()
            val https = if (url.isHttps) announced else url.newBuilder().scheme("https").port(HTTPS_PORT).build().toString().trimEnd('/')
            if (info(https)?.id?.equals(found.id, ignoreCase = true) == true) return ServerCheck.Encrypted(https)
            return if (!url.isHttps && info(announced) != null) ServerCheck.Plain(announced) else ServerCheck.Silent
        }

        /**
         * The first supported server among [candidateUrls], with its public info. Throws [InvalidServerAddress],
         * [UnsupportedServer], or [ReachFailure] when none answers or a certificate is refused: plain HTTP after it
         * would carry the password in the clear, so only typing http:// chooses it, or picking a server found that
         * [check] found plain, or silent twice.
         */
        suspend fun reach(input: String, deviceId: String, http: OkHttpClient = Http.client): Pair<String, PublicSystemInfo> {
            val candidates = checkedCandidates(input)
            val failures = mutableListOf<Pair<String, Exception>>()
            for ((i, url) in candidates.withIndex()) {
                val connect = if (i < candidates.lastIndex) PROBE_CONNECT_MS else CONNECT_MS
                val info = publicInfo(url, deviceId, http, connect).onFailure { failures += url to it as Exception }.getOrNull()
                if (info != null) {
                    if (!isSupported(info)) throw UnsupportedServer(url, info.productName, info.version)
                    return url to info
                }
                if (failures.last().second.isCertificateFailure()) break
            }
            // On a tie, report the last address tried: the plain HTTP one, closest to the input.
            val (url, cause) = failures.asReversed().maxBy { (_, e) -> e.meaning() }
            throw ReachFailure(url, cause)
        }

        /** [candidateUrls] for [input]; throws [InvalidServerAddress] unless all of them parse as URLs. */
        private fun checkedCandidates(input: String): List<String> {
            val candidates = candidateUrls(input)
            if (candidates.isEmpty() || candidates.any { "$it/".toHttpUrlOrNull() == null }) throw InvalidServerAddress(input.trim())
            return candidates
        }

        /** Public info of the server at [url]; failures a server can cause are returned, others are thrown. */
        private suspend fun publicInfo(url: String, deviceId: String?, http: OkHttpClient, connectMs: Long): Result<PublicSystemInfo> =
            try {
                Result.success(JellyfinClient(url, deviceId, token = null, http, connectMs).api.publicInfo())
            } catch (e: IOException) {
                Result.failure(e) // No answer, or TLS failed.
            } catch (e: HttpException) {
                Result.failure(e)
            } catch (e: SerializationException) {
                Result.failure(e) // Something answered, but not Jellyfin.
            }

        /** Failure informativeness, least first. */
        private enum class Meaning { SILENCE, TLS_FAILED, ANSWER, CERTIFICATE_REFUSED }

        private fun Exception.meaning(): Meaning = when {
            isCertificateFailure() -> Meaning.CERTIFICATE_REFUSED
            this is HttpException || this is SerializationException -> Meaning.ANSWER
            this is SSLException -> Meaning.TLS_FAILED
            else -> Meaning.SILENCE
        }

        /** Jellyfin 10.9 or later; a missing or unparsable version is accepted. */
        internal fun isSupported(info: PublicSystemInfo): Boolean {
            val jellyfin = info.productName == null || info.productName == PRODUCT
            val (major, minor) = info.version?.split('.', '-')?.take(2)?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }
                ?: return jellyfin
            return jellyfin && (major > MIN_MAJOR || major == MIN_MAJOR && minor >= MIN_MINOR)
        }

        fun isEncrypted(url: String): Boolean = url.startsWith("https://")

        /** True when [url] is on [server]: its host, and over HTTPS when the server is reached so. */
        internal fun staysOn(server: HttpUrl, url: HttpUrl): Boolean = url.host == server.host && (url.isHttps || !server.isHttps)

        /** Asks the server for the token's account: an [HttpException] once it refuses the token, an [IOException] when silent. */
        suspend fun checkToken(config: SourceConfig.Jellyfin, deviceId: String) {
            JellyfinClient(config.serverUrl, deviceId, config.accessToken).api.me()
        }

        /** Revokes the token on the server, giving up after a short timeout if it is unreachable. */
        suspend fun signOut(config: SourceConfig.Jellyfin, deviceId: String) {
            withTimeoutOrNull(SIGN_OUT_MS) {
                try {
                    JellyfinClient(config.serverUrl, deviceId, config.accessToken).api.logout()
                } catch (_: IOException) {
                    // The token stays valid; it can be revoked from Jellyfin's dashboard.
                } catch (_: HttpException) {
                    // Already revoked.
                }
            }
        }
    }
}
