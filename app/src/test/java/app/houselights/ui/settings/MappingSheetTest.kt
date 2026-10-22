package app.houselights.ui.settings

import app.houselights.core.PathMapping
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A path mapping's sheet on the TV: its password never shown, and kept until another is typed. */
@RunWith(RobolectricTestRunner::class)
class MappingSheetTest {
    private val was = PathMapping("/media/films", "smb://lecteur:motdepasse@nas/films")

    @Test
    fun thePasswordIsNotShown() {
        assertEquals("smb://***@nas/films", shownTo(was))
        assertEquals("", shownTo(null))
    }

    @Test
    fun itIsKeptWhileItsMaskIs() {
        assertEquals(was, editedMapping(was, "/media/films", shownTo(was)))
        assertEquals("smb://lecteur:motdepasse@nas/movies", editedMapping(was, "/media/films", "smb://***@nas/movies").to)
        // Typed unescaped, with a slash and an @ in it.
        val raw = PathMapping("/media", "smb://lecteur:p/ss@w0rd@nas/media")
        assertEquals("smb://***@nas/media", shownTo(raw))
        assertEquals(raw, editedMapping(raw, "/media", shownTo(raw)))
        assertEquals("smb://lecteur:p/ss@w0rd@nas/videos", editedMapping(raw, "/media", "smb://***@nas/videos").to)
    }

    @Test
    fun anotherAccountOrNoneIsTakenAsTyped() {
        assertEquals("smb://invite:secret@nas/films", editedMapping(was, "/media/films", "smb://invite:secret@nas/films").to)
        assertEquals("smb://nas/films", editedMapping(was, "/media/films", "smb://nas/films").to)
        assertEquals(PathMapping("/media", "nfs://nas/films"), editedMapping(null, " /media ", " nfs://nas/films "))
    }
}
