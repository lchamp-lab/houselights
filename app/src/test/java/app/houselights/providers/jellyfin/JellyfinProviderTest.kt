package app.houselights.providers.jellyfin

import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.SectionType
import app.houselights.core.SortOption
import app.houselights.core.SourceConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** The Jellyfin provider against a test server: what it writes, what it reports, how it fails. */
@RunWith(RobolectricTestRunner::class)
class JellyfinProviderTest {
    private val config = SourceConfig.Jellyfin("http://nas:8096", "Maison", "u1", "lea", "token")
    private val film = MediaItem("f1", MediaKind.MOVIE, "Heat")

    private fun provider(server: FakeServer) = JellyfinProvider(config, "device", server.http, retryDelaysMs = listOf(0, 0, 0))

    /** The HTTP failure [block] ends on; null when it succeeds. */
    private suspend fun httpFailure(block: suspend () -> Unit): HttpException? = try {
        block()
        null
    } catch (e: HttpException) {
        e
    }

    private val views = """{"Items":[{"Id":"lib1","Name":"Films","CollectionType":"movies"}],"TotalRecordCount":1}"""
    private val oneFilm = """{"Items":[{"Id":"f1","Name":"Heat","Type":"Movie"}],"TotalRecordCount":1}"""

    @Test
    fun aServerErrorOnWatchedIsAFailure() = runTest {
        val server = FakeServer { Answer(500) }
        assertEquals(500, httpFailure { provider(server).setPlayed(film, true) }?.code())
        assertEquals(listOf("/UserPlayedItems/f1"), server.paths)
    }

    @Test
    fun anEmptyAnswerIsASuccess() = runTest {
        val server = FakeServer { Answer(204) }
        provider(server).setFavorite(film, true)
        provider(server).reporter("f1|s1")!!.started(0)
        assertEquals(listOf("/UserFavoriteItems/f1", "/Sessions/Playing"), server.paths)
    }

    /** A 500 on Stopped, "database is locked" during a scan, would leave the film unwatched on the server. */
    @Test
    fun stoppedIsTriedAgainWhileTheServerFails() = runTest {
        val tries = AtomicInteger()
        val server = FakeServer {
            when (tries.incrementAndGet()) {
                1 -> Answer(500)
                2 -> throw ConnectException("Connection refused")
                else -> Answer(204)
            }
        }
        provider(server).reporter("f1|s1")!!.stopped(7_000_000, null)
        assertEquals(List(3) { "/Sessions/Playing/Stopped" }, server.paths)
    }

    @Test
    fun stoppedIsTriedThreeTimesMoreThenFails() = runTest {
        val server = FakeServer { Answer(503) }
        assertEquals(503, httpFailure { provider(server).reporter("f1|s1")!!.stopped(7_000_000, null) }?.code())
        assertEquals(4, server.paths.size)
    }

    /** The request may have reached the server before the answer timed out: a second Stopped would count the film twice. */
    @Test
    fun aReadTimeoutIsNotTriedAgain() = runTest {
        val server = FakeServer { throw SocketTimeoutException("timeout") }
        try {
            provider(server).reporter("f1|s1")!!.stopped(7_000_000, null)
        } catch (_: IOException) {
            // Expected: reported as a failure.
        }
        assertEquals(1, server.paths.size)
    }

    /** No connection within the timeout (a server asleep, a Wi-Fi joining again): Stopped never reached it, and is sent again. */
    @Test
    fun aConnectionThatTimedOutIsTriedAgain() = runTest {
        val tries = AtomicInteger()
        val server = FakeServer {
            if (tries.incrementAndGet() == 1) {
                // Android's message; OkHttp passes it through unchanged.
                throw SocketTimeoutException("failed to connect to /192.168.1.10 (port 8096) from /192.168.1.20 (port 41234) after 10000ms")
            }
            Answer(204)
        }
        provider(server).reporter("f1|s1")!!.stopped(7_000_000, null)
        assertEquals(List(2) { "/Sessions/Playing/Stopped" }, server.paths)
    }

    @Test
    fun anAnswerOtherThanTheNetworkFailingIsNotTriedAgain() = runTest {
        val server = FakeServer { throw IOException("Connection reset") }
        runCatching { provider(server).reporter("f1|s1")!!.stopped(7_000_000, null) }
        assertEquals(1, server.paths.size)
    }

    /** Stopped(1) failing as episode 2 starts within seconds is sent once more before Playing(2), never after. */
    @Test
    fun aNewPlaybackSendsTheLastStoppedBeforeItsPlaying() = runTest {
        val firstStopFailed = CompletableDeferred<Unit>()
        val stops = AtomicInteger()
        val server = FakeServer { request ->
            if (request.url.encodedPath.endsWith("/Stopped") && stops.incrementAndGet() == 1) {
                firstStopFailed.complete(Unit)
                throw ConnectException("Connection refused")
            }
            Answer(204)
        }
        // The next try is a minute away: the next episode starts before it.
        val jellyfin = JellyfinProvider(config, "device", server.http, retryDelaysMs = listOf(60_000, 60_000, 60_000))
        val first = launch { jellyfin.reporter("e1|s1")!!.stopped(7_000_000, null) }
        firstStopFailed.await()
        jellyfin.reporter("e2|s2")!!.started(0)
        first.join()
        assertEquals(listOf("/Sessions/Playing/Stopped", "/Sessions/Playing/Stopped", "/Sessions/Playing"), server.paths)
        assertEquals(listOf("e1", "e1", "e2"), server.requests.map { it.itemId() })
    }

    @Test
    fun aLastStoppedThatFailsAgainIsGivenUp() = runTest {
        val firstStopFailed = CompletableDeferred<Unit>()
        val server = FakeServer { request ->
            if (request.url.encodedPath.endsWith("/Stopped")) {
                firstStopFailed.complete(Unit)
                Answer(503)
            } else {
                Answer(204)
            }
        }
        val jellyfin = JellyfinProvider(config, "device", server.http, retryDelaysMs = listOf(60_000, 60_000, 60_000))
        val first = launch { jellyfin.reporter("e1|s1")!!.stopped(7_000_000, null) }
        firstStopFailed.await()
        jellyfin.reporter("e2|s2")!!.started(0)
        first.join()
        advanceUntilIdle()
        assertEquals(listOf("/Sessions/Playing/Stopped", "/Sessions/Playing/Stopped", "/Sessions/Playing"), server.paths)
    }

    /** The item a report is about, from its JSON body. */
    private fun Request.itemId(): String? {
        val text = Buffer().also { body?.writeTo(it) }.readUtf8()
        return Regex(""""ItemId"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
    }

    @Test
    fun aRefusedReportIsNotTriedAgain() = runTest {
        val server = FakeServer { Answer(401) }
        assertEquals(401, httpFailure { provider(server).reporter("f1|s1")!!.stopped(7_000_000, null) }?.code())
        assertEquals(1, server.paths.size)
    }

    @Test
    fun homeLeavesOutTheRowsThatFailed() = runTest {
        val server = FakeServer { request ->
            when (request.url.encodedPath) {
                "/UserViews" -> Answer(200, views)
                "/UserItems/Resume" -> Answer(200, oneFilm)
                else -> Answer(500)
            }
        }
        val home = provider(server).home()
        assertEquals(listOf(SectionType.CONTINUE), home.map { it.type })
    }

    /** Every row failing does not mean an empty server: home fails, with the reason. */
    @Test
    fun homeFailsWhenEveryRowFails() = runTest {
        val server = FakeServer { request -> if (request.url.encodedPath == "/UserViews") Answer(200, views) else Answer(500) }
        assertEquals(500, httpFailure { provider(server).home() }?.code())
        assertTrue(server.paths.containsAll(listOf("/UserItems/Resume", "/Shows/NextUp", "/Items/Latest")))
    }

    /** A Blu-ray's folder may open on a menu or an intro, a file never does: the player is followed accordingly. */
    @Test
    fun aDiscIsPlayedAsADisc() = runTest {
        fun item(videoType: String, container: String) =
            """{"Id":"f1","Name":"Heat","Type":"Movie","MediaSources":[{"Id":"s1","Container":"$container","VideoType":"$videoType"}]}"""
        val options = PlaybackOptions(emptyList(), forceHttp = true)
        val bluRay = FakeServer { Answer(200, item("BluRay", "ts")) }
        assertTrue((provider(bluRay).playback(film, resume = false, options) as PlaybackPlan.Launch).disc)
        val file = FakeServer { Answer(200, item("VideoFile", "mkv")) }
        assertFalse((provider(file).playback(film, resume = false, options) as PlaybackPlan.Launch).disc)
    }

    /** Home loads the libraries, then its rows, which need them too: the server is asked for them once. */
    @Test
    fun homeAsksForTheLibrariesOnce() = runTest {
        val server = FakeServer { request ->
            when (request.url.encodedPath) {
                "/UserViews" -> Answer(200, views)
                "/Items/Latest" -> Answer(200, "[]")
                else -> Answer(200, oneFilm)
            }
        }
        val provider = provider(server)
        assertEquals(listOf("lib1"), provider.libraries().map { it.id })
        provider.home()
        assertEquals(1, server.paths.count { it == "/UserViews" })
        assertTrue("The latest of the library were not asked for", "/Items/Latest" in server.paths)
        // A separate home load asks for them again.
        provider.home()
        assertEquals(2, server.paths.count { it == "/UserViews" })
    }

    /** A wall shows a title's year and facts: its overview and genres are not asked for. */
    @Test
    fun aWallAsksOnlyForWhatItShows() = runTest {
        val server = FakeServer { Answer(200, oneFilm) }
        val films = Library("lib1", LibraryKind.MOVIES, "Films")
        val page = provider(server).libraryPage(films, SortOption.TITLE, LibraryFilter(), start = 0, limit = 120)
        assertEquals(listOf("Heat"), page.items.map { it.title })
        val fields = server.requests.last().url.queryParameter("fields").orEmpty().split(',')
        assertFalse("Overview" in fields)
        assertFalse("Genres" in fields)
        assertTrue("ProductionYear" in fields)
    }

    @Test
    fun titlesAreMadeAwayFromTheCaller() = runTest {
        val mapping = object : CoroutineDispatcher() {
            val runs = AtomicInteger()

            override fun dispatch(context: CoroutineContext, block: Runnable) {
                runs.incrementAndGet()
                Dispatchers.Default.dispatch(context, block)
            }
        }
        val server = FakeServer { request ->
            when (request.url.encodedPath) {
                "/UserViews" -> Answer(200, views)
                "/Items/Latest" -> Answer(200, "[]")
                else -> Answer(200, oneFilm)
            }
        }
        val provider = JellyfinProvider(config, "device", server.http, retryDelaysMs = listOf(0, 0, 0), mapping = mapping)
        val films = Library("lib1", LibraryKind.MOVIES, "Films")
        val page = provider.libraryPage(films, SortOption.TITLE, LibraryFilter(), start = 0, limit = 120)
        assertEquals(listOf("Heat"), page.items.map { it.title })
        assertEquals("the wall's page", 1, mapping.runs.get())
        provider.home()
        assertEquals("home's rows", 2, mapping.runs.get())
    }

    @Test
    fun anEmptyServerIsNotAFailure() = runTest {
        val server = FakeServer { request ->
            when (request.url.encodedPath) {
                "/UserViews" -> Answer(200, views)
                "/Items/Latest" -> Answer(200, "[]")
                else -> Answer(200, "{}")
            }
        }
        assertEquals(emptyList<Any>(), provider(server).home())
    }
}
