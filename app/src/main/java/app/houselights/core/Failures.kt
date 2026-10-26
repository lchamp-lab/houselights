package app.houselights.core

import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.OsConstants
import com.emc.ecs.nfsclient.mount.MountException
import com.emc.ecs.nfsclient.mount.MountStatus
import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.emc.ecs.nfsclient.rpc.RpcException
import com.emc.ecs.nfsclient.rpc.RpcStatus
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * The root cause of a failure, classified once from its cause chain ([failureKind]) and worded by each caller
 * (UiError, ScanErrorKind, Jellyfin sign-in): the only place that inspects every layer's exceptions.
 */
enum class FailureKind {
    /** The NAS or the server does not answer, or stopped answering. */
    UNREACHABLE,

    /** A bare [IOException]: the network on a screen, the device's storage on a local write. */
    IO,

    /** TLS refused the server's certificate: expired, self-signed, or made out to another name. */
    CERTIFICATE,

    /** The server refused the account or its token (HTTP 401 or 403): signed out, or never signed in. */
    REFUSED,

    /** Not in the library ([NotFoundException]), or no such resource on the server (HTTP 404). */
    GONE,

    /** The server answered with another HTTP error status. */
    SERVER_FAILED,

    /** An answer that could not be parsed into the expected shape. */
    UNEXPECTED_ANSWER,

    /** The NAS refused the account, or guest access. */
    LOGIN_FAILED,

    /** The NAS denied the device or the account the requested access. */
    ACCESS_DENIED,

    /** The share, the export or the file is not on the NAS. */
    MISSING,

    NO_TMDB_KEY,

    TMDB_KEY_REFUSED,

    TMDB_UNREACHABLE,

    /** TMDB has no entry for the requested id ([TmdbNotFoundException]); distinct from a library miss ([GONE]). */
    TMDB_NOT_FOUND,

    /** The device's Keystore could not seal a secret. */
    KEYSTORE,

    /** The device's Keystore did not open a saved secret for now: it is kept, and opened again at the next try. */
    SECRET_UNREAD,

    STORAGE_FULL,

    /** The device's storage (its disk) failed; on a write, nothing was saved. */
    STORAGE_FAILED,

    OTHER,
}

/**
 * Classifies this failure from its cause chain, first match wins: the app's own exceptions and storage, then
 * an HTTP status or TLS, then an unparseable answer, then network and NAS; a bare [IOException] comes last.
 */
fun Throwable.failureKind(): FailureKind {
    val chain = causeChain()
    // An unparseable answer is checked before the NAS's message words: its body excerpt may contain them.
    val unreadable = FailureKind.UNEXPECTED_ANSWER.takeIf { chain.any(::unreadable) }
    return typed(chain) ?: answered(chain) ?: unreadable ?: nas(chain) ?: when {
        chain.any { it is IOException } -> FailureKind.IO
        else -> FailureKind.OTHER
    }
}

/**
 * Whether this failure is an NFS server refusing the mount itself (MNT3ERR_ACCES or MNT3ERR_PERM: the export is not
 * open to this player), rather than a read the mount allowed (NFS3ERR_ACCES: the user the app presents, root squashed,
 * may not read the folder); both are [FailureKind.ACCESS_DENIED], each with its own remedy.
 */
fun Throwable.isMountRefused(): Boolean = causeChain().any { it is MountException && it.status?.value in MOUNT_REFUSED }

/** An answer that could not be read into what was expected: a server's JSON, or a NAS's bytes. */
private fun unreadable(e: Throwable) = e is SerializationException || e is MalformedAnswerException

/** This failure and its causes, outermost first; a cyclic chain stops at the first repeat. */
internal fun Throwable.causeChain(): List<Throwable> {
    val chain = mutableListOf<Throwable>()
    var next: Throwable? = this
    while (next != null && chain.none { it === next }) {
        chain += next
        next = next.cause
    }
    return chain
}

/** Classifies the app's own exceptions and storage errors by type. */
private fun typed(chain: List<Throwable>): FailureKind? = when {
    chain.any { it is SecretsUnavailableException } -> FailureKind.KEYSTORE
    chain.any { it is SecretUnreadException } -> FailureKind.SECRET_UNREAD
    chain.any { it is NoTmdbKeyException } -> FailureKind.NO_TMDB_KEY
    chain.any { it is TmdbKeyRefusedException } -> FailureKind.TMDB_KEY_REFUSED
    // Before TMDB out of reach, which it extends so a scan counts it among TMDB's failures.
    chain.any { it is TmdbCertificateException } -> FailureKind.CERTIFICATE
    chain.any { it is TmdbUnreachableException } -> FailureKind.TMDB_UNREACHABLE
    chain.any { it is TmdbNotFoundException } -> FailureKind.TMDB_NOT_FOUND
    chain.any { it is NotFoundException } -> FailureKind.GONE
    // Before the files it fails on, which may be missing or refused once the disk is gone.
    chain.any { it is DiskAbsentException } -> FailureKind.UNREACHABLE
    storageFull(chain) -> FailureKind.STORAGE_FULL
    chain.any { it is SQLiteDiskIOException } -> FailureKind.STORAGE_FAILED
    else -> null
}

/** The device is out of space: Room reports SQLiteFullException, a file write ENOSPC. */
private fun storageFull(chain: List<Throwable>): Boolean =
    chain.any { it is SQLiteFullException || it is ErrnoException && it.errno == OsConstants.ENOSPC } ||
        chain.any { it.message?.contains("No space left on device", ignoreCase = true) == true }

/** Classifies a server's HTTP status, or TLS refusing its certificate. */
private fun answered(chain: List<Throwable>): FailureKind? {
    val status = chain.firstNotNullOfOrNull { (it as? HttpException)?.code() }
    return when {
        status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN -> FailureKind.REFUSED
        status == HttpStatus.NOT_FOUND -> FailureKind.GONE
        status != null -> FailureKind.SERVER_FAILED
        chain.any { it is CertificateException || it is CertPathValidatorException || it is SSLPeerUnverifiedException } ->
            FailureKind.CERTIFICATE
        else -> null
    }
}

/** Network and NAS failures by exception type, else by message: nfs-client gives statuses by number, smbj by name. */
private fun nas(chain: List<Throwable>): FailureKind? {
    val text = chain.joinToString(" ") { "${it.javaClass.simpleName} ${it.message}" }
    return when {
        noAnswer(chain, text) -> FailureKind.UNREACHABLE
        signInRefused(chain, text) -> FailureKind.LOGIN_FAILED
        refused(chain, text) -> FailureKind.ACCESS_DENIED
        missing(chain, text) -> FailureKind.MISSING
        else -> null
    }
}

/**
 * The NAS does not answer. smbj wraps a late answer as a TimeoutException in its TransportException;
 * nfs-client reports an RpcException with status NETWORK_ERROR inside the request's NfsException or
 * MountException. A TransportException alone (signing required, SMBv1) is not a network failure.
 */
private fun noAnswer(chain: List<Throwable>, text: String): Boolean =
    chain.any {
        it is UnknownHostException || it is SocketException || it is SocketTimeoutException || it is EOFException ||
            it is TimeoutException ||
            // Compared by identity: LOCAL_BINDING_ERROR (a port the device cannot bind) has the same value.
            it is RpcException && it.status === RpcStatus.NETWORK_ERROR ||
            // The NAS asks to retry later (a disk spinning up, a file recalled from an archive).
            it is NfsException && it.status?.value == NfsStatus.NFS3ERR_JUKEBOX.value
    } || NO_ANSWER.any { text.contains(it, ignoreCase = true) }

/**
 * The NAS refused the account, or a guest: smbj 0.14 cannot sign as a guest (no key) where signing is required
 * (Windows 11 24H2, recent Synology), and throws its own exception or a TransportException at the first signing.
 */
private fun signInRefused(chain: List<Throwable>, text: String): Boolean =
    text.contains("LOGON_FAILURE") || text.contains("ACCOUNT_") || text.contains("WRONG_PASSWORD") ||
        chain.any { it is SMB2GuestSigningRequiredException || it is TransportException && it.message == NO_SIGNING_KEY }

/**
 * The NAS refused the device or account; nfs-client says "returned state: 13" for a mount, else "NfsStatus:13". On the
 * player's own disks, Android refused the app (no storage permission).
 */
private fun refused(chain: List<Throwable>, text: String): Boolean =
    chain.any {
        it is MountException && it.status?.value in MOUNT_REFUSED || it is NfsException && it.status?.value in NFS_REFUSED ||
            it is AccessDeniedException
    } || REFUSED.any { text.contains(it) } || text.contains("AUTH_ERROR", ignoreCase = true) ||
        text.contains("auth", ignoreCase = true) && text.contains("rpc", ignoreCase = true)

/**
 * The share, export or folder does not exist; nfs-client throws FileNotFoundException for NFS3ERR_NOENT, a file of the
 * player's own disks NoSuchFileException.
 */
private fun missing(chain: List<Throwable>, text: String): Boolean =
    chain.any {
        it is FileNotFoundException || it is NoSuchFileException ||
            it is MountException && it.status?.value == MountStatus.MNT3ERR_NOENT.value
    } || MISSING.any { text.contains(it) }


/** smbj Session's message when it must sign a request without a key, as in a guest session. */
private const val NO_SIGNING_KEY = "Message signing is required, but no signing key is negotiated"

private val MOUNT_REFUSED = setOf(MountStatus.MNT3ERR_ACCES.value, MountStatus.MNT3ERR_PERM.value)
private val NFS_REFUSED = setOf(NfsStatus.NFS3ERR_ACCES.value, NfsStatus.NFS3ERR_PERM.value)
private val REFUSED = listOf("ACCESS_DENIED", "ERR_ACCES", "ERR_PERM")
private val MISSING = listOf("BAD_NETWORK_NAME", "NOT_FOUND", "ERR_NOENT", "PATH_NOT_FOUND")

private val NO_ANSWER = listOf(
    "waiting for connection", "timed out", "rpc request timeout", "connection is broken", "tcp IO error",
    "STATUS_IO_TIMEOUT", "STATUS_CONNECTION_DISCONNECTED", "STATUS_CONNECTION_RESET",
)
