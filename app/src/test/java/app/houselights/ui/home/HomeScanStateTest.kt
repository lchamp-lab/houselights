package app.houselights.ui.home

import app.houselights.providers.local.Protocol
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.ScanState
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What home says of a scan, folder by folder: two folders may bear the same name. */
class HomeScanStateTest {
    private fun folder(id: Long, host: String, lastScanAt: Long = 0) = SourceEntity(
        id = id, name = "Films", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = host, share = "volume1",
        path = "Films", username = "", password = Secret(""), lastScanAt = lastScanAt,
    )

    private val read = folder(1, "nas1", lastScanAt = 1_800_000_000_000L)
    private val new = folder(2, "nas2")

    @Test
    fun aFolderReadAgainIsNoFirstScanThoughAnotherOfItsNameIsNew() {
        val scan = ScanState(running = true, sourceId = read.id)
        assertFalse(firstScan(scan, empty = false, folders = listOf(read, new)))
        assertTrue(firstScan(scan.copy(sourceId = new.id), empty = false, folders = listOf(read, new)))
    }

    @Test
    fun aScanFailedNamesTheFolderItFailedOn() {
        val off = ScanError(ScanErrorKind.UNREACHABLE)
        assertEquals("nas2", failedFolder(mapOf(new.id to off), listOf(read, new))?.first?.host)
        assertNull("a folder removed since says nothing", failedFolder(mapOf(9L to off), listOf(read, new)))
    }
}
