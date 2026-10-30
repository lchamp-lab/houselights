package app.houselights.providers.local

import app.houselights.core.FailureKind
import app.houselights.core.FailurePlace
import app.houselights.core.causeChain
import app.houselights.core.failureKind
import app.houselights.core.failurePlace

/** Why a source could not be scanned, in terms the user can act on. */
enum class ScanErrorKind {
    UNREACHABLE,
    ACCESS_DENIED,
    LOGIN_FAILED,

    /**
     * The folder's saved password, which the device's Keystore did not open for now: kept, never tried empty, the NAS not
     * asked; it is read again at the next scan.
     */
    PASSWORD_UNREAD,
    NOT_FOUND,

    /** No video listed while the library has some from it; nothing was removed. */
    EMPTY,

    /** Device full: the database or artwork could not be written, and the pass stops. */
    STORAGE_FULL,
    OTHER,
}

/** An empty listing for a source with titles, likely an unmounted disk: nothing is removed. */
class EmptyFolderException : IllegalStateException("The folder lists no video, yet the library holds some")

/**
 * A failure as shown in the UI; the exception is logged where caught. A folder missing or refused says [place], where
 * on the NAS, when the NAS's answer told it: the share or export itself, or a folder inside one that answered.
 */
data class ScanError(val kind: ScanErrorKind, val place: FailurePlace? = null)

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
        val placed = kind == ScanErrorKind.NOT_FOUND || kind == ScanErrorKind.ACCESS_DENIED
        return ScanError(kind, e.failurePlace().takeIf { placed })
    }

    /**
     * Whether [e] says a folder of [protocol] is gone while its NAS answers: not found inside a share or an export that
     * answered ([FailurePlace.FOLDER]), as a folder deleted or moved on the NAS is; its titles are then held as a sudden
     * drop holds them ([MissingFiles.folderGone]). Not a NAS out of reach, an account or a guest refused, a folder
     * refused, nor a share or an export missing ([FailurePlace.SHARE]): none says the files left, and a NAS waking up
     * may answer so. Nor a folder of the player's own disks ([Protocol.LOCAL]), whose failures say no place: a disk not
     * there, or not readable yet, is out of reach as a NAS asleep ([LocalBrowser]). Over NFS, a folder not found is one
     * the NAS said it does not have ([lookedUp]).
     */
    fun isFolderGone(protocol: Protocol, e: Throwable): Boolean = isFolderGone(protocol, classify(e))

    /** Whether [error], a scan's of a folder of [protocol], says the folder is gone while its NAS answers. */
    fun isFolderGone(protocol: Protocol, error: ScanError): Boolean =
        protocol != Protocol.LOCAL && error.kind == ScanErrorKind.NOT_FOUND && error.place == FailurePlace.FOLDER

    /** TMDB failures are reported separately ([ScanState.tmdbIssue]); other kinds map to OTHER. */
    private fun FailureKind.scanErrorKind(): ScanErrorKind = when (this) {
        FailureKind.UNREACHABLE -> ScanErrorKind.UNREACHABLE
        FailureKind.LOGIN_FAILED -> ScanErrorKind.LOGIN_FAILED
        FailureKind.SECRET_UNREAD -> ScanErrorKind.PASSWORD_UNREAD
        FailureKind.ACCESS_DENIED -> ScanErrorKind.ACCESS_DENIED
        FailureKind.MISSING -> ScanErrorKind.NOT_FOUND
        FailureKind.STORAGE_FULL -> ScanErrorKind.STORAGE_FULL
        else -> ScanErrorKind.OTHER
    }
}
