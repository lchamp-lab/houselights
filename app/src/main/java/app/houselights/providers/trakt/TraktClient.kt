package app.houselights.providers.trakt

import app.houselights.BuildConfig
import app.houselights.core.Http
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** The app's keys on Trakt; only [clientId] is needed, [clientSecret] only for an app Trakt issued one to. */
class TraktKeys(val clientId: String, val clientSecret: String? = null) {
    /** Without a client ID, this build cannot reach Trakt. */
    val available: Boolean get() = clientId.isNotBlank()

    override fun toString() = "TraktKeys(clientId=$clientId, clientSecret=${clientSecret?.let { "***" }})"

    companion object {
        /** The keys built into the APK (README, Trakt). */
        val builtIn = TraktKeys(BuildConfig.TRAKT_CLIENT_ID, BuildConfig.TRAKT_CLIENT_SECRET.ifBlank { null })
    }
}

/**
 * Trakt's two hosts with the headers it asks of every request; [token] is the account's access token, sent to
 * the API only.
 */
class TraktClient(
    keys: TraktKeys,
    token: () -> String?,
    base: OkHttpClient = Http.client,
) {
    private val http: OkHttpClient = base.newBuilder()
        .connectTimeout(CONNECT_S, TimeUnit.SECONDS)
        .readTimeout(READ_S, TimeUnit.SECONDS)
        // First, so whatever the base client intercepts already carries them.
        .apply { interceptors().add(0, headers(keys.clientId)) }
        .build()

    val auth: TraktAuthApi = retrofit(AUTH_URL, http).create(TraktAuthApi::class.java)

    val api: TraktApi = retrofit(
        API_URL,
        http.newBuilder()
            .apply {
                interceptors().add(
                    1,
                    Interceptor { chain ->
                        val request = chain.request()
                        chain.proceed(token()?.let { request.newBuilder().header("Authorization", "Bearer $it").build() } ?: request)
                    },
                )
            }
            .build(),
    ).create(TraktApi::class.java)

    companion object {
        const val API_URL = "https://api.trakt.tv/"
        const val AUTH_URL = "https://auth.trakt.tv/"

        /** Trakt's page a code is entered on, as typed; `trakt_qr` encodes it, as Trakt asks of a QR code. */
        const val ACTIVATE = "auth.trakt.tv/activate"

        /** Refreshing names the redirect URI of the Trakt app, which the device flow never opens (README, Trakt). */
        const val REDIRECT_URI = "https://github.com/lchamp-lab/houselights"

        private const val CONNECT_S = 10L
        private const val READ_S = 30L

        /** Nulls left out (a missing client secret is not sent), defaults sent (grant_type). */
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
            encodeDefaults = true
        }

        private fun headers(clientId: String) = Interceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Houselights/${BuildConfig.VERSION_NAME}")
                    .header("trakt-api-version", "2")
                    .header("trakt-api-key", clientId)
                    .build()
            )
        }

        private fun retrofit(url: String, http: OkHttpClient): Retrofit = Retrofit.Builder()
            .baseUrl(url)
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }
}
