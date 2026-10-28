package app.houselights

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether Android's screensaver (a dream) shows over the app, from its start to its end as Android tells the receivers
 * registered for it ([Intent.ACTION_DREAMING_STARTED], [Intent.ACTION_DREAMING_STOPPED]): one that starts while the
 * app is [onScreen], not one over another app, which may play from the NAS again once woken; false until one starts.
 * Followed from [context], the application's, for the process's life. A screensaver that is an app of its own says
 * nothing of it.
 */
internal fun screensaverShows(context: Context, onScreen: () -> Boolean): StateFlow<Boolean> {
    val shows = MutableStateFlow(false)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            shows.value = intent.action == Intent.ACTION_DREAMING_STARTED && onScreen()
        }
    }
    val dreams = IntentFilter().apply {
        addAction(Intent.ACTION_DREAMING_STARTED)
        addAction(Intent.ACTION_DREAMING_STOPPED)
    }
    // Protected broadcasts, which the system alone may send: exported, the receiver needs no permission of its own.
    ContextCompat.registerReceiver(context, receiver, dreams, ContextCompat.RECEIVER_EXPORTED)
    return shows
}
