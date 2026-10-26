package app.houselights.providers.local

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/** Whether the app may read the player's own disks, and what grants it. */
enum class DiskAccess {
    GRANTED,

    /** Android asks in its own window, for [DiskPermission.asked] (Android 9 and 10). */
    TO_ASK,

    /** Only Android's settings grant it ([DiskPermission.settings]): all-files access from Android 11, or a refusal for good. */
    IN_SETTINGS,
}

/**
 * What reading folders on the player's own disks needs ([Protocol.LOCAL]), the videos and their NFOs, artwork and
 * subtitles: on Android 9 and 10, READ_EXTERNAL_STORAGE (10 in its legacy storage); from Android 11, where it reads
 * media files alone and an NFO is none, all-files access (MANAGE_EXTERNAL_STORAGE), which only Android's settings grant.
 * The app is not on Google Play, whose policy limits it. Screens ask; this only says what is needed and where.
 */
class DiskPermission(
    private val context: Context,
    private val sdk: Int = Build.VERSION.SDK_INT,
    /** Whether all-files access is granted (Android 11 and later). */
    private val allFiles: () -> Boolean = ::isExternalStorageManager,
) {
    /** The permissions Android's window asks for; none from Android 11, where only the settings grant the access. */
    val asked: Array<String>
        get() = if (sdk >= Build.VERSION_CODES.R) emptyArray() else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    /**
     * Where the access stands; [refusedForGood] when Android's window was refused for good ("Ne plus demander"), which
     * then refuses at once and only the settings undo.
     */
    fun access(refusedForGood: Boolean = false): DiskAccess = when {
        sdk >= Build.VERSION_CODES.R -> if (allFiles()) DiskAccess.GRANTED else DiskAccess.IN_SETTINGS
        asked.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } -> DiskAccess.GRANTED
        refusedForGood -> DiskAccess.IN_SETTINGS
        else -> DiskAccess.TO_ASK
    }

    /**
     * Android's screens that grant it, to try in turn: all-files access for this app (Android 11 and later), then the
     * app's details, where its permissions are. A TV box may lack either: starting it throws ActivityNotFoundException,
     * and the next is tried; with none, a computer grants it (README, Privacy). Not looked up beforehand, as Android 11
     * hides from the app the activities its manifest does not query.
     */
    fun settings(): List<Intent> {
        val app = Uri.fromParts("package", context.packageName, null)
        return listOfNotNull(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, app).takeIf { sdk >= Build.VERSION_CODES.R },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app),
        )
    }
}

private fun isExternalStorageManager(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
