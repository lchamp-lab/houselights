package app.houselights.providers.local

import com.emc.ecs.nfsclient.mount.MountException
import com.emc.ecs.nfsclient.mount.MountStatus
import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.emc.ecs.nfsclient.rpc.RpcException
import com.emc.ecs.nfsclient.rpc.RpcStatus
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

class ScanErrorsTest {
    private fun kind(e: Throwable) = ScanErrors.classify(e).kind

    @Test
    fun unreachable() {
        assertEquals(ScanErrorKind.UNREACHABLE, kind(IOException("rpc error, server: 192.168.1.20, RPC error: waiting for connection to be established, but failed /192.168.1.20:111")))
        assertEquals(ScanErrorKind.UNREACHABLE, kind(IOException("wrapped", ConnectException("Connection refused"))))
    }

    /** As smbj 0.14 throws it when the NAS answers a request later than its timeout: Promise, Futures.get, Share.receive. */
    @Test
    fun anSmbRequestThatTimesOutIsUnreachable() {
        val expired = SMBRuntimeException(TimeoutException("Timeout expired"))
        assertEquals(ScanErrorKind.UNREACHABLE, kind(SMBRuntimeException(TransportException(ExecutionException(expired)))))
    }

    /** The packet reader fails the requests under way with what stopped it. */
    @Test
    fun anSmbConnectionDroppedIsUnreachable() {
        assertEquals(ScanErrorKind.UNREACHABLE, kind(SMBRuntimeException(TransportException(SocketException("Connection reset")))))
        assertEquals(ScanErrorKind.UNREACHABLE, kind(SMBRuntimeException(TransportException(EOFException()))))
        assertEquals(ScanErrorKind.UNREACHABLE, kind(smbStatus(NtStatus.STATUS_IO_TIMEOUT)))
        assertEquals(ScanErrorKind.UNREACHABLE, kind(smbStatus(NtStatus.STATUS_CONNECTION_DISCONNECTED)))
    }

    /** smbj raises a TransportException of its own for what the NAS asks and it cannot do: no network failure. */
    @Test
    fun anSmbProtocolFailureIsNotTheNetwork() {
        assertEquals(ScanErrorKind.OTHER, kind(TransportException("Message signing is required, but no signing key is negotiated")))
    }

    /** nfs-client 1.1: after its retries, RpcWrapper wraps its Connection's RpcException in an NfsException. */
    @Test
    fun anNfsRequestWithoutAnswerIsUnreachable() {
        listOf(
            "rpc request timeout on the connection: /192.168.1.20:2049",
            "the connection is broken: /192.168.1.20:2049",
            "tcp IO error on the connection: /192.168.1.20:2049",
        ).forEach { message ->
            val rpc = RpcException(RpcStatus.NETWORK_ERROR, message)
            val thrown = NfsException(NfsStatus.NFS3ERR_IO, "network error, server: 192.168.1.20, RPC error: $message", rpc)
            assertEquals(message, ScanErrorKind.UNREACHABLE, kind(thrown))
        }
        assertEquals(ScanErrorKind.UNREACHABLE, kind(NfsException(NfsStatus.NFS3ERR_JUKEBOX, "read error code: NfsStatus:10008")))
    }

    /** Nfs3 mounts the export when it is made: the RpcException comes inside a MountException. */
    @Test
    fun anNfsMountWithoutAnswerIsUnreachable() {
        val rpc = RpcException(RpcStatus.NETWORK_ERROR, "rpc request timeout on the connection: /192.168.1.20:635")
        val message = "network error, server: 192.168.1.20, export: /volume1/media, RPC error: ${rpc.message}"
        assertEquals(ScanErrorKind.UNREACHABLE, kind(MountException(MountStatus.MNT3ERR_IO, message, rpc)))
    }

    /** A status of the same value as NETWORK_ERROR: the device, not the NAS. */
    @Test
    fun aPortTheDeviceCannotTakeIsNotTheNasSilence() {
        val rpc = RpcException(RpcStatus.LOCAL_BINDING_ERROR, "Cannot bind a port < 1024: /192.168.1.20:635")
        val message = "rpc error, server: 192.168.1.20, export: /volume1/media, RPC error: ${rpc.message}"
        assertEquals(ScanErrorKind.OTHER, kind(MountException(MountStatus.MNT3ERR_IO, message, rpc)))
    }

    @Test
    fun smbLogin() {
        assertEquals(ScanErrorKind.LOGIN_FAILED, kind(RuntimeException("STATUS_LOGON_FAILURE (0xc000006d): Authentication failed for 'zidoo'")))
    }

    @Test
    fun accessDenied() {
        assertEquals(ScanErrorKind.ACCESS_DENIED, kind(IOException("NFS3ERR_ACCES")))
        assertEquals(ScanErrorKind.ACCESS_DENIED, kind(RuntimeException("STATUS_ACCESS_DENIED (0xc0000022)")))
    }

    /** Nfs3 refused its mount: the NAS does not export the folder to this device's address (seen on the emulator). */
    @Test
    fun anNfsMountRefusedIsAccessDenied() {
        assertEquals(ScanErrorKind.ACCESS_DENIED, kind(mountFailure(MountStatus.MNT3ERR_ACCES)))
        assertEquals(ScanErrorKind.ACCESS_DENIED, kind(mountFailure(MountStatus.MNT3ERR_PERM)))
        assertEquals(ScanErrorKind.NOT_FOUND, kind(mountFailure(MountStatus.MNT3ERR_NOENT)))
    }

    /** NfsResponseHandler names the status by its number only. */
    @Test
    fun anNfsRequestRefusedIsAccessDenied() {
        assertEquals(ScanErrorKind.ACCESS_DENIED, kind(NfsException(NfsStatus.NFS3ERR_ACCES, "readdir error code: NfsStatus:13")))
    }

    @Test
    fun notFound() {
        assertEquals(ScanErrorKind.NOT_FOUND, kind(RuntimeException("STATUS_BAD_NETWORK_NAME (0xc00000cc)")))
        assertEquals(ScanErrorKind.NOT_FOUND, kind(IOException("NFS3ERR_NOENT")))
    }

    @Test
    fun other() {
        assertEquals(ScanErrorKind.OTHER, kind(IllegalStateException("something else")))
    }

    /** As Nfs3.lookupRootHandle throws it for a status that is not MNT3_OK. */
    private fun mountFailure(status: MountStatus) = MountException(
        status,
        "mount failure, server: 192.168.1.20, export: /volume1/media, nfs version: 3, returned state: ${status.value}",
    )

    /** As Share.receive throws it for a status that is not a success. */
    private fun smbStatus(status: NtStatus) =
        SMBApiException(status.value, SMB2MessageCommandCode.SMB2_READ, "Read failed for \\\\nas\\media\\film.mkv", null)
}
