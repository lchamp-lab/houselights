package app.houselights.providers.local

import android.system.ErrnoException
import android.system.OsConstants
import app.houselights.core.DiskAbsentException
import app.houselights.testing.mountFailure
import app.houselights.testing.smbStatus
import com.emc.ecs.nfsclient.mount.MountException
import com.emc.ecs.nfsclient.mount.MountStatus
import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.emc.ecs.nfsclient.rpc.RpcException
import com.emc.ecs.nfsclient.rpc.RpcStatus
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb.SMB1NotSupportedException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.nio.file.NoSuchFileException

/**
 * What a folder's scan reports for smbj's and nfs-client's own exceptions and messages, and for storage errors told by
 * errno alone, beyond the failures every screen reads alike (FailuresTest). On Robolectric for Android's exceptions.
 */
@RunWith(RobolectricTestRunner::class)
class ScanErrorsTest {
    private fun kind(e: Throwable) = ScanErrors.classify(e).kind

    /** As nfs-client words an RPC connection it could not open, the portmapper's here. */
    @Test
    fun anNfsConnectionThatCannotBeOpenedIsUnreachable() {
        val message = "rpc error, server: 192.168.1.20, RPC error: waiting for connection to be established, but failed /192.168.1.20:111"
        assertEquals(ScanErrorKind.UNREACHABLE, kind(IOException(message)))
    }

    /** The packet reader fails the pending requests with what stopped it. */
    @Test
    fun anSmbConnectionDroppedIsUnreachable() {
        assertEquals(ScanErrorKind.UNREACHABLE, kind(SMBRuntimeException(TransportException(SocketException("Connection reset")))))
        assertEquals(ScanErrorKind.UNREACHABLE, kind(SMBRuntimeException(TransportException(EOFException()))))
        assertEquals(ScanErrorKind.UNREACHABLE, kind(smbStatus(NtStatus.STATUS_IO_TIMEOUT)))
        assertEquals(ScanErrorKind.UNREACHABLE, kind(smbStatus(NtStatus.STATUS_CONNECTION_DISCONNECTED)))
    }

    /** smbj throws its own TransportException for a NAS request it cannot fulfil: not a network failure. */
    @Test
    fun anSmbProtocolFailureIsNotTheNetwork() {
        assertEquals(ScanErrorKind.OTHER, kind(SMB1NotSupportedException()))
    }

    /** A NAS requiring signing (Windows 11 24H2, recent Synology) refuses guests, at setup or first send. */
    @Test
    fun aGuestRefusedForSigningIsTheSignIn() {
        assertEquals(ScanErrorKind.LOGIN_FAILED, kind(SMB2GuestSigningRequiredException()))
        val unsigned = TransportException("Message signing is required, but no signing key is negotiated")
        assertEquals(ScanErrorKind.LOGIN_FAILED, kind(SMBRuntimeException(unsigned)))
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

    /** Nfs3 mounts the export when it is created: the RpcException comes inside a MountException. */
    @Test
    fun anNfsMountWithoutAnswerIsUnreachable() {
        val rpc = RpcException(RpcStatus.NETWORK_ERROR, "rpc request timeout on the connection: /192.168.1.20:635")
        val message = "network error, server: 192.168.1.20, export: /volume1/media, RPC error: ${rpc.message}"
        assertEquals(ScanErrorKind.UNREACHABLE, kind(MountException(MountStatus.MNT3ERR_IO, message, rpc)))
    }

    /** A status with the same value as NETWORK_ERROR: the device's fault, not the NAS's silence. */
    @Test
    fun aPortTheDeviceCannotTakeIsNotTheNasSilence() {
        val rpc = RpcException(RpcStatus.LOCAL_BINDING_ERROR, "Cannot bind a port < 1024: /192.168.1.20:635")
        val message = "rpc error, server: 192.168.1.20, export: /volume1/media, RPC error: ${rpc.message}"
        assertEquals(ScanErrorKind.OTHER, kind(MountException(MountStatus.MNT3ERR_IO, message, rpc)))
    }

    /** No space left on the device, told by the errno alone; another write error is not a full device. */
    @Test
    fun aFullPlayerIsNotTheNas() {
        assertEquals(ScanErrorKind.STORAGE_FULL, kind(IOException(ErrnoException("write", OsConstants.ENOSPC))))
        assertEquals(ScanErrorKind.OTHER, kind(IOException(ErrnoException("write", OsConstants.EIO))))
    }

    /** As smbj and nfs-client name a refusal in their messages. */
    @Test
    fun aRefusalNamedInTheMessageIsAccessDenied() {
        assertEquals(ScanErrorKind.ACCESS_DENIED, kind(IOException("NFS3ERR_ACCES")))
        assertEquals(ScanErrorKind.ACCESS_DENIED, kind(RuntimeException("STATUS_ACCESS_DENIED (0xc0000022)")))
    }

    /** Nfs3's mount refused: the NAS does not export the folder to this device (seen on the emulator). */
    @Test
    fun anNfsMountRefusedIsAccessDenied() {
        assertEquals(ScanErrorKind.ACCESS_DENIED, kind(mountFailure(MountStatus.MNT3ERR_PERM)))
        assertEquals(ScanErrorKind.NOT_FOUND, kind(mountFailure(MountStatus.MNT3ERR_NOENT)))
    }

    /** NfsResponseHandler names the status by its number only. */
    @Test
    fun anNfsRequestRefusedIsAccessDenied() {
        assertEquals(ScanErrorKind.ACCESS_DENIED, kind(NfsException(NfsStatus.NFS3ERR_ACCES, "readdir error code: NfsStatus:13")))
    }

    /** A share or export path the NAS does not have, as smbj and nfs-client name it. */
    @Test
    fun aShareOrPathNamedMissingInTheMessageIsNotFound() {
        assertEquals(ScanErrorKind.NOT_FOUND, kind(RuntimeException("STATUS_BAD_NETWORK_NAME (0xc00000cc)")))
        assertEquals(ScanErrorKind.NOT_FOUND, kind(IOException("NFS3ERR_NOENT")))
    }

    /**
     * A folder gone while its NAS answers, the one failure that holds a folder's titles: not found inside a share or an
     * export reached, over NFS or SMB. Not a share or an export missing, a folder refused, an account or a guest refused,
     * a NAS out of reach, nor any failure of the player's own disks.
     */
    @Test
    fun onlyAFolderGoneFromANasThatAnswersHoldsItsTitles() {
        val gone = FileNotFoundException("/volume1/media/houselights-test/films")
        assertTrue(ScanErrors.isFolderGone(Protocol.NFS, gone))
        assertTrue(ScanErrors.isFolderGone(Protocol.SMB, smbStatus(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND)))
        assertTrue(ScanErrors.isFolderGone(Protocol.SMB, smbStatus(NtStatus.STATUS_OBJECT_PATH_NOT_FOUND)))
        assertFalse("a disk's folder", ScanErrors.isFolderGone(Protocol.LOCAL, gone))
        assertFalse(ScanErrors.isFolderGone(Protocol.LOCAL, NoSuchFileException("/storage/1234-ABCD/Films")))
        assertFalse(ScanErrors.isFolderGone(Protocol.LOCAL, DiskAbsentException("1234-ABCD")))
        listOf(
            ConnectException("Connection refused"),
            NfsException(NfsStatus.NFS3ERR_JUKEBOX, "lookup error code: NfsStatus:10008"),
            mountFailure(MountStatus.MNT3ERR_NOENT),
            mountFailure(MountStatus.MNT3ERR_ACCES),
            smbStatus(NtStatus.STATUS_BAD_NETWORK_NAME),
            smbStatus(NtStatus.STATUS_ACCESS_DENIED),
            smbStatus(NtStatus.STATUS_LOGON_FAILURE),
            SMB2GuestSigningRequiredException(),
            NfsException(NfsStatus.NFS3ERR_ACCES, "readdirplus error code: NfsStatus:13"),
            SocketException("Connection reset"),
            IOException("rpc error, server: 192.168.1.20, RPC error: rpc request timeout"),
        ).forEach { e ->
            assertFalse("$e", ScanErrors.isFolderGone(Protocol.NFS, e))
            assertFalse("$e", ScanErrors.isFolderGone(Protocol.SMB, e))
        }
    }
}
