package app.houselights.providers.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * What « Ajouter un dossier » reads of the library's folders on the NAS chosen: how each row it shows stands to them,
 * said before anything is chosen, and the account and identity a new folder there reuses.
 */
class SameNasTest {
    private fun folder(
        id: Long,
        share: String,
        path: String,
        protocol: Protocol = Protocol.NFS,
        host: String = "192.168.1.20",
        user: String = "",
        password: String = "",
        uid: Int? = null,
        gid: Int? = null,
    ) = SourceEntity(
        id = id, name = path.substringAfterLast('/').ifEmpty { share }, kind = SourceKind.MOVIES, protocol = protocol, host = host,
        share = share, path = path, username = user, password = Secret(password), uid = uid, gid = gid,
    )

    private val films = folder(1, "volume1/video", "Films")
    private val shows = folder(2, "volume1/video", "Séries/Animées")
    private val library = listOf(films, shows)

    /** The overlap per row: the folder itself, one holding the library's, one inside it, one apart, as adding finds them. */
    @Test
    fun eachRowSaysHowItStandsToTheLibrary() {
        assertEquals(Standing.Added(films), folder(0, "volume1/video", "Films").standing(library))
        assertEquals("spelled otherwise", Standing.Added(films), folder(0, "/volume1/video/", "Films/").standing(library))
        assertEquals(Standing.Holds(listOf(films, shows)), folder(0, "volume1/video", "").standing(library))
        assertEquals(Standing.Holds(listOf(shows)), folder(0, "volume1/video", "Séries").standing(library))
        assertEquals(Standing.Inside(films), folder(0, "volume1/video", "Films/4K").standing(library))
        assertEquals("a sibling", Standing.Apart, folder(0, "volume1/video", "Films2").standing(library))
        assertEquals("NFS tells case apart", Standing.Apart, folder(0, "volume1/video", "films").standing(library))
        assertEquals("another protocol", Standing.Apart, folder(0, "volume1/video", "Films", Protocol.SMB).standing(library))
        assertEquals("another NAS", Standing.Apart, folder(0, "volume1/video", "Films", host = "192.168.1.31").standing(library))
        val smb = folder(3, "video", "Films", Protocol.SMB)
        assertEquals("SMB ignores case", Standing.Added(smb), folder(0, "Video", "films", Protocol.SMB).standing(listOf(smb)))
    }

    /** The library's folders on one NAS, however its host is spelled, the latest added first. */
    @Test
    fun theFoldersOfOneNasAreReadLatestFirst() {
        val other = folder(3, "media", "films", host = "192.168.1.31")
        val spelled = folder(4, "volume1/video", "4K", host = " 192.168.1.20 ")
        assertEquals(listOf(spelled, shows, films), onNas(listOf(films, shows, other, spelled), "192.168.1.20"))
        assertEquals(listOf(other), onNas(listOf(films, other), " 192.168.1.31"))
        assertEquals(emptyList<SourceEntity>(), onNas(library, "192.168.1.99"))
    }

    /**
     * A new SMB folder signs in as the latest SMB folder of its NAS does, its password reused, or as a guest after a
     * guest's; a new NFS folder presents the latest NFS folder's user and group, root after a root one.
     */
    @Test
    fun aNewFolderReusesTheLatestAccountAndIdentityOfItsNas() {
        val kodi = folder(5, "video", "Films", Protocol.SMB, user = "kodi", password = "secret")
        val guest = folder(6, "video", "Séries", Protocol.SMB)
        assertSame(kodi, listOf(kodi, films).smbAccess())
        assertSame("after a guest, a guest", guest, listOf(guest, kodi).smbAccess())
        assertNull(library.smbAccess())

        val squashed = folder(7, "volume1/video", "4K", uid = 1026, gid = 100)
        assertEquals(1026 to 100, listOf(kodi, squashed, films).nfsIdentity())
        assertNull("root after root", listOf(films, squashed).nfsIdentity())
        assertNull(listOf(kodi).nfsIdentity())
    }
}
