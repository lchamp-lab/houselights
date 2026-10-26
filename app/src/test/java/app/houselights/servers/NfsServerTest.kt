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
 * The NFS NAS of tools/test-servers (unfs3, NFSv3, answering READDIRPLUS with NFS3ERR_NOTSUPP so the app lists with
 * READDIR): its exports as the app mounts them, from a non-privileged port and as root squashed, and what it refuses.
 */
@RunWith(RobolectricTestRunner::class)
class NfsServerTest : NasServerTest() {
    private fun folder(export: String, path: String = "", uid: Int? = null, gid: Int? = null) = SourceEntity(
        name = "Test", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = TestServers.host, share = export, path = path,
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

    /** The shared folder's name without /volume1, as a Synology answers it (issue #59): refused, not « not found ». */
    @Test
    fun aSharedFoldersNameAloneIsRefusedAsOnASynology() = runBlocking {
        val (kind, failure) = browsers.refusal(folder("media"))
        assertEquals(failure, ScanErrorKind.ACCESS_DENIED, kind)
    }
}
