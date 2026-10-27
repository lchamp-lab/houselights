package app.houselights.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.net.toUri

/** The settings screen that may let the app install apps: its own first, then Security, then Android's settings. */
enum class PermissionScreen {
    /** « Installer des applis inconnues », for this app alone. */
    APP,
    SECURITY,
    SETTINGS,

    /** None opens on this player: the sheet gives the other ways in words. */
    NONE,
}

/** Android's installer as the updater uses it. */
interface Installer {
    /** Whether Android lets the app install apps (« Installer des applis inconnues »). */
    fun allowed(): Boolean

    /** The first settings screen that opens on this player, to allow it. */
    fun permissionScreen(): PermissionScreen

    /** Opens [screen]; false when it could not be. */
    fun open(screen: PermissionScreen): Boolean

    /**
     * Hands [apk] to Android in a new session and commits it: Android asks the user to confirm, then answers on the
     * session's status ([onStatus] in the updater); [beforeCommit] runs once the file is written, before the commit.
     * Returns the session's id.
     */
    suspend fun install(apk: File, beforeCommit: suspend (session: Int) -> Unit): Int

    /** Whether Android still holds the session, waiting for the user or installing. */
    fun holds(session: Int): Boolean

    /** Opens Android's confirmation ([Intent.EXTRA_INTENT] of a status asking for the user); false when it could not. */
    fun confirm(intent: Intent): Boolean

    /** Abandons the sessions this app opened: a new attempt, or what a process killed meanwhile left. */
    fun abandonAll()
}

/**
 * Android's [PackageInstaller]: one session a version, written then committed with an explicit PendingIntent to
 * [statusReceiver]. Every install shows Android's confirmation, on every Android version: the session never asks to
 * skip it.
 */
class SessionInstaller(private val context: Context, private val statusReceiver: Class<out BroadcastReceiver>) : Installer {
    private val packages: PackageManager get() = context.packageManager
    private val installer: PackageInstaller get() = packages.packageInstaller

    override fun allowed(): Boolean = packages.canRequestPackageInstalls()

    override fun permissionScreen(): PermissionScreen =
        PermissionScreen.entries.firstOrNull { screen -> intent(screen)?.let { packages.resolveActivity(it, 0) } != null }
            ?: PermissionScreen.NONE

    private fun intent(screen: PermissionScreen): Intent? = when (screen) {
        PermissionScreen.APP -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
        PermissionScreen.SECURITY -> Intent(Settings.ACTION_SECURITY_SETTINGS)
        PermissionScreen.SETTINGS -> Intent(Settings.ACTION_SETTINGS)
        PermissionScreen.NONE -> null
    }

    override fun open(screen: PermissionScreen): Boolean = intent(screen)?.let(::start) == true

    override fun confirm(intent: Intent): Boolean = start(intent)

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Not opened: ${intent.action}", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Not allowed to open: ${intent.action}", e)
        false
    }

    override suspend fun install(apk: File, beforeCommit: suspend (session: Int) -> Unit): Int {
        val size = withContext(Dispatchers.IO) { apk.length() }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(size)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        val session = withContext(Dispatchers.IO) { installer.createSession(params) }
        var committed = false
        try {
            withContext(Dispatchers.IO) { write(session, apk, size) }
            beforeCommit(session)
            withContext(Dispatchers.IO) { installer.openSession(session).use { it.commit(statusSender(session)) } }
            committed = true
        } finally {
            if (!committed) abandon(session)
        }
        return session
    }

    /** [apk] whole into [session], synced to the device's storage before the session is committed. */
    private fun write(session: Int, apk: File, size: Long) = installer.openSession(session).use { opened ->
        opened.openWrite(BASE_APK, 0, size).use { out ->
            apk.inputStream().use { it.copyTo(out, CHUNK) }
            opened.fsync(out)
        }
    }

    /** Where Android answers [session]: this app's receiver, by name, mutable so that Android can fill in the status. */
    private fun statusSender(session: Int): IntentSender {
        val intent = Intent(context, statusReceiver).setPackage(context.packageName)
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, session, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable).intentSender
    }

    override fun holds(session: Int): Boolean = installer.getSessionInfo(session) != null

    override fun abandonAll() {
        installer.mySessions.forEach { abandon(it.sessionId) }
    }

    private fun abandon(session: Int) {
        try {
            installer.abandonSession(session)
        } catch (e: SecurityException) {
            // Already committed and handed over, or gone: Android goes on with it, or already has.
            Log.i(TAG, "Session $session not abandoned: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "Updates"
        const val BASE_APK = "base.apk"
        const val CHUNK = 64 * 1024
    }
}
