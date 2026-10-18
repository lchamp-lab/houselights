package app.houselights.providers.jellyfin

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Subset of the Jellyfin REST API (10.9+ routes). Write routes return Unit so that Retrofit throws an
 * HttpException on any non-2xx answer, which a Response return type would hide.
 */
interface JellyfinApi {
    @GET("System/Info/Public")
    suspend fun publicInfo(): PublicSystemInfo

    @POST("Users/AuthenticateByName")
    suspend fun authenticate(@Body body: AuthRequest): AuthResult

    /** Invalidates the access token sent with the request. */
    @POST("Sessions/Logout")
    suspend fun logout()

    @GET("UserViews")
    suspend fun views(@Query("userId") userId: String): ItemsResult

    @GET("UserItems/Resume")
    suspend fun resume(
        @Query("userId") userId: String,
        @Query("limit") limit: Int,
        @Query("fields") fields: String,
        @Query("mediaTypes") mediaTypes: String = "Video",
    ): ItemsResult

    @GET("Shows/NextUp")
    suspend fun nextUp(
        @Query("userId") userId: String,
        @Query("limit") limit: Int,
        @Query("fields") fields: String,
        @Query("seriesId") seriesId: String? = null,
    ): ItemsResult

    @GET("Items/Latest")
    suspend fun latest(
        @Query("userId") userId: String,
        @Query("parentId") parentId: String,
        @Query("limit") limit: Int,
        @Query("fields") fields: String,
    ): List<BaseItem>

    @GET("Items")
    @Suppress("LongParameterList") // One per query parameter of the route.
    suspend fun items(
        @Query("userId") userId: String,
        @Query("parentId") parentId: String?,
        @Query("includeItemTypes") includeItemTypes: String?,
        @Query("recursive") recursive: Boolean,
        @Query("sortBy") sortBy: String,
        @Query("sortOrder") sortOrder: String,
        @Query("startIndex") startIndex: Int,
        @Query("limit") limit: Int,
        @Query("fields") fields: String,
        @Query("filters") filters: String? = null,
        @Query("genres") genres: String? = null,
        @Query("years") years: String? = null,
        @Query("is4K") is4K: Boolean? = null,
        /** Comma-separated person ids; matches both cast and crew. */
        @Query("personIds") personIds: String? = null,
        /** Server-side search on item names. */
        @Query("searchTerm") searchTerm: String? = null,
    ): ItemsResult

    /** People whose names match [searchTerm], using the server's search. */
    @GET("Persons")
    suspend fun persons(
        @Query("userId") userId: String,
        @Query("searchTerm") searchTerm: String,
        @Query("limit") limit: Int,
    ): ItemsResult

    @GET("Genres")
    suspend fun genres(
        @Query("userId") userId: String,
        @Query("parentId") parentId: String,
        @Query("sortBy") sortBy: String = "SortName",
    ): ItemsResult

    @GET("Items/{id}")
    suspend fun item(@Path("id") id: String, @Query("userId") userId: String): BaseItem

    @GET("Items/{id}/Similar")
    suspend fun similar(
        @Path("id") id: String,
        @Query("userId") userId: String,
        @Query("limit") limit: Int,
        @Query("fields") fields: String,
    ): ItemsResult

    @GET("Shows/{id}/Seasons")
    suspend fun seasons(
        @Path("id") seriesId: String,
        @Query("userId") userId: String,
        @Query("fields") fields: String,
    ): ItemsResult

    @GET("Shows/{id}/Episodes")
    suspend fun episodes(
        @Path("id") seriesId: String,
        @Query("userId") userId: String,
        @Query("seasonId") seasonId: String,
        @Query("fields") fields: String,
    ): ItemsResult

    @POST("UserPlayedItems/{id}")
    suspend fun markPlayed(@Path("id") id: String, @Query("userId") userId: String)

    @DELETE("UserPlayedItems/{id}")
    suspend fun markUnplayed(@Path("id") id: String, @Query("userId") userId: String)

    @POST("UserFavoriteItems/{id}")
    suspend fun addFavorite(@Path("id") id: String, @Query("userId") userId: String)

    @DELETE("UserFavoriteItems/{id}")
    suspend fun removeFavorite(@Path("id") id: String, @Query("userId") userId: String)

    @POST("Sessions/Playing")
    suspend fun reportStart(@Body body: PlaybackReport)

    @POST("Sessions/Playing/Progress")
    suspend fun reportProgress(@Body body: PlaybackReport)

    @POST("Sessions/Playing/Stopped")
    suspend fun reportStopped(@Body body: PlaybackReport)
}
