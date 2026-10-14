package app.houselights.core

import okhttp3.OkHttpClient

/**
 * The app's one HTTP client: a single pool of connections and threads. Each service derives its own
 * timeouts and headers from it with [OkHttpClient.newBuilder], which shares both.
 */
object Http {
    val client: OkHttpClient by lazy { OkHttpClient() }
}
