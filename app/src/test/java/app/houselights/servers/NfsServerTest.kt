package app.houselights.servers

import app.houselights.providers.local.Protocol
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.servers.TestServers.refusal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The NFS NAS of tools/test-servers (unfs3, NFSv3, each reply in one fragment as a NAS sends it): its exports as the
 * app mounts them, from a non-privileged port and as root squashed, and what it refuses. NfsFragmentsServerTest runs
 * them all again on the same NAS sending its replies in fragments.
 *
 * unfs3 answers READDIRPLUS with NFS3ERR_NOTSUPP, so these tests list with READDIR, the fallback, where a Synology, a
 * QNAP, TrueNAS or Unraid answer READDIRPLUS. That path is held by NfsListingTest alone, over a fake export: its pages,
 * cookies and attributes as NfsBrowser reads them; nfs-client's own READDIRPLUS, its request and its answer's decoding
 * (Nfs3Export.readdirplus), by no test.
 */
@RunWith(RobolectricTestRunner::class)
open class NfsServerTest : NasServerTest() {
    /** Where the NAS answers. */
    protected open val host: String get() = TestServers.host

    protected fun folder(export: String, path: String = "", uid: Int? = null, gid: Int? = null) = SourceEntity(
        name = "Test", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = host, share = export, path = path,
        username = "", password = Secret(""), uid = uid, gid = gid,
    )

    override fun video(path: String) = folder("volume1/video", path)

    @Test
    fun aFolderPresentingAUserOfTheNasReadsAsThatUser() = runBlocking {
        val films = folder("volume1/video", "Films", uid = 1026, gid = 100)
        assertEquals(Samples.FILMS_ENTRIES, browsers.of(films).test(films))
    }

    @Test
    fun aSecondExportOfTheNasPassesItsTest() = runBlocking {
        val media = folder("volume1/media")
        assertEquals(2, browsers.of(media).test(media))
    }

    @Test
    fun anExportThatIsNotThereIsNotFound() = runBlocking {
        val (kind, failure) = browsers.refusal(folder("volume1/videos"))
        assertEquals(failure, ScanErrorKind.NOT_FOUND, kind)
    }

    /** /volume1/private allows 192.0.2.10 alone: the NAS answers MNT3ERR_ACCES to the mount. */
    @Test
    fun anExportThatDoesNotAllowThisDeviceIsRefused() = runBlocking {
        val (kind, failure) = browsers.refusal(folder("volume1/private"))
        assertEquals(failure, ScanErrorKind.ACCESS_DENIED, kind)
    }

    /** « photos » is a shared folder of the NAS without an NFS rule (issue #79, its third cause). */
    @Test
    fun aSharedFolderWithoutAnNfsRuleIsRefused() = runBlocking {
        val (kind, failure) = browsers.refusal(folder("volume1/photos"))
        assertEquals(failure, ScanErrorKind.ACCESS_DENIED, kind)
    }

    /**
     * /volume1/public keeps Synology's default NFS rule, « Allow connections from non-privileged ports » off: its mount
     * from the app's port, above 1023, is refused as on a Synology, where the folder settings' NFS hint says to allow
     * them.
     */
    @Test
    fun anExportForPrivilegedPortsAloneIsRefused() = runBlocking {
        val (kind, failure) = browsers.refusal(folder("volume1/public"))
        assertEquals(failure, ScanErrorKind.ACCESS_DENIED, kind)
    }

    /** The shared folder's name without /volume1, as a Synology answers it (issue #59): refused, not « not found ». */
    @Test
    fun aSharedFoldersNameAloneIsRefusedAsOnASynology() = runBlocking {
        val (kind, failure) = browsers.refusal(folder("media"))
        assertEquals(failure, ScanErrorKind.ACCESS_DENIED, kind)
    }
}
