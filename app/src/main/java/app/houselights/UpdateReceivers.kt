package app.houselights

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import kotlinx.coroutines.launch

// The updater's receivers (src/updater/AndroidManifest.xml, the release's alone): beside the app's container, which
// they reach, as MainActivity does.

/** Android's answer for a session the updater committed: its confirmation to open, or how the install ended. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updates = (context.applicationContext as HouselightsApp).container.updates
        updates.onSessionStatus(
            id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, NO_SESSION),
            status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
            message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java),
        )
    }

    private companion object {
        const val NO_SESSION = -1
    }
}

/**
 * The app just replaced by an update: its first start, which says what was installed (Updater.run). On Android 9 it
 * opens the app again when it updated itself; later versions may not let it, and the sheet said to reopen it.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = (context.applicationContext as HouselightsApp).container
        val answer = goAsync()
        container.scope.launch {
            try {
                if (container.updates.replaced()) {
                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } finally {
                answer.finish()
            }
        }
    }
}
