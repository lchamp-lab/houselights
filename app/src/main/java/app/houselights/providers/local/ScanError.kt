package app.houselights.providers.local

import android.database.sqlite.SQLiteFullException
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Why a folder could not be read, in terms the user can act on. */
enum class ScanErrorKind {
    UNREACHABLE,
    ACCESS_DENIED,
    LOGIN_FAILED,
    NOT_FOUND,

    /** Listed without a single video while the library holds some: nothing was removed. */
    EMPTY,

    /** The device has no space left: the database or the artwork could not be written. The pass stops. */
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

    /** Maps SMB, NFS and socket failures to a [ScanErrorKind]. */
    fun classify(e: Throwable): ScanError {
        val chain = generateSequence(e) { it.cause }.toList()
        val text = chain.joinToString(" ") { "${it.javaClass.simpleName} ${it.message}" }
        val kind = when {
            chain.any { it is EmptyFolderException } -> ScanErrorKind.EMPTY
            chain.any { it is SQLiteFullException } || text.contains("ENOSPC") ||
                text.contains("No space left on device", ignoreCase = true) -> ScanErrorKind.STORAGE_FULL
            chain.any { it is UnknownHostException || it is NoRouteToHostException || it is ConnectException || it is SocketTimeoutException } ||
                text.contains("waiting for connection", ignoreCase = true) ||
                text.contains("timed out", ignoreCase = true) -> ScanErrorKind.UNREACHABLE
            text.contains("LOGON_FAILURE") || text.contains("ACCOUNT_") || text.contains("WRONG_PASSWORD") -> ScanErrorKind.LOGIN_FAILED
            text.contains("ACCESS_DENIED") || text.contains("ERR_ACCES") || text.contains("ERR_PERM") ||
                text.contains("AUTH_ERROR", ignoreCase = true) || text.contains("auth", ignoreCase = true) && text.contains("rpc", ignoreCase = true) ->
                ScanErrorKind.ACCESS_DENIED
            chain.any { it is FileNotFoundException } || text.contains("BAD_NETWORK_NAME") || text.contains("NOT_FOUND") ||
                text.contains("ERR_NOENT") || text.contains("PATH_NOT_FOUND") -> ScanErrorKind.NOT_FOUND
            else -> ScanErrorKind.OTHER
        }
        return ScanError(kind)
    }
}
