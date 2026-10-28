package app.houselights.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform
import kotlin.coroutines.cancellation.CancellationException

/** Catches failures but rethrows cancellation, so a superseded load stops quietly, not with "Job was cancelled". */
@Suppress("TooGenericExceptionCaught") // Catching everything is the point, cancellation aside.
inline fun <T> suspendRunCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

/** At most one value per [periodMs]: the first one at once, then the latest of each period. */
fun <T> Flow<T>.throttleLatest(periodMs: Long): Flow<T> = throttleLatest { periodMs }

/** At most one value per period: the first one at once, then the latest of each period, read from [periodMs] as each goes. */
fun <T> Flow<T>.throttleLatest(periodMs: () -> Long): Flow<T> = conflate().transform {
    emit(it)
    delay(periodMs())
}
