package app.houselights.ui.settings

import app.houselights.providers.local.Folder
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.SourceKind
import app.houselights.ui.util.FACT_GAP
import org.junit.Assert.assertEquals
import org.junit.Test

/** A folder's sheet says where it is: its NAS's address, then its path, apart by the app's space, never a dot. */
class FolderPlaceTest {
    private fun folder(protocol: Protocol, address: String) = Folder(
        id = 1,
        name = "Films",
        kind = SourceKind.MOVIES,
        protocol = protocol,
        host = "192.168.1.20",
        address = address,
        lastScanAt = 0,
    )

    @Test
    fun theAddressThenThePathAsTheFormComposesIt() {
        assertEquals("192.168.1.20$FACT_GAP/volume1/video/Films", placeOf(folder(Protocol.NFS, "192.168.1.20/volume1/video/Films")))
        assertEquals("192.168.1.20$FACT_GAP/video/Séries", placeOf(folder(Protocol.SMB, "192.168.1.20/video/Séries")))
        assertEquals("an export at the NAS's root", "192.168.1.20", placeOf(folder(Protocol.NFS, "192.168.1.20")))
    }

    /** A folder of this player's disks: the disk as « Ajouter un dossier » names it, never its id, then its path. */
    @Test
    fun theDiskThenThePath() {
        val films = Folder(
            id = 2,
            name = "Films",
            kind = SourceKind.MOVIES,
            protocol = Protocol.LOCAL,
            host = "1234-ABCD",
            address = "1234-ABCD/Films",
            lastScanAt = 0,
        )
        assertEquals("Samsung T7$FACT_GAP/Films", placeOf(films, disk = "Samsung T7"))
        assertEquals("a disk's root", "Samsung T7", placeOf(films.copy(address = "1234-ABCD"), disk = "Samsung T7"))
    }
}
