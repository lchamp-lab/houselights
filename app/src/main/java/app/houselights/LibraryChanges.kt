package app.houselights

import app.houselights.core.throttleLatest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The library version screens observe ([app.houselights.core.LibraryEvents.libraryVersion]), moved on by
 * [sources] and in-app edits ([userChanged]) at most once every [periodMs]; an isolated change shows at once.
 */
class LibraryChanges(scope: CoroutineScope, sources: List<Flow<Unit>>, periodMs: Long = LIBRARY_CHANGE_MS) {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    /** Edits made in the app (watched, favourite), which neither scans nor playback report. */
    private val userChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun userChanged() {
        userChanges.tryEmit(Unit)
    }

    init {
        scope.launch {
            merge(*sources.toTypedArray(), userChanges)
                .throttleLatest(periodMs)
                .collect { _version.update { v -> v + 1 } }
        }
    }

    companion object {
        /** Scan progress refreshes screens at most this often. */
        const val LIBRARY_CHANGE_MS = 3_000L
    }
}
