package app.houselights.providers.trakt

import app.houselights.core.Decoded
import app.houselights.core.decodeCounted
import app.houselights.core.serverJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/** Trakt's OAuth routes, on its own host ([TraktClient.AUTH_URL]). */
interface TraktAuthApi {
    @POST("oauth/device/code")
    suspend fun deviceCode(@Body body: DeviceCodeRequest): DeviceCode

    /** Its status says where the code stands, so the response is kept whole ([TraktAccount.awaitApproval]). */
    @POST("oauth/device/token")
    suspend fun deviceToken(@Body body: DeviceTokenRequest): Response<TraktTokens>

    /** A refused refresh token answers 400 (invalid_grant). */
    @POST("oauth/token")
    suspend fun refresh(@Body body: RefreshRequest): TraktTokens

    @POST("oauth/revoke")
    suspend fun revoke(@Body body: RevokeRequest)
}

/** The account's routes, each sent with its access token ([TraktAccount.call]). */
interface TraktApi {
    @GET("users/settings")
    suspend fun settings(): UserSettings

    /** When the account's history and paused playbacks last changed: a pull is skipped when they did not. */
    @GET("sync/last_activities")
    suspend fun lastActivities(): LastActivities

    // What every app sent Trakt, read entry by entry ([watchedMovies], [watchedShows], [playback]).

    @GET("sync/watched/movies")
    suspend fun watchedMovieEntries(): List<JsonElement>

    @GET("sync/watched/shows")
    suspend fun watchedShowEntries(): List<JsonElement>

    @GET("sync/playback")
    suspend fun playbackEntries(): List<JsonElement>

    // Writes: their status is all a sync reads, their body is dropped unread.

    /** Forgets a paused playback ([PlaybackProgress.id]); 404 once gone. */
    @DELETE("sync/playback/{id}")
    suspend fun removePlayback(@Path("id") id: Long)

    @POST("sync/history")
    suspend fun addToHistory(@Body body: HistoryItems)

    @POST("sync/history/remove")
    suspend fun removeFromHistory(@Body body: HistoryItems)

    /** 409 when the same title was scrobbled moments ago; 422 under 1 %. */
    @POST("scrobble/stop")
    suspend fun scrobbleStop(@Body body: ScrobbleRequest)
}

// An entry this version cannot read is left out and logged, rather than the whole list. A list can hold thousands of
// entries: they are decoded off the caller's thread, which is the main one for a sync.

suspend fun TraktApi.watchedMovies(): Decoded<WatchedMovie> {
    val entries = watchedMovieEntries()
    return withContext(Dispatchers.Default) {
        serverJson.decodeCounted(WatchedMovie.serializer(), entries, "Trakt sync/watched/movies")
    }
}

suspend fun TraktApi.watchedShows(): Decoded<WatchedShow> {
    val entries = watchedShowEntries()
    return withContext(Dispatchers.Default) {
        serverJson.decodeCounted(WatchedShow.serializer(), entries, "Trakt sync/watched/shows")
    }
}

suspend fun TraktApi.playback(): Decoded<PlaybackProgress> {
    val entries = playbackEntries()
    return withContext(Dispatchers.Default) {
        serverJson.decodeCounted(PlaybackProgress.serializer(), entries, "Trakt sync/playback")
    }
}
