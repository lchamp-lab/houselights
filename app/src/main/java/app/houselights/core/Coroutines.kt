package app.houselights.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for suspending code: a cancellation is not a failure. It is rethrown, so a load
 * replaced by a newer one stops quietly instead of reporting "Job was cancelled".
 */
@Suppress("TooGenericExceptionCaught") // Catching everything is the point, cancellation aside.
inline fun <T> suspendRunCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
