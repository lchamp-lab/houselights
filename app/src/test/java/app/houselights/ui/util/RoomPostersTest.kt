package app.houselights.ui.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import app.houselights.core.HomeSection
import app.houselights.core.ImageRef
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.SectionType
import app.houselights.core.SourceConfig
import app.houselights.providers.jellyfin.account
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The settings' room is read once a visit: the folders' latest posters, the welcome wall's only without folders, a
 * Jellyfin library's only as home showed them; the last ones come back at once.
 */
@RunWith(RobolectricTestRunner::class)
class RoomPostersTest {
    @get:Rule
    val compose = createComposeRule()

    private var source: SourceConfig? = SourceConfig.Local
    private var latest = listOf("/a.jpg", "/b.jpg")
    private var folders = true
    private var trendingAsked = 0

    private val room = RoomPosters(
        source = { source },
        latest = { latest },
        hasFolders = { folders },
        trending = {
            trendingAsked++
            listOf("https://image.tmdb.org/t/p/w300/wall.jpg")
        },
    )

    @Test
    fun theFoldersLatestNeverTheWelcomeWalls() = runBlocking {
        assertEquals(2, room.read(WIDTH).size)
        latest = emptyList()
        // Folders not read yet: nothing, rather than another library's posters.
        assertTrue(room.read(WIDTH).isEmpty())
        assertEquals(0, trendingAsked)
    }

    @Test
    fun withoutFoldersTheWelcomeWalls() = runBlocking {
        latest = emptyList()
        folders = false
        assertEquals(1, room.read(WIDTH).size)
        source = null
        assertEquals(1, room.read(WIDTH).size)
        assertEquals(2, trendingAsked)
    }

    @Test
    fun aJellyfinLibrarysAsHomeShowedThem() = runBlocking {
        source = account
        assertTrue("never asks the server", room.read(WIDTH).isEmpty())
        room.shown(account, listOf(HomeSection(SectionType.LATEST, listOf(film("m1"), film("m2")), landscape = false)))
        assertEquals(listOf(poster("m1"), poster("m2")), room.read(WIDTH))
        assertEquals("what was read comes back at once", 2, room.last(account).size)
        assertTrue(room.last(SourceConfig.Local).isEmpty())
        assertEquals(0, trendingAsked)
    }

    @Test
    fun aVisitReadsOnce() {
        var reads = 0
        var composed = 0
        var tick by mutableIntStateOf(0)
        lateinit var shown: List<ImageRef>
        compose.setContent {
            // Read here so that each tick recomposes.
            if (tick >= 0) composed++
            shown = rememberRoomPosters(listOf(poster("last"))) {
                reads++
                listOf(poster("read $reads"))
            }
        }
        compose.runOnIdle { tick++ }
        compose.runOnIdle { tick++ }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("recomposed: $composed", composed >= 3)
            assertEquals(1, reads)
            assertEquals(listOf(poster("read 1")), shown)
        }
    }

    private fun poster(id: String) = ImageRef(id) { "file:///$id.jpg" }

    private fun film(id: String) = MediaItem(id, MediaKind.MOVIE, id, poster = poster(id))

    private companion object {
        const val WIDTH = 296
    }
}
