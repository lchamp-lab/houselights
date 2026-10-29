package app.houselights.providers.local

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

@Suppress("TooManyFunctions") // One per TMDB endpoint the app reads.
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

    /** A film's ([kind] "movie"), a show's ("tv") or a saga's ("collection") posters, backdrops and logos. */
    @GET("{kind}/{id}/images")
    suspend fun images(
        @Path("kind") kind: String,
        @Path("id") id: Int,
        @Query("include_image_language") languages: String,
    ): TmdbImageSet

    /** A film's ([kind] "movie") or a show's ("tv") other titles. */
    @GET("{kind}/{id}/alternative_titles")
    suspend fun alternativeTitles(@Path("kind") kind: String, @Path("id") id: Int): TmdbAlternativeTitles

    @GET("collection/{id}")
    suspend fun collection(@Path("id") id: Int, @Query("language") language: String): TmdbCollection

    @GET("trending/movie/week")
    suspend fun trendingMovies(@Query("language") language: String): TmdbPage<TmdbSearchResult>

    @GET("tv/{id}/season/{season}")
    suspend fun season(@Path("id") id: Int, @Path("season") season: Int, @Query("language") language: String): TmdbSeason

    /** A show's episode groups; their names are TMDB's, in no language of their own. */
    @GET("tv/{id}/episode_groups")
    suspend fun episodeGroups(@Path("id") id: Int): TmdbEpisodeGroups

    @GET("tv/episode_group/{id}")
    suspend fun episodeGroup(@Path("id") id: String): TmdbEpisodeGroup
}
