package app.houselights.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo

/**
 * Apps on Robolectric's package manager, as a player's firmware or its owner installs them. Two
 * apps answering the same video have no default between them, as on a device where none was chosen.
 */
internal class FakeApps(private val context: Context) {
    private val pm = shadowOf(context.packageManager)
    private val installed = mutableSetOf<ComponentName>()

    init {
        pm.setShouldShowActivityChooser(true)
    }

    /**
     * Installs [pkg], a [system] app or not, signed with [certificate], by [installer]; with [activity],
     * which answers a video by its type ([byType]) and by the smb and nfs schemes ([bySchemes]), and
     * which other apps may start ([exported]) unless they lack [permission].
     */
    fun install(
        pkg: String,
        activity: String? = null,
        system: Boolean = false,
        certificate: String? = null,
        byType: Boolean = false,
        bySchemes: Boolean = true,
        installer: String? = null,
        exported: Boolean = true,
        permission: String? = null,
    ): ComponentName? {
        val signatures = certificate?.let { arrayOf(Signature(it.toByteArray())) }
        pm.installPackage(
            PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
                }
                this.signatures = signatures
                signingInfo = signatures?.let(::signingInfo)
            }
        )
        if (installer != null) {
            context.packageManager.setInstallerPackageName(pkg, installer)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) pm.setInstallSourceInfo(pkg, installer, installer)
        }
        val component = activity?.let { ComponentName(pkg, it) } ?: return null
        pm.addActivityIfNotPresent(component).also {
            it.exported = exported
            it.permission = permission
        }
        installed += component
        if (byType) pm.addIntentFilterForActivity(component, video())
        if (bySchemes) pm.addIntentFilterForActivity(component, video("smb", "nfs"))
        return component
    }

    /** Uninstalls [pkg], with what its activities answered. */
    fun uninstall(pkg: String) {
        installed.filter { it.packageName == pkg }.forEach(pm::clearIntentFilterForActivity)
        pm.removePackage(pkg)
    }

    /** Makes [component] what the system opens a video with, unasked. */
    @Suppress("DEPRECATION")
    fun makeDefault(component: ComponentName) {
        context.packageManager.addPreferredActivity(video("smb", "nfs"), IntentFilter.MATCH_CATEGORY_SCHEME, arrayOf(component), component)
        context.packageManager.addPreferredActivity(video(), IntentFilter.MATCH_CATEGORY_TYPE, arrayOf(component), component)
    }

    private fun video(vararg schemes: String) = IntentFilter(Intent.ACTION_VIEW).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        addDataType("video/*")
        schemes.forEach(::addDataScheme)
    }

    private fun signingInfo(signatures: Array<Signature>): SigningInfo =
        Shadow.newInstanceOf(SigningInfo::class.java).also { Shadow.extract<ShadowSigningInfo>(it).setSignatures(signatures) }

    companion object {
        const val GALLERY = "com.android.gallery3d"
        const val MOVIE_ACTIVITY = "com.android.gallery3d.app.MovieActivity"

        /** A newer firmware's player, under a name of Zidoo's. */
        const val ZIDOO_PLAYER = "com.zidoo.player"
        const val ZIDOO_PLAYER_ACTIVITY = "com.zidoo.player.VideoPlayerActivity"

        /** Zidoo's own certificate, and another one. */
        const val ZIDOO_KEY = "zidoo-firmware-key"
        const val OTHER_KEY = "someone-else-key"

        /** A Zidoo whose build says so. */
        val ZIDOO_BUILD = DeviceIdentity(
            "ZIDOO", "ZIDOO", "Z9X Pro", "Z9X_Pro", "rtd1619b", "ZIDOO/Z9X_Pro/rtd1619b:11/RP1A/1:user/release-keys", "release-keys",
        )

        /** A Realtek board whose build names only its chip: a Zidoo, or not. */
        val REALTEK_BUILD = DeviceIdentity(
            "Realtek", "Realtek", "RTD1619", "rtd1619", "rtd1619", "Realtek/rtd1619/rtd1619:9/PPR1/1:user/release-keys", "release-keys",
        )

        /** Another Android TV box. */
        val OTHER_BUILD = DeviceIdentity(
            "Google", "google", "Chromecast", "sabrina", "sabrina", "google/sabrina/sabrina:12/STTE/1:user/release-keys", "release-keys",
        )
    }
}
