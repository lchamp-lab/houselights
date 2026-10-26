package app.houselights.providers.local

import androidx.test.core.app.ApplicationProvider
import app.houselights.core.DiskAbsentException
import app.houselights.core.PlaybackOptions
import app.houselights.core.PlaybackPlan
import app.houselights.ui.util.UiError
import app.houselights.ui.util.toUiError
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Lire on a film of the player's own disks: from where its disk is mounted at that moment, or said out of reach. */
@RunWith(RobolectricTestRunner::class)
class DiskPlaybackTest {
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
                kind = ItemKind.MOVIE, sourceId = folder, title = "Heat", sortTitle = "heat", filePath = "Heat (1995)/Heat (1995).mkv",
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
        assertEquals("said as a NAS out of reach", UiError.OFFLINE, absent.toUiError())
    }
}
