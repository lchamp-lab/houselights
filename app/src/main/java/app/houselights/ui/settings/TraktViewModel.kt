package app.houselights.ui.settings

import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.houselights.R
import app.houselights.core.FailureKind
import app.houselights.core.failureKind
import app.houselights.core.suspendRunCatching
import app.houselights.providers.local.TraktSyncError
import app.houselights.providers.trakt.DeviceCodeEnd
import app.houselights.providers.trakt.TraktLink
import app.houselights.providers.trakt.TraktSignedOutException
import app.houselights.providers.trakt.TraktSyncState
import app.houselights.providers.trakt.TraktSyncing
import app.houselights.ui.util.LatestJob
import app.houselights.ui.util.UiError
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.launchAction
import app.houselights.ui.util.toWriteError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A code on screen: [userCode] to enter on Trakt's page, valid for [leftMs] more. */
internal data class ShownCode(val userCode: String, val leftMs: Long) {
    /** The time left in whole minutes, rounded up: the last one reads "1 min" until the code ends. */
    val leftMinutesMs: Long get() = (leftMs + MINUTE_MS - 1) / MINUTE_MS * MINUTE_MS

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}

/**
 * The Trakt pane's work: connecting with a device code ([connecting] while asking for it and showing it), syncing,
 * then disconnecting. Why the last attempt failed stays in [note] until the next one. Disconnecting runs in [work],
 * which outlives the screen.
 */
internal class TraktViewModel(
    private val account: TraktLink,
    private val sync: TraktSyncing,
    private val work: CoroutineScope,
    /** The clock the code's time left is counted on. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {
    /** Whether this build can reach Trakt at all. */
    val available: Boolean get() = account.available

    /** True from asking for a code until it ends or is cancelled; [code] once Trakt gave one. */
    var connecting by mutableStateOf(false)
        private set

    var code by mutableStateOf<ShownCode?>(null)
        private set

    /** Why the last connection or disconnection failed, as a string resource; null when it did not. */
    var note by mutableStateOf<Int?>(null)
        private set

    var disconnecting by mutableStateOf(false)
        private set

    /** Where the sync with Trakt stands. */
    val syncState: StateFlow<TraktSyncState> get() = sync.state

    private val attempt = LatestJob(viewModelScope)

    /** Joins the library and Trakt now; nothing while a sync runs. */
    fun syncNow() {
        if (!sync.state.value.running) sync.syncNow()
    }

    /** Asks Trakt for a code and waits for the viewer to approve it; a second call while one runs does nothing. */
    fun connect() {
        if (connecting || disconnecting) return
        note = null
        connecting = true
        attempt.launch {
            suspendRunCatching {
                val device = account.deviceCode()
                val ends = clock() + device.expiresIn * MS_PER_S
                val countdown = launch {
                    while (true) {
                        code = ShownCode(device.userCode, (ends - clock()).coerceAtLeast(0))
                        delay(TICK_MS)
                    }
                }
                try {
                    account.awaitApproval(device)
                } finally {
                    countdown.cancel()
                }
            }
                .onSuccess { end -> note = noteOf(end) }
                .onFailure { e ->
                    Log.w(TAG, "Connecting to Trakt failed", e)
                    note = noteOf(e)
                }
            code = null
            connecting = false
        }
    }

    /** Stops waiting for the code shown: Back on its sheet. */
    fun cancel() {
        if (!connecting) return
        attempt.cancel()
        code = null
        connecting = false
    }

    /** Reads the account's name when its session lacks it: the network failed as it connected. */
    fun readName() {
        viewModelScope.launchAction(TAG, "Saving the Trakt account's name") { account.readName() }
    }

    /** Revokes the account on Trakt and forgets it here; only forgetting can fail (storage). */
    fun disconnect() {
        if (disconnecting) return
        cancel()
        note = null
        disconnecting = true
        work.launch {
            suspendRunCatching { account.signOut() }.onFailure { e ->
                Log.w(TAG, "Disconnecting from Trakt failed", e)
                note = e.toWriteError().message
            }
            disconnecting = false
        }
    }

    private companion object {
        const val TAG = "Trakt"
        const val MS_PER_S = 1_000L
        const val TICK_MS = 1_000L
    }
}

/** The message for how a code ended; null once approved. */
@StringRes
internal fun noteOf(end: DeviceCodeEnd): Int? = when (end) {
    DeviceCodeEnd.APPROVED -> null
    DeviceCodeEnd.DENIED -> R.string.trakt_denied
    DeviceCodeEnd.EXPIRED -> R.string.trakt_expired
    // Another device used the code, or Trakt lost it: a new one is all it takes.
    DeviceCodeEnd.INVALID, DeviceCodeEnd.USED -> R.string.trakt_failed
}

/** The message for a failure: the account ended, the network, Trakt refusing the build's key, the device's storage, else Trakt. */
@StringRes
internal fun noteOf(e: Throwable): Int {
    if (e is TraktSignedOutException) return R.string.trakt_ended
    return when (e.failureKind()) {
        FailureKind.UNREACHABLE, FailureKind.IO, FailureKind.CERTIFICATE -> R.string.trakt_offline
        FailureKind.REFUSED -> R.string.trakt_refused
        FailureKind.KEYSTORE, FailureKind.STORAGE_FULL, FailureKind.STORAGE_FAILED -> e.toWriteError().message
        else -> R.string.trakt_failed
    }
}

/** Why the last exchange with Trakt failed, and that it resumes on its own. */
@StringRes
internal fun noteOf(error: TraktSyncError): Int = when (error) {
    TraktSyncError.OFFLINE -> R.string.trakt_sync_offline
    TraktSyncError.BUSY -> R.string.trakt_sync_busy
    TraktSyncError.FAILED -> R.string.trakt_sync_failed
    TraktSyncError.STORAGE -> UiError.NOT_SAVED.message
}

/** The pane's ViewModel, kept while Settings is open. */
@Composable
internal fun traktViewModel(): TraktViewModel {
    val app = appContainer()
    return viewModel { TraktViewModel(app.trakt, app.traktSync, app.scope) }
}
