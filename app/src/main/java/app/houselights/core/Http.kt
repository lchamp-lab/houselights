package app.houselights.core

import okhttp3.OkHttpClient

/**
 * The app's one HTTP client: a single pool of connections and threads. Each service derives its own
 * timeouts and headers from it with [OkHttpClient.newBuilder], which shares both.
 */
object Http {
    val client: OkHttpClient by lazy { OkHttpClient() }
}

/** The HTTP statuses the app tells apart, by name, whichever server answers them. */
object HttpStatus {
    const val OK = 200
    const val UNAUTHORIZED = 401
    const val FORBIDDEN = 403
    const val NOT_FOUND = 404
    const val TOO_MANY_REQUESTS = 429
}
