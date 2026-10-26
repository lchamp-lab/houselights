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

    /**
     * SMB: the NAS's address before the share, as Windows or a link writes it, is dropped when it names the NAS of the
     * address field, as written there or by its first name; the address field says where.
     */
    @Test
    fun aShareTypedAsUncDropsItsHost() {
        val smb = FolderForm(protocol = Protocol.SMB, host = "nas.local")
        listOf("\\\\NAS\\video\\Films2", "\\\\NAS\\video\\Films2\\", "smb://nas.local/video/Films2", "SMB://NAS.LOCAL/video/Films2")
            .forEach { typed ->
                val source = smb.copy(share = typed).toSource()
                assertEquals(typed, listOf("nas.local", "video", "Films2"), listOf(source.host, source.share, source.path))
            }
        val byIp = smb.copy(host = "192.168.1.10", share = "\\\\192.168.1.10\\video\\Films2").toSource()
        assertEquals("video" to "Films2", byIp.place())
        assertEquals("video" to "", smb.copy(share = "\\\\NAS\\video").toSource().place())
        val inFolder = smb.copy(share = "video", path = "\\\\NAS\\video\\Films2").toSource()
        assertEquals("the whole address in the folder's field, under its share", "video" to "Films2", inFolder.place())
        listOf("\\\\NAS\\", "\\\\NAS").forEach { typed ->
            assertEquals("the address alone names no share", R.string.error_share_missing, smb.copy(share = typed).missing())
        }
    }

    /**
     * SMB: a name after "\\" that is not the address field's NAS is no address but the share it names, so a share typed
     * after "\\" or "//" stays that share rather than giving way to the next name, and a path on another NAS is never
     * looked for on this one.
     */
    @Test
    fun aNameAfterTwoSlashesThatIsNotTheNasIsTheShare() {
        val smb = FolderForm(protocol = Protocol.SMB, host = "192.168.1.10")
        listOf("\\\\video\\Films", "//video/Films", "\\\\video\\Films\\").forEach { typed ->
            assertEquals(typed, "video" to "Films", smb.copy(share = typed).toSource().place())
        }
        assertEquals("another NAS's share", "192.168.1.20" to "video", smb.copy(share = "\\\\192.168.1.20\\video").toSource().place())
        val elsewhere = smb.copy(share = "video", path = "\\\\192.168.1.20\\video\\Films").toSource()
        assertEquals("another NAS's folder", "video" to "192.168.1.20/video/Films", elsewhere.place())
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

    /**
     * A folder inside a folder named as its share (or its export) shows in « Dossier » after the share again, as it would
     * be typed from the NAS's root, so its form gives it back where it is: a password typed again never moves it.
     */
    @Test
    fun anEditedFolderInsideAFolderNamedAsItsShareKeepsItsPath() {
        val nfs = stored.copy(protocol = Protocol.NFS, username = "", password = Secret(""))
        listOf(
            stored.copy(share = "video", path = "video"),
            stored.copy(share = "video", path = "video/Films"),
            stored.copy(share = "Films", path = "films/HD"),
            nfs.copy(share = "volume1", path = "volume1/x"),
            nfs.copy(share = "volume1/video", path = "volume1/video/Films2"),
            stored,
        ).forEach { folder ->
            val edited = FolderForm.of(folder).copy(password = "new").toSource(folder)
            assertEquals("${folder.share} ${folder.path}", folder.place(), edited.place())
        }
        assertEquals("video/video/Films", FolderForm.of(stored.copy(share = "video", path = "video/Films")).path)
        assertEquals("/volume1/volume1/x", FolderForm.of(nfs.copy(share = "volume1", path = "volume1/x")).path)
        assertEquals("films", FolderForm.of(stored).path)
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
