package app.houselights.providers.local

import app.houselights.core.FailureKind
import app.houselights.core.causeChain
import app.houselights.core.failureKind

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
    fun isUnreachable(e: Throwable): Boolean = e.failureKind() == FailureKind.UNREACHABLE

    /** A failure as a folder's settings tell it: the scan's own words for what it met, else what the failure comes down to. */
    fun classify(e: Throwable): ScanError {
        val chain = e.causeChain()
        val kind = when {
            chain.any { it is EmptyFolderException } -> ScanErrorKind.EMPTY
            chain.any { it is UnreadableFolderException } -> ScanErrorKind.ACCESS_DENIED
            else -> e.failureKind().scanErrorKind()
        }
        return ScanError(kind)
    }

    /**
     * What a folder's settings say of a [FailureKind]. TMDB's failures are told apart from the folders
     * ([ScanState.tmdbIssue]); the others name nothing the folder's settings would fix.
     */
    private fun FailureKind.scanErrorKind(): ScanErrorKind = when (this) {
        FailureKind.UNREACHABLE -> ScanErrorKind.UNREACHABLE
        FailureKind.LOGIN_FAILED -> ScanErrorKind.LOGIN_FAILED
        FailureKind.ACCESS_DENIED -> ScanErrorKind.ACCESS_DENIED
        FailureKind.MISSING -> ScanErrorKind.NOT_FOUND
        FailureKind.STORAGE_FULL -> ScanErrorKind.STORAGE_FULL
        else -> ScanErrorKind.OTHER
    }
}
