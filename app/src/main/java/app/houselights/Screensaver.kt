package app.houselights

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether Android's screensaver (a dream) shows, from its start to its end as Android tells the receivers registered for
 * it ([Intent.ACTION_DREAMING_STARTED], [Intent.ACTION_DREAMING_STOPPED]); false until one starts. Followed from
 * [context], the application's, for the process's life. A screensaver that is an app of its own says nothing of it.
 */
internal fun screensaverShows(context: Context): StateFlow<Boolean> {
    val shows = MutableStateFlow(false)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            shows.value = intent.action == Intent.ACTION_DREAMING_STARTED
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
