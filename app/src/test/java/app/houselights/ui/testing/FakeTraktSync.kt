package app.houselights.ui.testing

import app.houselights.providers.trakt.TraktSyncState
import app.houselights.providers.trakt.TraktSyncing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** The sync as the Trakt pane sees it: [state] set by the test, each request counted in [asked] and shown running. */
class FakeTraktSync(initial: TraktSyncState = TraktSyncState()) : TraktSyncing {
    override val state = MutableStateFlow(initial)

    var asked = 0
        private set

    override fun syncNow() {
        asked++
        state.update { it.copy(running = true) }
    }
}
