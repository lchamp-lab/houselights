package app.houselights

import app.houselights.providers.local.TmdbClient
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Pictures' calls ([imageHttpClient]). TMDB's CDN, over HTTP/2, takes [TMDB_AT_ONCE] at a time: a cold home asks it for
 * some forty pictures. A Jellyfin server, which resizes each one, and the NAS keep OkHttp's five per host.
 */
internal class ImageCalls(api: OkHttpClient) : Call.Factory {
    private val images = imageHttpClient(api)
    private val tmdb = images.newBuilder()
        .dispatcher(Dispatcher(api.dispatcher.executorService).apply { maxRequestsPerHost = TMDB_AT_ONCE })
        .build()

    /** The client a picture at [url] is asked through. */
    fun client(url: HttpUrl): OkHttpClient = if (url.host == TmdbClient.IMAGE_HOST) tmdb else images

    override fun newCall(request: Request): Call = client(request.url).newCall(request)

    private companion object {
        const val TMDB_AT_ONCE = 8
    }
}
