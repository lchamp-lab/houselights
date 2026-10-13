package app.houselights.providers.local

import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Why a folder could not be read, in terms the user can act on. */
enum class ScanErrorKind { UNREACHABLE, ACCESS_DENIED, LOGIN_FAILED, NOT_FOUND, OTHER }

data class ScanError(val kind: ScanErrorKind, val detail: String)

object ScanErrors {
    /** Maps SMB, NFS and socket failures to a [ScanErrorKind]; the raw message stays in [ScanError.detail]. */
    fun classify(e: Throwable): ScanError {
        val chain = generateSequence(e) { it.cause }.toList()
        val text = chain.joinToString(" ") { "${it.javaClass.simpleName} ${it.message}" }
        val kind = when {
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
        return ScanError(kind, e.message ?: e.javaClass.simpleName)
    }
}
