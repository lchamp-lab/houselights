package app.houselights.providers.local

import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.emc.ecs.nfsclient.rpc.RpcException
import com.emc.ecs.nfsclient.rpc.RpcStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

/** Which NFS failures make the mount again, as nfs-client 1.1 raises them. */
class NfsBrowserTest {
    @Test
    fun aRequestWithoutAnswerMountsAgain() {
        val rpc = RpcException(RpcStatus.NETWORK_ERROR, "the connection is broken: /192.168.1.20:2049")
        val message = "network error, server: 192.168.1.20, RPC error: ${rpc.message}"
        assertTrue(NfsBrowser.isBroken(NfsException(NfsStatus.NFS3ERR_IO, message, rpc)))
    }

    /** The NAS restarted, or the export moved: the handles of the mount mean nothing to it. */
    @Test
    fun aHandleTheNasForgotMountsAgain() {
        assertTrue(NfsBrowser.isBroken(NfsException(NfsStatus.NFS3ERR_STALE, "readdir error code: NfsStatus:70")))
        assertTrue(NfsBrowser.isBroken(NfsException(NfsStatus.NFS3ERR_BADHANDLE, "readdir error code: NfsStatus:10001")))
    }

    /** lost+found refused, a file gone since the listing, a disk error of the NAS: the mount is sound. */
    @Test
    fun aFileRefusedOrGoneKeepsTheMount() {
        assertFalse(NfsBrowser.isBroken(NfsException(NfsStatus.NFS3ERR_ACCES, "readdir error code: NfsStatus:13")))
        assertFalse(NfsBrowser.isBroken(FileNotFoundException("lookup /volume1/media/movie.nfo")))
        assertFalse(NfsBrowser.isBroken(NfsException(NfsStatus.NFS3ERR_IO, "read error code: NfsStatus:5")))
    }
}
