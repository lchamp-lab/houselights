package app.houselights.providers.jellyfin

import android.os.Build
import app.houselights.BuildConfig
import app.houselights.core.Http
import app.houselights.core.SourceConfig
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/** HTTP access to one Jellyfin server, with the MediaBrowser authorization header. */
class JellyfinClient(serverUrl: String, private val deviceId: String, private val token: String?) {
    val baseUrl: String = normalizeUrl(serverUrl)

    private val http: OkHttpClient = Http.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val auth = buildString {
                append("MediaBrowser Client=\"Houselights\", Device=\"")
                append(Build.MODEL.replace("\"", ""))
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

        /** Adds the scheme and the default Jellyfin port when missing. */
        fun normalizeUrl(input: String): String {
            var url = input.trim().trimEnd('/')
            if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            val hostPart = url.substringAfter("://").substringBefore('/')
            if (url.startsWith("http://") && hostPart.isNotEmpty() && !hostPart.contains(':')) {
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
            val https = if (host.contains(':')) "https://$typed" else "https://$host:$HTTPS_PORT$rest"
            return listOf(https, normalizeUrl(typed))
        }

        /**
         * The first of [candidateUrls] that answers as a Jellyfin server, with what it says about itself.
         * Throws the error of the last address tried when none answers.
         */
        suspend fun reach(input: String, deviceId: String): Pair<String, PublicSystemInfo> {
            var failure: Exception = IllegalArgumentException("No server address")
            for (url in candidateUrls(input)) {
                try {
                    return url to JellyfinClient(url, deviceId, token = null).api.publicInfo()
                } catch (e: IOException) {
                    failure = e // Nothing answers there, or not over TLS.
                } catch (e: HttpException) {
                    failure = e
                } catch (e: SerializationException) {
                    failure = e // Something answers, but not Jellyfin.
                }
            }
            throw failure
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
