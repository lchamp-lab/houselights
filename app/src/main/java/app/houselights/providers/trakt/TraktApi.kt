package app.houselights.providers.trakt

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

    @GET("sync/watched/movies")
    suspend fun watchedMovies(): List<WatchedMovie>

    @GET("sync/watched/shows")
    suspend fun watchedShows(): List<WatchedShow>

    @GET("sync/playback")
    suspend fun playback(): List<PlaybackProgress>

    /** Forgets a paused playback ([PlaybackProgress.id]); 404 once gone. */
    @DELETE("sync/playback/{id}")
    suspend fun removePlayback(@Path("id") id: Long)

    @POST("sync/history")
    suspend fun addToHistory(@Body body: HistoryItems): HistoryAdded

    @POST("sync/history/remove")
    suspend fun removeFromHistory(@Body body: HistoryItems): HistoryRemoved

    /** 409 when the same title was scrobbled moments ago; 422 under 1 %. */
    @POST("scrobble/stop")
    suspend fun scrobbleStop(@Body body: ScrobbleRequest): ScrobbleResult
}
