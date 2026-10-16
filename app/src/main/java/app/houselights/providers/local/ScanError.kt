package app.houselights.providers.local

import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.OsConstants
import com.emc.ecs.nfsclient.mount.MountException
import com.emc.ecs.nfsclient.mount.MountStatus
import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.emc.ecs.nfsclient.rpc.RpcException
import com.emc.ecs.nfsclient.rpc.RpcStatus
import java.io.EOFException
import java.io.FileNotFoundException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/** Why a folder could not be read, in terms the user can act on. */
enum class ScanErrorKind {
    UNREACHABLE,
    ACCESS_DENIED,
    LOGIN_FAILED,
    NOT_FOUND,

    /** Listed without a single video while the library holds some: nothing was removed. */
    EMPTY,

    /** The player's own storage is full: the NAS is not to blame, and every file would fail the same way. */
    STORAGE_FULL,
    OTHER,
}

/**
 * A folder listed empty while the library holds titles from it: more likely a disk not mounted
 * behind the share than a library emptied, so the scan stops before it removes anything.
 */
class EmptyFolderException : IllegalStateException("The folder lists no video, yet the library holds some")

/** A failure as the interface tells it; the exception itself goes to the log where it is caught. */
data class ScanError(val kind: ScanErrorKind)

object ScanErrors {
    /** The NAS (not TMDB) can't be reached: worth trying again later, not worth going on now. */
    fun isUnreachable(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.none { it is TmdbUnreachableException } && classify(e).kind == ScanErrorKind.UNREACHABLE

    /** Maps SMB, NFS, socket and storage failures to a [ScanErrorKind]: the first that fits, in this order. */
    fun classify(e: Throwable): ScanError {
        val chain = generateSequence(e) { it.cause }.toList()
        val text = chain.joinToString(" ") { "${it.javaClass.simpleName} ${it.message}" }
        val kind = when {
            chain.any { it is EmptyFolderException } -> ScanErrorKind.EMPTY
            storageFull(chain, text) -> ScanErrorKind.STORAGE_FULL
            noAnswer(chain, text) -> ScanErrorKind.UNREACHABLE
            text.contains("LOGON_FAILURE") || text.contains("ACCOUNT_") || text.contains("WRONG_PASSWORD") -> ScanErrorKind.LOGIN_FAILED
            refused(chain, text) -> ScanErrorKind.ACCESS_DENIED
            missing(chain, text) -> ScanErrorKind.NOT_FOUND
            else -> ScanErrorKind.OTHER
        }
        return ScanError(kind)
    }

    /** The player's storage is full: Room says so with SQLiteFullException, a file written with ENOSPC. */
    private fun storageFull(chain: List<Throwable>, text: String): Boolean =
        chain.any { it is SQLiteFullException || it is ErrnoException && it.errno == OsConstants.ENOSPC } ||
            text.contains("No space left on device", ignoreCase = true)

    /**
     * The NAS does not answer, or stopped answering. smbj tells a request answered too late by a
     * TimeoutException ("Timeout expired") inside its TransportException, a connection dropped by the
     * socket's own error; nfs-client by an RpcException of status NETWORK_ERROR ("rpc request timeout",
     * "the connection is broken", "tcp IO error") inside the NfsException or MountException of the
     * request. A TransportException of its own (signing required, SMBv1) is not the network.
     */
    private fun noAnswer(chain: List<Throwable>, text: String): Boolean =
        chain.any {
            it is UnknownHostException || it is SocketException || it is SocketTimeoutException || it is EOFException ||
                it is TimeoutException ||
                // By identity: LOCAL_BINDING_ERROR, a port the device cannot take, has the same value.
                it is RpcException && it.status === RpcStatus.NETWORK_ERROR ||
                // The NAS asks to come back later (a disk waking, a file recalled from an archive).
                it is NfsException && it.status?.value == NfsStatus.NFS3ERR_JUKEBOX.value
        } || NO_ANSWER.any { text.contains(it, ignoreCase = true) }

    /**
     * The NAS refused the device or the account. nfs-client names a status by its number only: a mount
     * refused says "returned state: 13", a request refused "NfsStatus:13".
     */
    private fun refused(chain: List<Throwable>, text: String): Boolean =
        chain.any {
            it is MountException && it.status?.value in MOUNT_REFUSED || it is NfsException && it.status?.value in NFS_REFUSED
        } || REFUSED.any { text.contains(it) } || text.contains("AUTH_ERROR", ignoreCase = true) ||
            text.contains("auth", ignoreCase = true) && text.contains("rpc", ignoreCase = true)

    /** The share, the export or the folder is not there; nfs-client throws FileNotFoundException for NFS3ERR_NOENT. */
    private fun missing(chain: List<Throwable>, text: String): Boolean =
        chain.any { it is FileNotFoundException || it is MountException && it.status?.value == MountStatus.MNT3ERR_NOENT.value } ||
            MISSING.any { text.contains(it) }

    private val MOUNT_REFUSED = setOf(MountStatus.MNT3ERR_ACCES.value, MountStatus.MNT3ERR_PERM.value)
    private val NFS_REFUSED = setOf(NfsStatus.NFS3ERR_ACCES.value, NfsStatus.NFS3ERR_PERM.value)
    private val REFUSED = listOf("ACCESS_DENIED", "ERR_ACCES", "ERR_PERM")
    private val MISSING = listOf("BAD_NETWORK_NAME", "NOT_FOUND", "ERR_NOENT", "PATH_NOT_FOUND")

    private val NO_ANSWER = listOf(
        "waiting for connection", "timed out", "rpc request timeout", "connection is broken", "tcp IO error",
        "STATUS_IO_TIMEOUT", "STATUS_CONNECTION_DISCONNECTED", "STATUS_CONNECTION_RESET",
    )
}
