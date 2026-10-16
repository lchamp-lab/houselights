package app.houselights.ui.onboarding

import app.houselights.providers.local.Protocol
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The form of a folder edited: filled in from it, saved under its id, its password kept unless typed again. */
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
