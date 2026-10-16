package app.houselights.core

import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.OsConstants
import app.houselights.providers.local.TmdbKeyRefusedException
import app.houselights.providers.local.TmdbUnreachableException
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
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * What a failure comes down to, whoever met it: read once from the exception and its causes
 * ([failureKind]), then told in each place's own words (a screen's UiError, a folder's
 * ScanErrorKind, signing in to Jellyfin). The one place that reads every layer's exceptions: the
 * app's, the NAS's (smbj, nfs-client), HTTP's, TLS's and the device's storage.
 */
enum class FailureKind {
    /** The NAS or the server does not answer, or stopped answering. */
    UNREACHABLE,

    /** A read or a write failed ([IOException]), and nothing says more: the network on a screen, the storage in a write to the device. */
    IO,

    /** TLS refused the server's certificate: expired, self-signed, or made out to another name. */
    CERTIFICATE,

    /** The server refused the account or its token (HTTP 401 or 403): signed out, or never signed in. */
    REFUSED,

    /** What was asked for is not in the library ([NotFoundException]), or the server has no such thing (HTTP 404). */
    GONE,

    /** The server answered with a failure of its own: another HTTP status. */
    SERVER_FAILED,

    /** An answer that could not be read: not the shape asked for. */
    UNEXPECTED_ANSWER,

    /** The NAS refused the account, or a guest. */
    LOGIN_FAILED,

    /** The NAS refused the device or the account what was asked. */
    ACCESS_DENIED,

    /** The share, the export or the file is not on the NAS. */
    MISSING,

    /** Searching TMDB or listing its images needs a key, and there is none. */
    NO_TMDB_KEY,

    /** TMDB refuses the key in use. */
    TMDB_KEY_REFUSED,

    /** TMDB does not answer, or failed. */
    TMDB_UNREACHABLE,

    /** The device's Keystore could not seal a secret. */
    KEYSTORE,

    /** The device has no space left. */
    STORAGE_FULL,

    /** The device's storage failed (its disk): on a write, nothing was saved. */
    STORAGE_FAILED,

    OTHER,
}

/**
 * What this failure comes down to, from it and its causes, the first that fits: the app's own
 * exceptions, wherever they lie in the chain, and the device's storage; then what a server or TLS
 * answered; then an answer that does not read; then the network and the NAS; an [IOException] that
 * says nothing more comes last.
 */
fun Throwable.failureKind(): FailureKind {
    val chain = causeChain()
    // An answer that does not read is told before the NAS's words: its excerpt of the body may hold them.
    val unreadable = FailureKind.UNEXPECTED_ANSWER.takeIf { chain.any { it is SerializationException } }
    return typed(chain) ?: answered(chain) ?: unreadable ?: nas(chain) ?: when {
        chain.any { it is IOException } -> FailureKind.IO
        else -> FailureKind.OTHER
    }
}

/** This failure and its causes, outermost first; a chain that loops ends where it does. */
internal fun Throwable.causeChain(): List<Throwable> {
    val chain = mutableListOf<Throwable>()
    var next: Throwable? = this
    while (next != null && chain.none { it === next }) {
        chain += next
        next = next.cause
    }
    return chain
}

/** What the app's exceptions and the device's storage say, by their type. */
private fun typed(chain: List<Throwable>): FailureKind? = when {
    chain.any { it is SecretsUnavailableException } -> FailureKind.KEYSTORE
    chain.any { it is NoTmdbKeyException } -> FailureKind.NO_TMDB_KEY
    chain.any { it is TmdbKeyRefusedException } -> FailureKind.TMDB_KEY_REFUSED
    chain.any { it is TmdbUnreachableException } -> FailureKind.TMDB_UNREACHABLE
    chain.any { it is NotFoundException } -> FailureKind.GONE
    storageFull(chain) -> FailureKind.STORAGE_FULL
    chain.any { it is SQLiteDiskIOException } -> FailureKind.STORAGE_FAILED
    else -> null
}

/** The device is out of space: Room says so with SQLiteFullException, a file written with ENOSPC. */
private fun storageFull(chain: List<Throwable>): Boolean =
    chain.any { it is SQLiteFullException || it is ErrnoException && it.errno == OsConstants.ENOSPC } ||
        chain.any { it.message?.contains("No space left on device", ignoreCase = true) == true }

/** What a server answered (its HTTP status), or TLS refusing its certificate. */
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

/**
 * What the network or the NAS said, by the types of SMB, NFS and sockets, else by their words:
 * nfs-client names a status by its number only, smbj by its name in a message.
 */
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
 * The NAS refused the account, or a guest: smbj 0.14 does not sign in as a guest to a NAS that
 * requires signing (Windows 11 24H2, recent Synology), as a guest has no key to sign with, and says
 * so with an exception of its own, or a TransportException once the session asks to sign. Either
 * way an account is to be entered.
 */
private fun signInRefused(chain: List<Throwable>, text: String): Boolean =
    text.contains("LOGON_FAILURE") || text.contains("ACCOUNT_") || text.contains("WRONG_PASSWORD") ||
        chain.any { it is SMB2GuestSigningRequiredException || it is TransportException && it.message == NO_SIGNING_KEY }

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


/** What smbj's Session says when it is to sign a request without a key: signed in as a guest. */
private const val NO_SIGNING_KEY = "Message signing is required, but no signing key is negotiated"

private val MOUNT_REFUSED = setOf(MountStatus.MNT3ERR_ACCES.value, MountStatus.MNT3ERR_PERM.value)
private val NFS_REFUSED = setOf(NfsStatus.NFS3ERR_ACCES.value, NfsStatus.NFS3ERR_PERM.value)
private val REFUSED = listOf("ACCESS_DENIED", "ERR_ACCES", "ERR_PERM")
private val MISSING = listOf("BAD_NETWORK_NAME", "NOT_FOUND", "ERR_NOENT", "PATH_NOT_FOUND")

private val NO_ANSWER = listOf(
    "waiting for connection", "timed out", "rpc request timeout", "connection is broken", "tcp IO error",
    "STATUS_IO_TIMEOUT", "STATUS_CONNECTION_DISCONNECTED", "STATUS_CONNECTION_RESET",
)
