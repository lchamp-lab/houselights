package app.houselights.providers.local

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch

/**
 * TMDB in memory, in place of the network of a [TmdbClient] ([client]): films, shows, seasons and
 * collections by id, found by title or IMDb id. Every request is counted, and TMDB can be made to
 * fail, to answer a status or a body of its own, to break off a body, or to hold a request until it
 * is cancelled.
 */
class FakeTmdb : Interceptor {
    val movies = Collections.synchronizedMap(linkedMapOf<Int, TmdbMovie>())
    val shows = Collections.synchronizedMap(linkedMapOf<Int, TmdbTv>())
    val seasons = Collections.synchronizedMap(linkedMapOf<Pair<Int, Int>, TmdbSeason>())
    val collections = Collections.synchronizedMap(linkedMapOf<Int, TmdbCollection>())

    /** Paths asked, in order ("movie/438631"), those that failed included. */
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Thrown instead of an answer, while set: TMDB out of reach. */
    @Volatile
    var failure: IOException? = null

    /** Thrown first, one per request, before TMDB answers again: a moment out of reach. */
    val nextFailures: MutableList<IOException> = Collections.synchronizedList(mutableListOf())

    /** Answered instead of the entry, while set (401, 503…), with its headers. */
    @Volatile
    var status: Int? = null

    @Volatile
    var headers: Map<String, String> = emptyMap()

    /** Statuses answered first, one per request, before TMDB answers again. */
    val nextStatuses: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    /** When set, requests whose path starts with it fail as if TMDB were out of reach. */
    @Volatile
    var failingPath: String? = null

    /** When set, answers start, then reading their body throws it: a body stalled past the read timeout, a connection reset. */
    @Volatile
    var bodyFailure: IOException? = null

    /** When set, the body of every entry found, in its place: TMDB's answers changed shape. */
    @Volatile
    var rawBody: String? = null

    /** When set, the path of an entry TMDB no longer has, though its search still finds it. */
    @Volatile
    var gonePath: String? = null

    /** When set, a request waits until its call is cancelled, after counting [held] down. */
    @Volatile
    var hold: Boolean = false

    @Volatile
    var held = CountDownLatch(1)

    fun client(pauseWhenUnreachable: Boolean = false) = TmdbClient("key", pauseWhenUnreachable, this)

    fun movie(id: Int, title: String, year: Int? = null, imdbId: String? = null, collection: TmdbCollectionRef? = null) =
        TmdbMovie(
            id = id, title = title, imdbId = imdbId, releaseDate = year?.let { "$it-06-01" }, overview = "About $title",
            posterPath = "/poster-$id.jpg", belongsToCollection = collection,
        ).also { movies[id] = it }

    fun show(id: Int, name: String, year: Int? = null) =
        TmdbTv(id = id, name = name, firstAirDate = year?.let { "$it-01-01" }, overview = "About $name", posterPath = "/show-$id.jpg")
            .also { shows[id] = it }

    fun season(showId: Int, number: Int, episodes: Int) = TmdbSeason(
        seasonNumber = number,
        name = "Season $number",
        posterPath = "/season-$showId-$number.jpg",
        episodes = (1..episodes).map { TmdbEpisode(episodeNumber = it, name = "Episode $number.$it", overview = "Plot $number.$it") },
    ).also { seasons[showId to number] = it }

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        val path = url.encodedPath.removePrefix("/3/")
        requests += path
        if (hold) {
            held.countDown()
            while (!chain.call().isCanceled()) Thread.sleep(WAIT_MS)
            throw IOException("Canceled")
        }
        failure?.let { throw it }
        synchronized(nextFailures) { nextFailures.removeFirstOrNull() }?.let { throw it }
        failingPath?.let { if (path.startsWith(it)) throw SocketTimeoutException("timeout") }
        val code = synchronized(nextStatuses) { nextStatuses.removeFirstOrNull() } ?: status
        val response = if (code != null) {
            respond(chain, code, "{}")
        } else {
            val body = answer(path, url.queryParameter("query"), url.queryParameter("year") ?: url.queryParameter("first_air_date_year"))
                ?.takeIf { path != gonePath }
            if (body == null) respond(chain, NOT_FOUND, "{}") else respond(chain, OK, rawBody ?: body)
        }
        return bodyFailure?.let { response.newBuilder().body(FailingBody(it)).build() } ?: response
    }

    /** A body whose bytes never come: reading it throws [failure]. */
    private class FailingBody(private val failure: IOException) : ResponseBody() {
        override fun contentType() = JSON

        override fun contentLength() = -1L

        override fun source(): BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw failure

            override fun timeout() = Timeout.NONE

            override fun close() = Unit
        }.buffer()
    }

    /** The body TMDB answers [path] with; null for an entry it does not have. */
    private fun answer(path: String, query: String?, year: String?): String? = when {
        path == "authentication" -> "{\"success\":true}"
        path.startsWith("search/") -> search(path, query, year)
        path.startsWith("find/") -> encode(
            TmdbFindResult.serializer(),
            TmdbFindResult(
                movieResults = movies.values.filter { it.imdbId == path.substringAfter('/') }
                    .map { TmdbSearchResult(it.id, title = it.title) },
            ),
        )
        path.endsWith("/images") -> encode(TmdbImageSet.serializer(), TmdbImageSet())
        path.startsWith("trending/") -> page(emptyList())
        else -> entry(path.split('/'))
    }

    private fun search(path: String, query: String?, year: String?): String = page(
        if (path == "search/movie") {
            movies.values.filter { it.title.equals(query, true) && matches(year, it.releaseDate) }
                .map { TmdbSearchResult(it.id, title = it.title, releaseDate = it.releaseDate) }
        } else {
            shows.values.filter { it.name.equals(query, true) && matches(year, it.firstAirDate) }
                .map { TmdbSearchResult(it.id, name = it.name, firstAirDate = it.firstAirDate) }
        },
    )

    /** A film, a show, a season or a collection, by id. */
    private fun entry(parts: List<String>): String? {
        fun id(at: Int) = parts.getOrNull(at)?.toIntOrNull()
        return when (parts[0]) {
            "movie" -> movies[id(1)]?.let { encode(TmdbMovie.serializer(), it) }
            "tv" -> if (parts.size == 4) {
                seasons[id(1) to id(3)]?.let { encode(TmdbSeason.serializer(), it) }
            } else {
                shows[id(1)]?.let { encode(TmdbTv.serializer(), it) }
            }
            "collection" -> collections[id(1)]?.let { encode(TmdbCollection.serializer(), it) }
            else -> null
        }
    }

    private fun matches(year: String?, date: String?) = year == null || date?.startsWith(year) == true

    private fun page(results: List<TmdbSearchResult>) =
        encode(TmdbPage.serializer(TmdbSearchResult.serializer()), TmdbPage(results))

    private fun <T> encode(serializer: KSerializer<T>, value: T): String = json.encodeToString(serializer, value)

    private fun respond(chain: Interceptor.Chain, code: Int, body: String): Response = Response.Builder()
        .request(chain.request())
        .protocol(okhttp3.Protocol.HTTP_1_1)
        .code(code)
        .message("TMDB $code")
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .body(body.toResponseBody(JSON))
        .build()

    private companion object {
        const val OK = 200
        const val NOT_FOUND = 404
        const val WAIT_MS = 5L
        val JSON = "application/json".toMediaType()

        @OptIn(ExperimentalSerializationApi::class)
        val json = Json {
            explicitNulls = false
            namingStrategy = JsonNamingStrategy.SnakeCase
        }
    }
}
