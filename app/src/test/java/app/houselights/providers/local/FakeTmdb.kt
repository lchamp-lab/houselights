package app.houselights.providers.local

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.HttpUrl
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

/** TMDB in memory behind [client]: counts requests; can fail, answer a status, break a body or hold a request. */
class FakeTmdb : Interceptor {
    val movies: MutableMap<Int, TmdbMovie> = Collections.synchronizedMap(linkedMapOf())
    val shows: MutableMap<Int, TmdbTv> = Collections.synchronizedMap(linkedMapOf())
    val seasons: MutableMap<Pair<Int, Int>, TmdbSeason> = Collections.synchronizedMap(linkedMapOf())
    val collections: MutableMap<Int, TmdbCollection> = Collections.synchronizedMap(linkedMapOf())

    /** Other titles of the films and of the shows of those ids ([TmdbClient.alternativeTitles]); none for the others. */
    val movieAlternatives: MutableMap<Int, List<String>> = Collections.synchronizedMap(linkedMapOf())
    val showAlternatives: MutableMap<Int, List<String>> = Collections.synchronizedMap(linkedMapOf())

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

    /** When set, replaces the body of every entry found: TMDB answering in an unexpected shape. */
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

    /** Runs at each request, on OkHttp's thread, before TMDB answers. */
    @Volatile
    var onRequest: ((path: String) -> Unit)? = null

    fun client(pauseWhenUnreachable: Boolean = false) = TmdbClient("key", pauseWhenUnreachable, this)

    fun movie(
        id: Int,
        title: String,
        year: Int? = null,
        imdbId: String? = null,
        collection: TmdbCollectionRef? = null,
        originalTitle: String? = null,
    ) = TmdbMovie(
        id = id, title = title, imdbId = imdbId, originalTitle = originalTitle, releaseDate = year?.let { "$it-06-01" },
        overview = "About $title", posterPath = "/poster-$id.jpg", belongsToCollection = collection,
    ).also { movies[id] = it }

    fun show(id: Int, name: String, year: Int? = null, tvdbId: Int? = null, imdbId: String? = null) = TmdbTv(
        id = id, name = name, firstAirDate = year?.let { "$it-01-01" }, overview = "About $name", posterPath = "/show-$id.jpg",
        externalIds = TmdbExternalIds(imdbId = imdbId, tvdbId = tvdbId),
    ).also { shows[id] = it }

    /** Changes what TMDB answers for the film [id], added before. */
    fun editMovie(id: Int, edit: (TmdbMovie) -> TmdbMovie) {
        movies[id] = edit(movies.getValue(id))
    }

    /** Changes what TMDB answers for the show [id], added before. */
    fun editShow(id: Int, edit: (TmdbTv) -> TmdbTv) {
        shows[id] = edit(shows.getValue(id))
    }

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
        onRequest?.invoke(path)
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
            val body = answer(path, url)?.takeIf { path != gonePath }
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
    private fun answer(path: String, url: HttpUrl): String? = when {
        path == "authentication" -> "{\"success\":true}"
        path.startsWith("search/") ->
            search(path, url.queryParameter("query"), url.queryParameter("year") ?: url.queryParameter("first_air_date_year"))
        path.startsWith("find/") -> find(path.substringAfter('/'), url.queryParameter("external_source"))
        path.endsWith("/images") -> encode(TmdbImageSet.serializer(), TmdbImageSet())
        path.endsWith("/alternative_titles") -> alternatives(path.split('/'))
        path.startsWith("trending/") -> page(emptyList())
        else -> entry(path.split('/'))
    }

    /** The films and shows an IMDb id names, or the shows a TheTVDB id does ([source] "tvdb_id"). */
    private fun find(id: String, source: String?): String = encode(
        TmdbFindResult.serializer(),
        if (source == "tvdb_id") {
            TmdbFindResult(
                tvResults = shows.values.filter { it.externalIds.tvdbId?.toString() == id }.map { TmdbSearchResult(it.id, name = it.name) },
            )
        } else {
            TmdbFindResult(
                movieResults = movies.values.filter { it.imdbId == id }.map { TmdbSearchResult(it.id, title = it.title) },
                tvResults = shows.values.filter { it.externalIds.imdbId == id }.map { TmdbSearchResult(it.id, name = it.name) },
            )
        },
    )

    /** A film's alternative titles (under "titles") or a show's (under "results"); null for an entry TMDB does not have. */
    private fun alternatives(parts: List<String>): String? {
        val id = parts[1].toIntOrNull()
        val show = parts[0] == "tv"
        if (id !in (if (show) shows else movies)) return null
        val titles = (if (show) showAlternatives[id] else movieAlternatives[id]).orEmpty().map { TmdbAlternativeTitle(it, "US") }
        val all = if (show) TmdbAlternativeTitles(results = titles) else TmdbAlternativeTitles(titles = titles)
        return encode(TmdbAlternativeTitles.serializer(), all)
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
