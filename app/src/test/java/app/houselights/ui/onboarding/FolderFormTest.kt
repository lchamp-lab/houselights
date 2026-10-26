package app.houselights.ui.onboarding

import app.houselights.R
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The form of a folder: edited, filled in from it, saved under its id, its password kept unless typed again; typed, its
 * share and folder read however much of the NAS's path each field holds, as long as nothing typed points elsewhere.
 */
class FolderFormTest {
    private val stored = SourceEntity(
        id = 7, name = "films", kind = SourceKind.SHOWS, protocol = Protocol.SMB, host = "192.168.1.10", share = "media",
        path = "films", username = "kodi", password = Secret("secret"), domain = "WORKGROUP",
    )

    @Test
    fun theFormOfAFolderShowsAllButItsPassword() {
        val form = FolderForm(
            kind = SourceKind.SHOWS, protocol = Protocol.SMB, host = "192.168.1.10", share = "media", path = "films", user = "kodi",
        )
        assertEquals(form, FolderForm.of(stored))
        val nfs = stored.copy(protocol = Protocol.NFS, share = "volume1/media", username = "", password = Secret(""))
        assertEquals("/volume1/media", FolderForm.of(nfs).share)
    }

    @Test
    fun aPasswordLeftEmptyStays() {
        val edited = FolderForm.of(stored).copy(host = "192.168.1.20").toSource(stored)
        assertEquals(7L, edited.id)
        assertEquals("192.168.1.20", edited.host)
        assertEquals("secret", edited.password.value)
        assertEquals("WORKGROUP", edited.domain)
    }

    @Test
    fun aPasswordTypedAgainReplacesIt() {
        assertEquals("new", FolderForm.of(stored).copy(password = "new").toSource(stored).password.value)
    }

    /** SMB: a share typed with the folders under it is the share alone, the rest read before the folder, "\" or "/". */
    @Test
    fun anSmbShareTypedWithItsPathIsSplit() {
        val smb = FolderForm(protocol = Protocol.SMB, host = "192.168.1.10")
        listOf("video/Films2", "video\\Films2", "/video/Films2/", " \\video\\Films2\\ ").forEach { typed ->
            assertEquals(typed, "video" to "Films2", smb.copy(share = typed).toSource().place())
        }
        val deeper = smb.copy(share = "video\\Films", path = "HD\\4K").toSource()
        assertEquals("video" to "Films/HD/4K", deeper.place())
        assertEquals("4K", deeper.name)
        val twice = smb.copy(share = "video/Films", path = "video/Films/HD").toSource()
        assertEquals("typed whole in both fields, read once", "video" to "Films/HD", twice.place())
    }

    /** SMB: the NAS's address before the share, as Windows or a link writes it, is dropped; the address field says where. */
    @Test
    fun aShareTypedAsUncDropsItsHost() {
        val smb = FolderForm(protocol = Protocol.SMB, host = "192.168.1.10")
        listOf("\\\\NAS\\video\\Films2", "\\\\NAS\\video\\Films2\\", "smb://nas.local/video/Films2", "SMB://192.168.1.10/video/Films2")
            .forEach { typed ->
                val source = smb.copy(share = typed).toSource()
                assertEquals(typed, listOf("192.168.1.10", "video", "Films2"), listOf(source.host, source.share, source.path))
            }
        assertEquals("video" to "", smb.copy(share = "\\\\NAS\\video").toSource().place())
        val inFolder = smb.copy(share = "video", path = "\\\\NAS\\video\\Films2").toSource()
        assertEquals("the whole address in the folder's field, under its share", "video" to "Films2", inFolder.place())
        assertEquals("the address alone names no share", R.string.error_share_missing, smb.copy(share = "\\\\NAS\\").missing())
    }

    /** Only the whole share again is dropped: a folder whose name is only like it is read as typed. */
    @Test
    fun aFolderNamedLikeItsShareButNotRepeatingItIsKept() {
        val nfs = FolderForm(protocol = Protocol.NFS, host = "nas.local", share = "/volume1/video")
        // The export's last name alone, another export, another name, the export in another case (NFS tells them apart).
        listOf("video/Films2", "/volume1/Films2", "volume1/videos/Films2", "/volume1/Video/Films2").forEach { typed ->
            assertEquals(typed, "volume1/video" to typed.trim('/'), nfs.copy(path = typed).toSource().place())
        }
        val backslashes = nfs.copy(path = "volume1\\video\\Films2").toSource()
        assertEquals("a \\ is part of a name over NFS", "volume1\\video\\Films2", backslashes.path)

        val smb = FolderForm(protocol = Protocol.SMB, host = "192.168.1.10", share = "video")
        listOf("videos/Films2", "Films2/video", "vid/eo/Films2").forEach { typed ->
            assertEquals(typed, "video" to typed, smb.copy(path = typed).toSource().place())
        }
        assertEquals("an address before another share stays", "NAS/Films2", smb.copy(path = "\\\\NAS\\Films2").toSource().path)
        assertEquals("a folder named as its share, typed from above it", "video", smb.copy(path = "video/video").toSource().path)
    }

    private fun SourceEntity.place() = share to path

    @Test
    fun aFolderKeepsItsKindAndNfsHasNoPassword() {
        val form = FolderForm.of(stored).copy(kind = SourceKind.MOVIES, protocol = Protocol.NFS, share = "/volume1/media")
        val edited = form.toSource(stored)
        assertEquals(SourceKind.SHOWS, edited.kind)
        assertEquals("volume1/media", edited.share)
        assertEquals("", edited.username)
        assertEquals("", edited.password.value)
    }
}
