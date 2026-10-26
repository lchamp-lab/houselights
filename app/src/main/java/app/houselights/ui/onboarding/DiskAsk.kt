package app.houselights.ui.onboarding

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import app.houselights.providers.local.DiskAccess
import app.houselights.providers.local.DiskPermission

/**
 * Asks Android for what reading this player's disks needs, as [access] says ([DiskPermission]): its own window
 * ([DiskAccess.TO_ASK]), then [answered] with whether it was refused for good; or its settings
 * ([DiskAccess.IN_SETTINGS]), tried in turn, after which the disk is chosen again, as the flow's sentence says.
 */
fun interface DiskAsk {
    fun ask(access: DiskAccess, answered: (refusedForGood: Boolean) -> Unit)

    companion object {
        /** Asks nothing: for a flow that reads no disk of its own. */
        val NONE = DiskAsk { _, _ -> }
    }
}

/** [DiskAsk] on this screen's activity, for [permission]. */
@Composable
internal fun rememberDiskAsk(permission: DiskPermission): DiskAsk {
    val context = LocalContext.current
    val waiting = remember { Waiting() }
    val window = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val refused = results.filterValues { granted -> !granted }.keys
        val activity = context.activity()
        // Refused with « Ne plus demander »: Android no longer shows its window, only its settings grant it.
        val forGood = activity != null && refused.isNotEmpty() &&
            refused.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        waiting.answered?.invoke(forGood)
        waiting.answered = null
    }
    return remember(permission, window) {
        DiskAsk { access, answered ->
            when (access) {
                DiskAccess.GRANTED -> answered(false)
                DiskAccess.TO_ASK -> {
                    waiting.answered = answered
                    window.launch(permission.asked)
                }
                DiskAccess.IN_SETTINGS -> openSettings(context, permission)
            }
        }
    }
}

/** What to do once Android's window closes, while it is open. */
private class Waiting {
    var answered: ((refusedForGood: Boolean) -> Unit)? = null
}

/** The first of Android's screens that grant the access which this player has; none, and a computer grants it. */
private fun openSettings(context: Context, permission: DiskPermission) {
    val opened = permission.settings().any { intent ->
        try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No screen for ${intent.action}", e)
            false
        }
    }
    if (!opened) Log.w(TAG, "No screen of Android's grants the access to this player's disks")
}

private fun Context.activity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()

private const val TAG = "DiskAsk"
