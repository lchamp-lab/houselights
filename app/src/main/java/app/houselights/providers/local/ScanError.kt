package app.houselights.providers.local

import app.houselights.core.FailureKind
import app.houselights.core.causeChain
import app.houselights.core.failureKind

/** Why a source could not be scanned, in terms the user can act on. */
enum class ScanErrorKind {
    UNREACHABLE,
    ACCESS_DENIED,
    LOGIN_FAILED,
    NOT_FOUND,

    /** No video listed while the library has some from it; nothing was removed. */
    EMPTY,

    /** Device full: the database or artwork could not be written, and the pass stops. */
    STORAGE_FULL,
    OTHER,
}

/** An empty listing for a source with titles, likely an unmounted disk: nothing is removed. */
class EmptyFolderException : IllegalStateException("The folder lists no video, yet the library holds some")

/** A failure as shown in the UI; the exception is logged where caught. */
data class ScanError(val kind: ScanErrorKind)

object ScanErrors {
    /** The NAS (not TMDB) is unreachable: retry later, stop now. */
    fun isUnreachable(e: Throwable): Boolean = e.failureKind() == FailureKind.UNREACHABLE

    /** Maps a failure to the error shown in the source's settings. */
    fun classify(e: Throwable): ScanError {
        val chain = e.causeChain()
        val kind = when {
            chain.any { it is EmptyFolderException } -> ScanErrorKind.EMPTY
            chain.any { it is UnreadableFolderException } -> ScanErrorKind.ACCESS_DENIED
            else -> e.failureKind().scanErrorKind()
        }
        return ScanError(kind)
    }

    /** TMDB failures are reported separately ([ScanState.tmdbIssue]); other kinds map to OTHER. */
    private fun FailureKind.scanErrorKind(): ScanErrorKind = when (this) {
        FailureKind.UNREACHABLE -> ScanErrorKind.UNREACHABLE
        FailureKind.LOGIN_FAILED -> ScanErrorKind.LOGIN_FAILED
        FailureKind.ACCESS_DENIED -> ScanErrorKind.ACCESS_DENIED
        FailureKind.MISSING -> ScanErrorKind.NOT_FOUND
        FailureKind.STORAGE_FULL -> ScanErrorKind.STORAGE_FULL
        else -> ScanErrorKind.OTHER
    }
}
