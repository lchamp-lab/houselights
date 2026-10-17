package app.houselights.providers.jellyfin

import app.houselights.core.Job
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.SortOption
import app.houselights.core.SourceConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What a Jellyfin server tells through the catalog: People, a person's titles, a film's media sources, searches. */
@RunWith(RobolectricTestRunner::class)
class JellyfinCatalogTest {
    private val config = SourceConfig.Jellyfin("http://nas:8096", "Maison", "u1", "lea", "token")
    private val film = MediaItem("f1", MediaKind.MOVIE, "Heat")

    private val heat = """
        {"Id":"f1","Name":"Heat","Type":"Movie","ProductionYear":1995,
         "People":[
           {"Id":"p1","Name":"Michael Mann","Type":"Director","PrimaryImageTag":"t1"},
           {"Id":"p1","Name":"Michael Mann","Type":"Writer"},
           {"Id":"p2","Name":"Al Pacino","Role":"Vincent Hanna","Type":"Actor"},
           {"Id":"p3","Name":"Elliot Goldenthal","Type":"Composer"},
           {"Id":"p4","Name":"Art Linson","Type":"Producer"}],
         "MediaSources":[
           {"Id":"s1","Name":"Heat - 1080p","Path":"/films/Heat 1080p.mkv","Size":10,"Container":"mkv",
            "MediaStreams":[{"Type":"Video","Codec":"h264","Width":1920,"Height":800}]},
           {"Id":"s2","Name":"Heat - Director's Cut 2160p","Path":"/films/Heat DC 2160p.mkv","Size":60,"Container":"mkv",
            "MediaStreams":[{"Type":"Video","Codec":"hevc","Width":3840,"Height":1600,"VideoRangeType":"DOVIWithHDR10"}]}]}
    """.trimIndent()

    private fun server(extra: (String) -> String? = { null }) = FakeServer { request ->
        val path = request.url.encodedPath
        extra(request.url.toString())?.let { return@FakeServer Answer(200, it) }
        when {
            path == "/Items/f1" -> Answer(200, heat)
            path == "/Items/p1" -> Answer(200, """{"Id":"p1","Name":"Michael Mann","Type":"Person","ImageTags":{"Primary":"t1"}}""")
            path == "/Items" -> Answer(200, """{"Items":[],"TotalRecordCount":0}""")
            path == "/Persons" -> Answer(200, """{"Items":[{"Id":"p2","Name":"Al Pacino","Type":"Person"}],"TotalRecordCount":1}""")
            else -> Answer(404)
        }
    }

    private fun provider(server: FakeServer) = JellyfinProvider(config, "device", server.http)

    @Test
    fun aTitlesPeopleAreItsCreditsByJob() = runTest {
        val credits = provider(server()).catalog.credits(film)
        assertEquals(listOf("Al Pacino"), credits.cast.map { it.name })
        assertEquals("Vincent Hanna", credits.cast.single().role)
        assertEquals(setOf(Job.DIRECTOR, Job.WRITER, Job.COMPOSER), credits.crew.keys)
        assertEquals("p1", credits.crew.getValue(Job.DIRECTOR).single().id)
        assertNull("the server does not count their titles", credits.crew.getValue(Job.DIRECTOR).single().titleCount)
    }

    @Test
    fun aPersonsTitlesAreTheServersWithWhatTheyDidOnEach() = runTest {
        val titles = """{"Items":[
            {"Id":"f1","Name":"Heat","Type":"Movie","ProductionYear":1995,"People":[{"Id":"p1","Name":"Michael Mann","Type":"Director"},{"Id":"p1","Name":"Michael Mann","Type":"Writer"}]},
            {"Id":"f2","Name":"Collateral","Type":"Movie","ProductionYear":2004,"People":[{"Id":"p1","Name":"Michael Mann","Type":"Director"}]}
            ],"TotalRecordCount":2}"""
        val server = server { url -> titles.takeIf { "personIds=p1" in url } }
        val page = checkNotNull(provider(server).catalog.person("p1"))
        assertEquals("Michael Mann", page.person.name)
        assertEquals(listOf("Heat", "Collateral"), page.titles.map { it.item.title })
        assertEquals(listOf(Job.DIRECTOR, Job.WRITER), page.titles.first().jobs)
        assertEquals(mapOf(Job.DIRECTOR to 2, Job.WRITER to 1), page.jobs)
    }

    @Test
    fun aFilmsMediaSourcesAreItsCopiesAndOnePlaysByItsId() = runTest {
        val server = server()
        val catalog = provider(server).catalog
        val versions = catalog.versions(film)
        assertEquals(listOf("s1", "s2"), versions.map { it.key })
        assertEquals(listOf(true, false), versions.map { it.chosen })
        assertEquals(listOf(false, true), versions.map { it.best })
        assertEquals("Director's Cut", versions.last().edition)
        assertEquals(60L, versions.last().sizeBytes)

        val plan = catalog.playback(versions.last(), resume = false, PlaybackOptions(emptyList(), forceHttp = true)) as PlaybackPlan.Launch
        assertTrue(plan.uri, "mediaSourceId=s2" in plan.uri)
    }

    @Test
    fun searchesAndAPersonsWallAreTheServers() = runTest {
        val server = server()
        val provider = provider(server)
        val found = provider.catalog.search("pacino")
        assertEquals(listOf("Al Pacino"), found.people.map { it.name })
        assertTrue(server.requests.any { it.url.encodedPath == "/Items" && it.url.queryParameter("searchTerm") == "pacino" })

        provider.libraryPage(Library("lib1", LibraryKind.MOVIES, "Films"), SortOption.TITLE, LibraryFilter(person = "p2"), 0, 10)
        assertTrue(server.requests.any { it.url.queryParameter("personIds") == "p2" })
        assertTrue(provider.capabilities.personFilter && !provider.capabilities.sagaParts)
    }
}
