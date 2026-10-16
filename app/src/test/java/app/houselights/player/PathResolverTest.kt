package app.houselights.player

import app.houselights.core.PathMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PathResolverTest {
    private val mappings = listOf(
        PathMapping("/data", "nfs://nas/volume1"),
        PathMapping("/data/films", "smb://zidoo:p%40ss@nas/films"),
        PathMapping("", "smb://nas/ignored"),
    )

    @Test
    fun theLongestRuleApplies() {
        assertEquals("smb://zidoo:p%40ss@nas/films/Heat.mkv", PathResolver.resolve("/data/films/Heat.mkv", mappings))
        assertEquals("nfs://nas/volume1/series/Dark/S01E01.mkv", PathResolver.resolve("/data/series/Dark/S01E01.mkv", mappings))
    }

    @Test
    fun aWindowsPathIsReadWithForwardSlashes() {
        val windows = listOf(PathMapping("D:\\Media\\", "smb://nas/media"))
        assertEquals("smb://nas/media/Films/Heat.mkv", PathResolver.resolve("D:\\Media\\Films\\Heat.mkv", windows))
        assertEquals("smb://server/share/Films/Heat.mkv", PathResolver.resolve("\\\\server\\share\\Films\\Heat.mkv", emptyList()))
    }

    /** "#" would start a fragment and "?" a query: the player would open a file named up to there. */
    @Test
    fun whatWouldEndThePathIsEscaped() {
        assertEquals(
            "nfs://nas/volume1/films/Amélie %232 100%25%3F.mkv",
            PathResolver.resolve("/data/films/Amélie #2 100%?.mkv", listOf(PathMapping("/data", "nfs://nas/volume1"))),
        )
        // A network path the server gives is the administrator's: "%" may already be escaped there.
        assertEquals("smb://nas/films/Heat %232%3F 100%25.mkv", PathResolver.resolve("smb://nas/films/Heat #2? 100%25.mkv", emptyList()))
        assertEquals("smb://server/share/Heat %232.mkv", PathResolver.resolve("\\\\server\\share\\Heat #2.mkv", emptyList()))
    }

    @Test
    fun withoutARuleThereIsNothingToOpen() {
        assertNull(PathResolver.resolve("/srv/films/Heat.mkv", mappings))
        assertNull(PathResolver.resolve(null, mappings))
        assertNull(PathResolver.resolve(" ", mappings))
        // A rule's folder, not a name that starts like it.
        assertNull(PathResolver.resolve("/database/Heat.mkv", listOf(PathMapping("/data", "nfs://nas/volume1"))))
    }
}
