package app.houselights.ui.testing

import app.houselights.core.LibraryEvents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Library changes, moved on at once: no throttling in a test. */
class FakeEvents : LibraryEvents {
    override val libraryVersion = MutableStateFlow(0L)
    override val settledVersion = MutableStateFlow(0L)

    override fun libraryChanged() {
        settledVersion.update { it + 1 }
        libraryVersion.update { it + 1 }
    }
}
