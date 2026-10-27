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

    /** The episode groups of the shows of those ids, as `tv/{id}/episode_groups` lists them; none for the others. */
    val episodeGroups: MutableMap<Int, List<TmdbEpisodeGroupRef>> = Collections.synchronizedMap(linkedMapOf())

    /** Each episode group whole, by its id ([group]). */
    val groups: MutableMap<String, TmdbEpisodeGroup> = Collections.synchronizedMap(linkedMapOf())

    /**
     * TMDB's ranked answer to a search, by its query in lower case: the films or shows of these ids, of the year asked
     * for. A query not here finds the films or shows of that title exactly.
     */
    val searches: MutableMap<String, List<Int>> = Collections.synchronizedMap(linkedMapOf())

    /** English titles of the films and shows of those ids, which a search in English ("en-US") finds and answers. */
    val englishTitles: MutableMap<Int, String> = Collections.synchronizedMap(linkedMapOf())

    /** Searches asked, in order: "query (year)", or "query" without a year; and the language each was asked in. */
    val searched: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val searchLanguages: MutableList<String?> = Collections.synchronizedList(mutableListOf())

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
        votes: Int = 0,
    ) = TmdbMovie(
        id = id, title = title, imdbId = imdbId, originalTitle = originalTitle, releaseDate = year?.let { "$it-06-01" },
        overview = "About $title", posterPath = "/poster-$id.jpg", belongsToCollection = collection, voteCount = votes,
    ).also { movies[id] = it }

    fun show(id: Int, name: String, year: Int? = null, tvdbId: Int? = null, imdbId: String? = null, votes: Int = 0) = TmdbTv(
        id = id,
        name = name,
        firstAirDate = year?.let { "$it-01-01" },
        overview = "About $name",
        posterPath = "/show-$id.jpg",
        externalIds = TmdbExternalIds(imdbId = imdbId, tvdbId = tvdbId),
        voteCount = votes,
    ).also { shows[id] = it }

    /** Changes what TMDB answers for the film [id], added before. */
    fun editMovie(id: Int, edit: (TmdbMovie) -> TmdbMovie) {
        movies[id] = edit(movies.getValue(id))
    }

    /** Changes what TMDB answers for the show [id], added before. */
    fun editShow(id: Int, edit: (TmdbTv) -> TmdbTv) {
        shows[id] = edit(shows.getValue(id))
    }

    /** Season [number] of show [showId], its [episodes] named "Episode 2.5"; listed with its show, as `tv/{id}` lists them. */
    fun season(showId: Int, number: Int, episodes: Int, airDate: String? = null) = TmdbSeason(
        seasonNumber = number,
        name = "Season $number",
        posterPath = "/season-$showId-$number.jpg",
        airDate = airDate,
        episodes = (1..episodes).map { TmdbEpisode(episodeNumber = it, name = "Episode $number.$it", overview = "Plot $number.$it") },
    ).also { season ->
        seasons[showId to number] = season
        shows[showId]?.let { show ->
            val listed = show.seasons.filter { s -> s.seasonNumber != number } + TmdbSeasonSummary(number, episodes, airDate)
            shows[showId] = show.copy(seasons = listed.sortedBy { s -> s.seasonNumber })
        }
    }

    /**
     * Episode group [id] of show [showId], of TMDB's [type] (2 absolute, 3 DVD…), its [seasons] each a list of TMDB's
     * (season, episode), numbered from [first], its episodes saying they are of show [of]; listed with the show's others,
     * in its place when it was already: TMDB editing it.
     */
    fun group(
        showId: Int,
        id: String,
        type: Int,
        name: String,
        seasons: List<List<Pair<Int, Int>>>,
        first: Int = 1,
        of: Int = showId,
    ): TmdbEpisodeGroup {
        val group = TmdbEpisodeGroup(
            id,
            type,
            name,
            seasons.mapIndexed { i, episodes ->
                TmdbGroupSeason(
                    "Group ${first + i}",
                    first + i,
                    episodes.mapIndexed { j, (season, episode) -> TmdbGroupEpisode(season, episode, j, of) },
                )
            },
        )
        groups[id] = group
        val ref = TmdbEpisodeGroupRef(id, type, name, seasons.sumOf { it.size }, seasons.size)
        val listed = episodeGroups[showId].orEmpty()
        episodeGroups[showId] = if (listed.any { it.id == id }) listed.map { if (it.id == id) ref else it } else listed + ref
        return group
    }

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
        path.startsWith("search/") -> search(
            path,
            url.queryParameter("query"),
            url.queryParameter("year") ?: url.queryParameter("first_air_date_year"),
            url.queryParameter("language"),
        )
        path.startsWith("find/") -> find(path.substringAfter('/'), url.queryParameter("external_source"))
        path.endsWith("/images") -> encode(TmdbImageSet.serializer(), TmdbImageSet())
        path.endsWith("/alternative_titles") -> alternatives(path.split('/'))
        path.startsWith("trending/") -> page(emptyList())
        path.endsWith("/episode_groups") -> path.split('/')[1].toIntOrNull()?.takeIf { it in shows }
            ?.let { encode(TmdbEpisodeGroups.serializer(), TmdbEpisodeGroups(episodeGroups[it].orEmpty())) }
        path.startsWith("tv/episode_group/") -> groups[path.substringAfterLast('/')]?.let { encode(TmdbEpisodeGroup.serializer(), it) }
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

    /** The films ([path] "search/movie") or shows of [query] and [year], ranked ([searches]), in English for "en-US". */
    private fun search(path: String, query: String?, year: String?, language: String?): String {
        searched += "$query${year?.let { " ($it)" }.orEmpty()}"
        searchLanguages += language
        val english = language == ENGLISH
        val ranked = searches[query?.lowercase()]
        fun named(id: Int, title: String?) = (if (english) englishTitles[id] else null) ?: title
        fun asked(id: Int, title: String?) = ranked?.contains(id) ?: named(id, title).equals(query, ignoreCase = true)
        fun order(id: Int) = ranked?.indexOf(id) ?: 0
        return page(
            if (path == "search/movie") {
                movies.values.filter { asked(it.id, it.title) && matches(year, it.releaseDate) }.sortedBy { order(it.id) }.map {
                    TmdbSearchResult(
                        it.id,
                        title = named(it.id, it.title),
                        releaseDate = it.releaseDate,
                        posterPath = it.posterPath,
                        originalTitle = it.originalTitle,
                        overview = it.overview,
                        voteCount = it.voteCount ?: 0,
                    )
                }
            } else {
                shows.values.filter { asked(it.id, it.name) && matches(year, it.firstAirDate) }.sortedBy { order(it.id) }.map {
                    TmdbSearchResult(
                        it.id,
                        name = named(it.id, it.name),
                        firstAirDate = it.firstAirDate,
                        posterPath = it.posterPath,
                        originalName = it.originalName,
                        overview = it.overview,
                        voteCount = it.voteCount ?: 0,
                    )
                }
            },
        )
    }

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
        const val ENGLISH = "en-US"
        val JSON = "application/json".toMediaType()

        @OptIn(ExperimentalSerializationApi::class)
        val json = Json {
            explicitNulls = false
            namingStrategy = JsonNamingStrategy.SnakeCase
        }
    }
}
