package app.houselights.providers.local

import androidx.test.core.app.ApplicationProvider
import app.houselights.core.DiskAbsentException
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.core.escapeUriPath
import app.houselights.ui.util.UiError
import app.houselights.ui.util.toUiError
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files

/**
 * Lire on a film of the player's own disks: from where its disk is mounted at that moment, or said out of reach; never
 * what a link leads to off the disk.
 */
@RunWith(RobolectricTestRunner::class)
class DiskPlaybackTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val db = testDatabase { allowMainThreadQueries() }
    private val dao = db.dao()
    private val disks = TestDisks()
    private val provider = LocalProvider(ApplicationProvider.getApplicationContext(), dao, NoEditor, volumes = disks)
    private val options = PlaybackOptions(emptyList(), forceHttp = false)

    @After
    fun close() = db.close()

    private suspend fun heat(): String {
        val folder = dao.insertSource(diskFolder(path = "Films"))
        val id = dao.insert(
            ItemEntity(
                kind = ItemKind.MOVIE,
                sourceId = folder,
                title = "Heat",
                sortTitle = "heat",
                filePath = "Heat (1995)/Heat (1995).mkv",
                addedAt = 0,
            ),
        )
        return id.toString()
    }

    private suspend fun uri(id: String) = (provider.playback(provider.item(id), resume = false, options) as PlaybackPlan.Launch).uri

    @Test
    fun aFilmPlaysFromWhereItsDiskIsMountedNow() = runTest {
        val heat = heat()
        disks.plug(USB_DISK, File("/storage/1234-ABCD"))
        assertEquals("file:///storage/1234-ABCD/Films/Heat (1995)/Heat (1995).mkv", uri(heat))

        // Plugged back where Android mounts it now.
        disks.plug(USB_DISK, File("/storage/usb2/1234-ABCD"))
        assertEquals("file:///storage/usb2/1234-ABCD/Films/Heat (1995)/Heat (1995).mkv", uri(heat))

        disks.unplug(USB_DISK)
        val absent = assertThrows(DiskAbsentException::class.java) { runBlocking { uri(heat) } }
        assertEquals("said as its disk not found", UiError.DISK_ABSENT, absent.toUiError())
    }

    /**
     * A film that is a link leading off its disk, to the app's own files say (a disk formatted ext4 holds links), is never
     * handed to a player, which a content:// address would let read what it leads to; a link within the disk plays.
     */
    @Test
    fun aLinkLeadingOffItsDiskIsNeverHandedToAPlayer() = runTest {
        val heat = heat()
        val disk = temp.newFolder(USB_DISK)
        disks.plug(USB_DISK, disk)
        val film = disk.resolve("Films/Heat (1995)/Heat (1995).mkv").toPath()
        Files.createDirectories(film.parent)
        Files.createSymbolicLink(film, temp.newFile("library.db").toPath())

        val refused = assertThrows(AccessDeniedException::class.java) { runBlocking { uri(heat) } }
        assertEquals(UiError.FAILED, refused.toUiError())

        Files.delete(film)
        Files.createSymbolicLink(film, disk.put("Archives/Heat.mkv").toPath())
        assertEquals("file://${escapeUriPath(disk.path)}/Films/Heat (1995)/Heat (1995).mkv", uri(heat))
    }
}
