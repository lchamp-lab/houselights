package app.houselights.providers.local

import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.emc.ecs.nfsclient.rpc.RpcException
import com.emc.ecs.nfsclient.rpc.RpcStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Which NFS failures, as nfs-client 1.1 raises them, remount the export, and what waits for a mount. */
class NfsBrowserTest {
    /** NFS has no session to log off: saving a folder never waits for a listing on a NAS that may be retrying. */
    @Test
    fun forgettingAFolderWaitsForNoNfsListing() {
        val mounting = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val nfs = NfsBrowser(
            mount = {
                mounting.countDown()
                answer.await()
                throw IOException("rpc request timeout on the connection: /192.168.1.20:635")
            },
            clock = { 0L },
        )
        val browsers = RemoteBrowsers(SmbBrowser(connect = { error("No SMB here") }, clock = { 0L }), nfs)
        val folder = SourceEntity(
            name = "Films", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = "192.168.1.20", share = "volume1/media",
            path = "Films", username = "", password = Secret(""),
        )
        val listing = thread { runCatching { runBlocking { nfs.list(folder) } } }
        assertTrue(mounting.await(5, TimeUnit.SECONDS))

        val saving = thread { runBlocking { browsers.forget(folder) } }
        saving.join(5_000)
        val saved = !saving.isAlive
        answer.countDown()
        listing.join(5_000)
        saving.join(5_000)
        assertTrue("saved while the listing waits for the NAS", saved)
    }

    @Test
    fun aRequestWithoutAnswerMountsAgain() {
        val rpc = RpcException(RpcStatus.NETWORK_ERROR, "the connection is broken: /192.168.1.20:2049")
        val message = "network error, server: 192.168.1.20, RPC error: ${rpc.message}"
        assertTrue(NfsBrowser.isBroken(NfsException(NfsStatus.NFS3ERR_IO, message, rpc)))
    }

    /** The NAS restarted, or the export moved: the mount's handles mean nothing to it. */
    @Test
    fun aHandleTheNasForgotMountsAgain() {
        assertTrue(NfsBrowser.isBroken(NfsException(NfsStatus.NFS3ERR_STALE, "readdir error code: NfsStatus:70")))
        assertTrue(NfsBrowser.isBroken(NfsException(NfsStatus.NFS3ERR_BADHANDLE, "readdir error code: NfsStatus:10001")))
    }

    /** lost+found refused, a file gone since the listing, a NAS disk error: the mount is still sound. */
    @Test
    fun aFileRefusedOrGoneKeepsTheMount() {
        assertFalse(NfsBrowser.isBroken(NfsException(NfsStatus.NFS3ERR_ACCES, "readdir error code: NfsStatus:13")))
        assertFalse(NfsBrowser.isBroken(FileNotFoundException("lookup /volume1/media/movie.nfo")))
        assertFalse(NfsBrowser.isBroken(NfsException(NfsStatus.NFS3ERR_IO, "read error code: NfsStatus:5")))
    }
}
