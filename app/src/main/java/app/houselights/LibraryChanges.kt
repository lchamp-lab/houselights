package app.houselights

import app.houselights.core.throttleLatest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The library version screens observe ([app.houselights.core.LibraryEvents.libraryVersion]), moved on by
 * [sources] and in-app edits ([userChanged]) at most once every [periodMs], an isolated change at once, and by a
 * running pass's [progress] at most once every [progressMs], but every [periodMs] while the library's very first scan
 * fills home ([homeFilling]), until its row of latest films or of latest shows is full: home grows as the titles are
 * found, and every screen with it, a wall then holding less than a page.
 */
class LibraryChanges(
    scope: CoroutineScope,
    sources: List<Flow<Unit>>,
    progress: Flow<Unit> = emptyFlow(),
    periodMs: Long = LIBRARY_CHANGE_MS,
    progressMs: Long = SCAN_PROGRESS_MS,
    /** Whether the library's very first scan is still filling home ([app.houselights.providers.local.ScanState.homeFilling]). */
    homeFilling: () -> Boolean = { false },
) {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    private val _settled = MutableStateFlow(0L)

    /** Moves on every change but a running pass's progress, before [version] shows it; the pass's end moves it. */
    val settled: StateFlow<Long> = _settled

    /** Edits made in the app (watched, favourite), which neither scans nor playback report. */
    private val userChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun userChanged() {
        userChanges.tryEmit(Unit)
    }

    init {
        scope.launch {
            val changes = merge(*sources.toTypedArray(), userChanges).onEach { _settled.update { s -> s + 1 } }
            // Progress waiting while the version moved is shown already: any reload since read it.
            val ticks = progress.map { _version.value }
                .throttleLatest { if (homeFilling()) periodMs else progressMs }
                .filter { it == _version.value }
                .map { }
            merge(changes, ticks)
                .throttleLatest(periodMs)
                .collect { _version.update { v -> v + 1 } }
        }
    }

    companion object {
        /** Changes refresh screens at most this often. */
        const val LIBRARY_CHANGE_MS = 3_000L

        /** A running pass's progress refreshes screens at most this often, but while the first fills home; its end, at once. */
        const val SCAN_PROGRESS_MS = 20_000L
    }
}
