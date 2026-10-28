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
import app.houselights.testing.Answer
import app.houselights.testing.FakeServer
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
import org.junit.Assert.assertNull
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
    private fun provider(server: FakeServer, retryDelaysMs: List<Long> = listOf(0, 0, 0)) =
        JellyfinProvider(account, "device", server.http, retryDelaysMs)

    /** The HTTP failure [block] ends on; null when it succeeds. */
    private suspend fun httpFailure(block: suspend () -> Unit): HttpException? = try {
        block()
        null
    } catch (e: HttpException) {
        e
    }

    private val views = """{"Items":[{"Id":"lib1","Name":"Films","CollectionType":"movies"}],"TotalRecordCount":1}"""
    private val oneFilm = """{"Items":[{"Id":"f1","Name":"Heat","Type":"Movie"}],"TotalRecordCount":1}"""

    /** A server with one library and nothing new in it, answering [rest] for every other list. */
    private fun homeServer(rest: String = oneFilm) = FakeServer { request ->
        when (request.url.encodedPath) {
            "/UserViews" -> Answer(200, views)
            "/Items/Latest" -> Answer(200, "[]")
            else -> Answer(200, rest)
        }
    }

    @Test
    fun aServerErrorOnWatchedIsAFailure() = runTest {
        val server = FakeServer { Answer(500) }
        assertEquals(500, httpFailure { provider(server).setPlayed(film, true) }?.code())
        assertEquals(listOf("/UserPlayedItems/f1"), server.paths)
    }

    /** What a title's page depends on fails as such, never as a show with nothing next or an empty saga. */
    @Test
    fun aPagesNextEpisodeAndSagaFailAsSuch() = runTest {
        val server = FakeServer { Answer(500) }
        val show = MediaItem("sh1", MediaKind.SERIES, "MobLand")
        val saga = MediaItem("bs1", MediaKind.COLLECTION, "Alien")
        assertEquals(500, httpFailure { provider(server).nextUp(show) }?.code())
        assertEquals(500, httpFailure { provider(server).collectionItems(saga) }?.code())
    }

    /** Seasons are asked for their episodes on disk: one without any, which the server calls watched, holds none. */
    @Test
    fun seasonsCountTheirEpisodesOnDisk() = runTest {
        val seasons = """{"Items":[
            {"Id":"se1","Name":"Saison 1","Type":"Season","IndexNumber":1,"ChildCount":8,"RecursiveItemCount":8,
             "UserData":{"Played":false,"UnplayedItemCount":2}},
            {"Id":"se2","Name":"Saison 2","Type":"Season","IndexNumber":2,"ChildCount":0,"RecursiveItemCount":0,
             "UserData":{"Played":true,"UnplayedItemCount":0}}
        ],"TotalRecordCount":2}"""
        val server = FakeServer { Answer(200, seasons) }
        val found = provider(server).seasons(MediaItem("sh1", MediaKind.SERIES, "MobLand"))
        val asked = synchronized(server.requests) { server.requests.single { it.url.encodedPath.endsWith("/Seasons") } }
        val fields = asked.url.queryParameter("fields").orEmpty().split(",")
        assertTrue(fields.toString(), fields.containsAll(listOf("ChildCount", "RecursiveItemCount")))
        assertEquals(listOf(8, 0), found.map { it.childCount })
        assertEquals(listOf(2, 0), found.map { it.user.unplayedCount })
    }

    /** A season is marked by its own id, the server marking its episodes; unmarked, it is deleted. */
    @Test
    fun aSeasonIsMarkedByItsOwnId() = runTest {
        val server = FakeServer { Answer(200, "{}") }
        val season = MediaItem("se1", MediaKind.SEASON, "Saison 1", seriesId = "sh1", seasonNumber = 1)
        provider(server).setPlayed(season, true)
        provider(server).setPlayed(season, false)
        val requests = synchronized(server.requests) { server.requests.map { it.method to it.url.encodedPath } }
        assertEquals(listOf("POST" to "/UserPlayedItems/se1", "DELETE" to "/UserPlayedItems/se1"), requests)
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

    /** An answer timed out or cut off may follow a request the server took: a second Stopped would count the film twice. */
    @Test
    fun aFailureThatMayHaveReachedTheServerIsNotTriedAgain() = runTest {
        for (failure in listOf(SocketTimeoutException("timeout"), IOException("Connection reset"))) {
            val server = FakeServer { throw failure }
            val thrown = runCatching { provider(server).reporter("f1|s1")!!.stopped(7_000_000, null) }.exceptionOrNull()
            assertTrue("$failure, reported as $thrown", thrown is IOException)
            assertEquals("$failure", 1, server.paths.size)
        }
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
        val jellyfin = provider(server, retryDelaysMs = listOf(60_000, 60_000, 60_000))
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
        val jellyfin = provider(server, retryDelaysMs = listOf(60_000, 60_000, 60_000))
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

    /** The source's tracks in the file's order go with its launch, a subtitle file beside it marked; one choice per show. */
    @Test
    fun theSourcesTracksGoWithItsLaunch() = runTest {
        val streams = """[{"Type":"Audio","Codec":"eac3","Language":"fre","Index":1},""" +
            """{"Type":"Audio","Codec":"ac3","Language":"eng","Index":4,"IsExternal":true},""" +
            """{"Type":"Subtitle","Codec":"subrip","Language":"fre","Index":3,"IsExternal":true},""" +
            """{"Type":"Subtitle","Codec":"pgssub","Language":"fre","Index":2,"IsForced":true}]"""
        val episode = """{"Id":"e1","Name":"Pilot","Type":"Episode","SeriesId":"show1",""" +
            """"MediaSources":[{"Id":"s1","Container":"mkv","VideoType":"VideoFile","MediaStreams":$streams}]}"""
        val options = PlaybackOptions(emptyList(), forceHttp = true)
        val plan = provider(FakeServer { Answer(200, episode) }).playback(film, resume = false, options) as PlaybackPlan.Launch
        assertEquals("jellyfin:show1", plan.titleKey)
        val media = checkNotNull(plan.media)
        assertTrue(media.tracksInOrder)
        assertEquals(listOf(true to false, false to true), media.subtitles.map { it.forced to it.external })
        assertEquals(listOf(false, true), media.audio.map { it.external })
    }

    private val heatWithTracks = """{"Id":"f1","Name":"Heat","Type":"Movie","MediaSources":[{"Id":"s1","Container":"mkv",""" +
        """"VideoType":"VideoFile","MediaStreams":[{"Type":"Audio","Codec":"eac3","Language":"fre","Index":1}]}]}"""

    /** A film's page asks the server for the film once: its tracks are read from it, as its launch reads them. */
    @Test
    fun aPagesTracksAreReadFromItsItem() = runTest {
        val server = FakeServer { Answer(200, heatWithTracks) }
        val jellyfin = provider(server)
        val options = PlaybackOptions(emptyList(), forceHttp = true)
        val item = jellyfin.item("f1")
        val tracks = jellyfin.playedTracks(item, options)
        assertEquals(listOf("/Items/f1"), server.paths)
        assertEquals((jellyfin.playback(item, resume = false, options) as PlaybackPlan.Launch).tracks, tracks)
        assertEquals("Lire reads the film again", 2, server.paths.size)
        assertEquals("jellyfin:f1", tracks.titleKey)
    }

    /** Tracks asked a while after the page, or of another title (a show's next episode), ask the server. */
    @Test
    fun tracksAskedLaterOrOfAnotherTitleAskTheServer() = runTest {
        var clock = 0L
        val server = FakeServer { Answer(200, heatWithTracks) }
        val jellyfin = JellyfinProvider(account, "device", server.http, now = { clock })
        val options = PlaybackOptions(emptyList(), forceHttp = true)
        val item = jellyfin.item("f1")
        jellyfin.playedTracks(MediaItem("e1", MediaKind.EPISODE, "Pilot"), options)
        assertEquals(listOf("/Items/f1", "/Items/e1"), server.paths)
        clock = 6_000
        jellyfin.playedTracks(item, options)
        assertEquals(listOf("/Items/f1", "/Items/e1", "/Items/f1"), server.paths)
    }

    /** Home loads the libraries, then its rows, which need them too: the server is asked for them once. */
    @Test
    fun homeAsksForTheLibrariesOnce() = runTest {
        val server = homeServer()
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

    /**
     * A box set Jellyfin's TMDb provider made or filled carries TMDB's id, and shows without TMDB's « Collection »; one
     * made by hand keeps its name. A wall holding box sets, and search, ask for their ids; a page asks for none.
     */
    @Test
    fun aBoxSetTmdbKnowsShowsWithoutItsWord() = runTest {
        val sets = """{"Items":[
            {"Id":"b1","Name":"John Wick Collection","Type":"BoxSet","ProviderIds":{"Tmdb":"404609"}},
            {"Id":"b2","Name":"The Criterion Collection","Type":"BoxSet","ProviderIds":{}}
        ],"TotalRecordCount":2}"""
        val server = FakeServer { request ->
            when (request.url.encodedPath) {
                "/Items/b1" -> Answer(200, """{"Id":"b1","Name":"John Wick Collection","Type":"BoxSet","ProviderIds":{"Tmdb":"404609"}}""")
                else -> Answer(200, sets)
            }
        }
        val provider = provider(server)
        val shown = listOf("John Wick", "The Criterion Collection")
        val wall = provider.libraryPage(Library("lib3", LibraryKind.COLLECTIONS, "Sagas"), SortOption.TITLE, LibraryFilter(), 0, 120)
        assertEquals(shown, wall.items.map { it.title })
        assertTrue("the wall asks for their ids", "ProviderIds" in server.requests.last().url.queryParameter("fields").orEmpty().split(','))
        val films = Library("lib1", LibraryKind.MOVIES, "Films")
        provider.libraryPage(films, SortOption.TITLE, LibraryFilter(), 0, 120, grouped = true)
        assertTrue(
            "so does a films' wall grouped",
            "ProviderIds" in server.requests.last().url.queryParameter("fields").orEmpty().split(','),
        )
        provider.libraryPage(films, SortOption.TITLE, LibraryFilter(), 0, 120)
        assertFalse("not a films' wall alone", "ProviderIds" in server.requests.last().url.queryParameter("fields").orEmpty().split(','))
        assertEquals(shown, checkNotNull(provider.catalog).search("wick", 10).sagas.map { it.title })
        assertEquals("John Wick", provider.item("b1").title)
    }

    /** The films and their sagas: the server gives a collection's films as the collection; else every film. */
    @Test
    fun theServerGroupsACollectionsFilmsOnlyWhenAsked() = runTest {
        val server = FakeServer { Answer(200, oneFilm) }
        val provider = provider(server)
        val films = Library("lib1", LibraryKind.MOVIES, "Films")
        val grouped = provider.libraryPage(films, SortOption.TITLE, LibraryFilter(), start = 0, limit = 120, grouped = true)
        assertNull("the server does not count them", grouped.sagas)
        provider.libraryPage(films, SortOption.TITLE, LibraryFilter(), start = 0, limit = 120)
        provider.libraryPage(Library("lib2", LibraryKind.SHOWS, "Séries"), SortOption.TITLE, LibraryFilter(), start = 0, limit = 120)
        val collapsed = synchronized(server.requests) { server.requests.map { it.url.queryParameter("collapseBoxSetItems") } }
        assertEquals(listOf("true", "false", null), collapsed)
        assertFalse("a collection is never filtered by its films", provider.capabilities.sagaFilters)
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
        val provider = JellyfinProvider(account, "device", homeServer().http, retryDelaysMs = listOf(0, 0, 0), mapping = mapping)
        val films = Library("lib1", LibraryKind.MOVIES, "Films")
        val page = provider.libraryPage(films, SortOption.TITLE, LibraryFilter(), start = 0, limit = 120)
        assertEquals(listOf("Heat"), page.items.map { it.title })
        assertEquals("the wall's page", 1, mapping.runs.get())
        provider.home()
        assertEquals("home's rows", 2, mapping.runs.get())
    }

    @Test
    fun anEmptyServerIsNotAFailure() = runTest {
        assertEquals(emptyList<Any>(), provider(homeServer(rest = "{}")).home())
    }
}
