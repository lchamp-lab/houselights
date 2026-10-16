package app.houselights.providers.local

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {
    @GET("search/movie")
    suspend fun searchMovie(
        @Query("query") query: String,
        @Query("year") year: Int?,
        @Query("language") language: String,
    ): TmdbPage<TmdbSearchResult>

    @GET("search/tv")
    suspend fun searchTv(
        @Query("query") query: String,
        @Query("first_air_date_year") year: Int?,
        @Query("language") language: String,
    ): TmdbPage<TmdbSearchResult>

    @GET("movie/{id}")
    suspend fun movie(
        @Path("id") id: Int,
        @Query("language") language: String,
        @Query("include_image_language") imageLanguages: String,
        @Query("append_to_response") append: String = "credits,images,release_dates",
    ): TmdbMovie

    @GET("tv/{id}")
    suspend fun tv(
        @Path("id") id: Int,
        @Query("language") language: String,
        @Query("include_image_language") imageLanguages: String,
        @Query("append_to_response") append: String = "credits,images,content_ratings,external_ids",
    ): TmdbTv

    @GET("find/{id}")
    suspend fun find(
        @Path("id") externalId: String,
        @Query("external_source") source: String = "imdb_id",
    ): TmdbFindResult

    @GET("movie/{id}/images")
    suspend fun movieImages(@Path("id") id: Int, @Query("include_image_language") languages: String): TmdbImageSet

    @GET("tv/{id}/images")
    suspend fun tvImages(@Path("id") id: Int, @Query("include_image_language") languages: String): TmdbImageSet

    @GET("collection/{id}")
    suspend fun collection(@Path("id") id: Int, @Query("language") language: String): TmdbCollection

    @GET("trending/movie/week")
    suspend fun trendingMovies(@Query("language") language: String): TmdbPage<TmdbSearchResult>

    @GET("tv/{id}/season/{season}")
    suspend fun season(@Path("id") id: Int, @Path("season") season: Int, @Query("language") language: String): TmdbSeason
}
