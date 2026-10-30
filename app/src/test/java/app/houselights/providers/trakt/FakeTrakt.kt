package app.houselights.providers.trakt

import app.houselights.testing.Answer
import app.houselights.testing.FakeServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import okio.Buffer
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Trakt as the sync sees it: the account's history and pauses as JSON, answers staged by route, [offline] at will. */
class FakeTrakt {
    @Volatile
    var movies = "[]"

    @Volatile
    var shows = "[]"

    @Volatile
    var playback = "[]"

    @Volatile
    var activities = activities("2026-10-01T00:00:00.000Z")

    /** Every request fails as without a network. */
    @Volatile
    var offline = false

    /** Every request fails as a connection Android did not open in time: none reaches Trakt. */
    @Volatile
    var connectTimingOut = false

    private val staged = ConcurrentHashMap<String, ArrayDeque<Answer>>()

    /** The requests Trakt received, answered or not; not those the network lost. */
    private val answered: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    val server = FakeServer(::answer)

    private val before = ConcurrentHashMap<String, () -> Unit>()

    private val losing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Answers [route] ("POST /scrobble/stop") with [answers] first, in turn. */
    fun stage(route: String, vararg answers: Answer) {
        staged.getOrPut(route) { ArrayDeque() }.addAll(answers)
    }

    /** Runs [action] once, as [route] is first asked, before it is answered. */
    fun during(route: String, action: () -> Unit) {
        before[route] = action
    }

    /** Takes the next request on [route] but loses its answer, as a connection dropped while Trakt answers. */
    fun lose(route: String) {
        losing += route
    }

    /** The routes answered, in order. */
    val routes: List<String> get() = synchronized(answered) { answered.map(::route) }

    fun count(route: String) = routes.count { it == route }

    /** The bodies Trakt received on [route], in order. */
    fun sent(route: String): List<JsonObject> = synchronized(answered) {
        answered.filter { route(it) == route }.map { r ->
            Json.parseToJsonElement(Buffer().also { r.body!!.writeTo(it) }.readUtf8()).jsonObject
        }
    }

    private fun route(request: Request) = "${request.method} ${request.url.encodedPath}"

    private fun answer(request: Request): Answer {
        if (offline) throw UnknownHostException("api.trakt.tv")
        if (connectTimingOut) throw SocketTimeoutException("failed to connect to api.trakt.tv/172.66.0.1 (port 443) after 10000ms")
        answered += request
        val route = route(request)
        before.remove(route)?.invoke()
        if (losing.remove(route)) throw SocketTimeoutException("timeout")
        return staged[route]?.let { synchronized(it) { it.removeFirstOrNull() } } ?: byDefault(route)
    }

    /** What Trakt answers [route] with, but for what a test staged. */
    private fun byDefault(route: String): Answer =
        when {
            route == "GET /sync/last_activities" -> Answer(200, activities)
            route == "GET /sync/watched/movies" -> Answer(200, movies)
            route == "GET /sync/watched/shows" -> Answer(200, shows)
            route == "GET /sync/playback" -> Answer(200, playback)
            route == "POST /sync/history" -> Answer(201, """{"added":{"movies":0,"episodes":0},"not_found":{}}""")
            route == "POST /sync/history/remove" -> Answer(200, """{"deleted":{"movies":0,"episodes":0},"not_found":{}}""")
            route == "POST /scrobble/stop" -> Answer(201, """{"id":1,"action":"pause","progress":10}""")
            route.startsWith("DELETE /sync/playback/") -> Answer(204)
            route == "POST /oauth/token" -> Answer(200, RENEWED)
            route == "POST /oauth/revoke" -> Answer(200)
            else -> Answer(404, "unexpected $route")
        }

    companion object {
        /** The tokens a renewal gives. */
        const val RENEWED = """{"access_token":"a2","token_type":"bearer","expires_in":86400,"refresh_token":"r2",""" +
            """"scope":"public","created_at":1700000000}"""

        /** Trakt's last activities, its history and pauses changed at [at]. */
        fun activities(at: String) =
            """{"all":"$at","movies":{"watched_at":"$at","paused_at":"$at"},"episodes":{"watched_at":"$at","paused_at":"$at"}}"""

        /** A film watched at [at], by its TMDB id. */
        fun movie(tmdb: Int, at: String) =
            """{"plays":1,"last_watched_at":"$at","movie":{"title":"F$tmdb","ids":{"trakt":$tmdb,"tmdb":$tmdb}}}"""

        /** A show by its TMDB id with [episodes] watched at [at], as (season, number). */
        fun show(tmdb: Int, at: String, vararg episodes: Pair<Int, Int>): String {
            val seasons = episodes.groupBy { it.first }.entries.joinToString(",") { (season, list) ->
                val watched = list.joinToString(",") { """{"number":${it.second},"plays":1,"last_watched_at":"$at"}""" }
                """{"number":$season,"episodes":[$watched]}"""
            }
            return """{"plays":1,"last_watched_at":"$at","show":{"title":"S$tmdb","ids":{"tmdb":$tmdb}},"seasons":[$seasons]}"""
        }

        /** A film paused at [progress] percent at [at], playback [id]. */
        fun pausedMovie(id: Long, tmdb: Int, progress: Float, at: String) =
            """{"id":$id,"progress":$progress,"paused_at":"$at","type":"movie","movie":{"title":"F$tmdb","ids":{"tmdb":$tmdb}}}"""

        /** Episode (season, number) of show [show] paused at [progress] percent at [at], playback [id]. */
        fun pausedEpisode(id: Long, show: Int, episode: Pair<Int, Int>, progress: Float, at: String) =
            """{"id":$id,"progress":$progress,"paused_at":"$at","type":"episode",""" +
                """"episode":{"season":${episode.first},"number":${episode.second}},"show":{"title":"S$show","ids":{"tmdb":$show}}}"""
    }
}
