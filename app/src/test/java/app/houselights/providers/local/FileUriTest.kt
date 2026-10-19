package app.houselights.providers.local

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
