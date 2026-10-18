package app.houselights.core

import okhttp3.OkHttpClient

/** The app's single connection and thread pool; services derive their own client with [OkHttpClient.newBuilder]. */
object Http {
    val client: OkHttpClient by lazy { OkHttpClient() }
}

/** HTTP statuses the app distinguishes, whichever server sends them. */
object HttpStatus {
    const val OK = 200
    const val UNAUTHORIZED = 401
    const val FORBIDDEN = 403
    const val NOT_FOUND = 404
    const val TOO_MANY_REQUESTS = 429
}
