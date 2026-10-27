package app.houselights.providers.trakt

import app.houselights.BuildConfig
import app.houselights.core.Http
import app.houselights.core.serverJson
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * The app's keys on Trakt: [clientId], a [clientSecret] only for an app that has one, and the [redirectUri] the app
 * was declared with, which renewing the tokens names.
 */
class TraktKeys(val clientId: String, val clientSecret: String? = null, val redirectUri: String = TraktClient.REDIRECT_URI) {
    /** Without a client ID, this build cannot reach Trakt. */
    val available: Boolean get() = clientId.isNotBlank()

    override fun toString() = "TraktKeys(clientId=$clientId, clientSecret=${clientSecret?.let { "***" }})"

    companion object {
        /** The keys built into the APK (README, Trakt). */
        val builtIn = TraktKeys(
            BuildConfig.TRAKT_CLIENT_ID,
            BuildConfig.TRAKT_CLIENT_SECRET.ifBlank { null },
            BuildConfig.TRAKT_REDIRECT_URI.ifBlank { TraktClient.REDIRECT_URI },
        )
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
    internal val http: OkHttpClient = base.newBuilder()
        .connectTimeout(CONNECT_S, TimeUnit.SECONDS)
        .readTimeout(READ_S, TimeUnit.SECONDS)
        // A redirect would replay a token or a secret to another host, or in the clear.
        .followRedirects(false)
        .followSslRedirects(false)
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

        /**
         * The project's Trakt app's redirect URI, which the device flow never opens but renewing names; another app
         * gives its own as trakt.redirectUri (README, Trakt).
         */
        const val REDIRECT_URI = "https://github.com/lchamp-lab/houselights"

        private const val CONNECT_S = 10L
        private const val READ_S = 30L

        private fun headers(clientId: String) = Interceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Houselights/${BuildConfig.VERSION_NAME}")
                    .header("trakt-api-version", "2")
                    .header("trakt-api-key", clientId)
                    .build(),
            )
        }

        private fun retrofit(url: String, http: OkHttpClient): Retrofit = Retrofit.Builder()
            .baseUrl(url)
            .client(http)
            // Nulls left out (a missing client secret is not sent), defaults sent (grant_type).
            .addConverterFactory(serverJson.asConverterFactory("application/json".toMediaType()))
            .build()
    }
}
