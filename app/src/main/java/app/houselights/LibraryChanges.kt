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
 * The library's version, as screens read it ([app.houselights.core.LibraryEvents.libraryVersion]):
 * moved on by what [sources] say (playback ended, scan progress) and by the user's own changes
 * ([userChanged]), at most once every [periodMs]. A change alone shows at once; during a scan, the
 * user's change waits for the end of the period at most, merged with the scan's.
 */
class LibraryChanges(scope: CoroutineScope, sources: List<Flow<Unit>>, periodMs: Long = LIBRARY_CHANGE_MS) {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    /** Changes the user made (watched, a favourite), which neither a scan nor playback reports. */
    private val userChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun userChanged() {
        userChanges.tryEmit(Unit)
    }

    init {
        scope.launch {
            merge(*sources.toTypedArray(), userChanges)
                .throttleLatest(periodMs)
                .collect { _version.update { it + 1 } }
        }
    }

    companion object {
        /** Scan progress refreshes screens at most this often. */
        const val LIBRARY_CHANGE_MS = 3_000L
    }
}
