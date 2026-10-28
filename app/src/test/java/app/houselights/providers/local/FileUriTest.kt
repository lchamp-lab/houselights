package app.houselights.providers.local

import app.houselights.core.DiskAbsentException
import app.houselights.core.SecretUnreadException
import app.houselights.ui.util.UiError
import app.houselights.ui.util.toUiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The address the player is given for a folder's file, credentials and all. */
@RunWith(RobolectricTestRunner::class)
class FileUriTest {
    private fun source(protocol: Protocol, user: String = "", password: String = "", domain: String = "") = SourceEntity(
        name = "Films", kind = SourceKind.MOVIES, protocol = protocol, host = "nas", share = "/volume1/media/",
        path = "films/", username = user, password = Secret(password), domain = domain,
    )

    /** Credentials and paths are escaped where they would end a URI; an account of a domain is written domain;user. */
    @Test
    fun aFilesAddressNamesItsFolderAndEscapesWhatWouldEndIt() {
        val uris = listOf(
            "nfs://nas/volume1/media/films/Top Gun.mkv" to LocalProvider.fileUri(source(Protocol.NFS), "Top Gun.mkv"),
            "smb://zidoo:p%40ss%3Aword@nas/volume1/media/films/Top Gun.mkv" to
                LocalProvider.fileUri(source(Protocol.SMB, "zidoo", "p@ss:word"), "Top Gun.mkv"),
            "smb://MAISON;zidoo:p%2Fss%23@nas/volume1/media/films/Top Gun.mkv" to
                LocalProvider.fileUri(source(Protocol.SMB, "zidoo", "p/ss#", domain = "MAISON"), "Top Gun.mkv"),
            "nfs://nas/volume1/media/films/Fast %23 Furious 100%25%3F.mkv" to
                LocalProvider.fileUri(source(Protocol.NFS), "Fast # Furious 100%?.mkv"),
        )
        uris.forEach { (expected, uri) -> assertEquals(expected, uri) }
    }

    /**
     * A film of the player's own disks is a file:// URI on where its disk is mounted now, escaped as a share's; never
     * handed as such (PlaybackManager). Without a mount, the disk is out of reach as a NAS asleep.
     */
    @Test
    fun aDisksFileIsOnItsMountNow() {
        val films = diskFolder(path = "Vidéos/Films/")
        assertEquals(
            "file:///storage/1234-ABCD/Vidéos/Films/Fast %23 Furious 100%25%3F.mkv",
            LocalProvider.fileUri(films, "Fast # Furious 100%?.mkv", File("/storage/1234-ABCD/")),
        )
        assertEquals(
            "file:///mnt/media_rw/1234-ABCD/Vidéos/Films/Heat.mkv",
            LocalProvider.fileUri(films, "Heat.mkv", File("/mnt/media_rw/1234-ABCD")),
        )
        val absent = assertThrows(DiskAbsentException::class.java) { LocalProvider.fileUri(films, "Heat.mkv") }
        assertEquals(UiError.DISK_ABSENT, absent.toUiError())
    }

    /** A password the Keystore did not open for now is never handed to the player empty; a guest needs none. */
    @Test
    fun aPasswordNotOpenedIsNeverGivenEmpty() {
        val unopened = Secret("", unopened = "sealed1:AAAA")
        assertThrows(SecretUnreadException::class.java) {
            LocalProvider.fileUri(source(Protocol.SMB, "zidoo").copy(password = unopened), "Top Gun.mkv")
        }
        assertEquals(
            "smb://nas/volume1/media/films/Top Gun.mkv",
            LocalProvider.fileUri(source(Protocol.SMB).copy(password = unopened), "Top Gun.mkv"),
        )
    }
}
