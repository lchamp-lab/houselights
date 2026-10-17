package app.houselights.providers.local

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileUriTest {
    private fun source(protocol: Protocol, user: String = "", password: String = "", domain: String = "") = SourceEntity(
        name = "Films", kind = SourceKind.MOVIES, protocol = protocol, host = "nas", share = "/volume1/media/",
        path = "films/", username = user, password = Secret(password), domain = domain,
    )

    @Test
    fun nfs() {
        assertEquals(
            "nfs://nas/volume1/media/films/Top Gun.mkv",
            LocalProvider.fileUri(source(Protocol.NFS), "Top Gun.mkv"),
        )
    }

    @Test
    fun smbEncodesCredentials() {
        assertEquals(
            "smb://zidoo:p%40ss%3Aword@nas/volume1/media/films/Top Gun.mkv",
            LocalProvider.fileUri(source(Protocol.SMB, "zidoo", "p@ss:word"), "Top Gun.mkv"),
        )
    }

    /** An account of a domain, as SMB URLs write it: domain;user. */
    @Test
    fun smbNamesTheDomainBeforeTheUser() {
        assertEquals(
            "smb://MAISON;zidoo:p%2Fss%23@nas/volume1/media/films/Top Gun.mkv",
            LocalProvider.fileUri(source(Protocol.SMB, "zidoo", "p/ss#", domain = "MAISON"), "Top Gun.mkv"),
        )
    }

    @Test
    fun pathCharactersThatEndAUriAreEscaped() {
        assertEquals(
            "nfs://nas/volume1/media/films/Fast %23 Furious 100%25%3F.mkv",
            LocalProvider.fileUri(source(Protocol.NFS), "Fast # Furious 100%?.mkv"),
        )
    }
}
