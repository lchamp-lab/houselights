package app.houselights.providers.jellyfin

import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.SectionType
import app.houselights.core.SourceConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
            // Said as a failure.
        }
        assertEquals(1, server.paths.size)
    }

    @Test
    fun anAnswerOtherThanTheNetworkFailingIsNotTriedAgain() = runTest {
        val server = FakeServer { throw IOException("Connection reset") }
        runCatching { provider(server).reporter("f1|s1")!!.stopped(7_000_000, null) }
        assertEquals(1, server.paths.size)
    }

    /** A Stopped late by some seconds must not land after the next film's Playing, nor mark again what was just unmarked. */
    @Test
    fun aNewPlaybackEndsTheTriesOfTheLastOne() = runTest {
        lateinit var jellyfin: JellyfinProvider
        val stops = AtomicInteger()
        val server = FakeServer { request ->
            if (request.url.encodedPath.endsWith("/Stopped") && stops.incrementAndGet() == 1) {
                // While the first Stopped fails, the next film starts.
                runBlocking { jellyfin.reporter("f2|s2")!!.started(0) }
                throw ConnectException("Connection refused")
            }
            Answer(204)
        }
        jellyfin = provider(server)
        runCatching { jellyfin.reporter("f1|s1")!!.stopped(7_000_000, null) }
        assertEquals(listOf("/Sessions/Playing", "/Sessions/Playing/Stopped"), server.paths.sorted())
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

    /** Every row failing is not a server with nothing on it: home fails, and says why. */
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
