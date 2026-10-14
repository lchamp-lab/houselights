package app.houselights.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform
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

/** At most one value per [periodMs]: the first one at once, then the latest of each period. */
fun <T> Flow<T>.throttleLatest(periodMs: Long): Flow<T> = conflate().transform {
    emit(it)
    delay(periodMs)
}
