package app.posterwall.providers.jellyfin

import android.os.Build
import app.posterwall.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** HTTP access to one Jellyfin server, with the MediaBrowser authorization header. */
class JellyfinClient(serverUrl: String, private val deviceId: String, private val token: String?) {
    val baseUrl: String = normalizeUrl(serverUrl)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val auth = buildString {
                append("MediaBrowser Client=\"Posterwall\", Device=\"")
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

        /** Adds the scheme and the default Jellyfin port when missing. */
        fun normalizeUrl(input: String): String {
            var url = input.trim().trimEnd('/')
            if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            val hostPart = url.substringAfter("://").substringBefore('/')
            if (url.startsWith("http://") && hostPart.isNotEmpty() && !hostPart.contains(':')) {
                url = url.replaceFirst(hostPart, "$hostPart:8096")
            }
            return url
        }
    }
}
