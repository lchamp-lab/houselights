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

/** Apps on Robolectric's package manager; two apps handling the same video have no default between them. */
internal class FakeApps(private val context: Context) {
    private val pm = shadowOf(context.packageManager)
    private val installed = mutableSetOf<ComponentName>()

    init {
        pm.setShouldShowActivityChooser(true)
    }

    /**
     * Installs [pkg], a [system] app or not, an update of one ([updated]: its [build] as Android gives it), signed with
     * [certificate], by [installer]; with [activity], which answers a video by its type ([byType]) and at an address of
     * each of [schemes], and which other apps may start ([exported]) unless they lack [permission].
     */
    fun install(
        pkg: String,
        activity: String? = null,
        system: Boolean = false,
        certificate: String? = null,
        byType: Boolean = false,
        schemes: List<String> = SHARES,
        installer: String? = null,
        exported: Boolean = true,
        permission: String? = null,
        updated: Boolean = false,
        build: AppBuild = AppBuild(),
    ): ComponentName? {
        val signatures = certificate?.let { arrayOf(Signature(it.toByteArray())) }
        pm.installPackage(
            PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
                    if (updated) flags = flags or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                    nonLocalizedLabel = build.label
                }
                longVersionCode = build.code
                versionName = build.name
                lastUpdateTime = build.at
                // Its signing info alone, as the app reads it, which Robolectric's checkSignatures compares from
                // Android 9 on rather than the signatures deprecated then.
                signingInfo = signatures?.let(::signingInfo)
            },
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
        if (schemes.isNotEmpty()) pm.addIntentFilterForActivity(component, video(*schemes.toTypedArray()))
        return component
    }

    /**
     * [pkg] replaced by another install of it, its [activity] answering at [schemes] as before: an update, or another app
     * under its name, as Android lists it once installed.
     */
    fun reinstall(
        pkg: String,
        activity: String,
        system: Boolean = false,
        certificate: String? = null,
        schemes: List<String> = SHARES,
        updated: Boolean = false,
        build: AppBuild = AppBuild(),
    ): ComponentName? {
        uninstall(pkg)
        return install(pkg, activity, system, certificate, schemes = schemes, updated = updated, build = build)
    }

    fun uninstall(pkg: String) {
        installed.filter { it.packageName == pkg }.forEach(pm::clearIntentFilterForActivity)
        pm.removePackage(pkg)
    }

    /** Makes [component] the default app for videos. */
    @Suppress("DEPRECATION") // The call the system makes a default app with, deprecated as apps may not.
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

    /**
     * An app's build as Android gives it: its versionCode ([code]) and versionName ([name]) as the app declares them, its
     * [label], and when Android installed it ([at], ms since the epoch).
     */
    data class AppBuild(val code: Long = 1, val name: String? = null, val label: String? = null, val at: Long = 0)

    companion object {
        const val GALLERY = "com.android.gallery3d"
        const val MOVIE_ACTIVITY = "com.android.gallery3d.app.MovieActivity"

        /** The player of newer firmwares, under a Zidoo package name. */
        const val ZIDOO_PLAYER = "com.zidoo.player"
        const val ZIDOO_PLAYER_ACTIVITY = "com.zidoo.player.VideoPlayerActivity"

        /** The shares the app hands a player a file on. */
        val SHARES = listOf("smb", "nfs")

        /** What the Z1000 Pro's player accepts (read on the device), plus https; smb and nfs go by name only. */
        val FIRMWARE_SCHEMES = listOf("http", "https", "file", "content")

        /** Zidoo's own certificate, and another one. */
        const val ZIDOO_KEY = "zidoo-firmware-key"
        const val OTHER_KEY = "someone-else-key"

        /** A Zidoo whose build says so. */
        val ZIDOO_BUILD = DeviceIdentity(
            "ZIDOO",
            "ZIDOO",
            "Z9X Pro",
            "Z9X_Pro",
            "rtd1619b",
            "ZIDOO/Z9X_Pro/rtd1619b:11/RP1A/1:user/release-keys",
            "release-keys",
        )

        /** The Z1000 Pro, as its build names it on the device: its chip's brand, and AOSP's test keys. */
        val Z1000_PRO_BUILD = DeviceIdentity(
            "ZIDOO",
            "rtk",
            "Z1000 Pro",
            "thor32",
            "thor32",
            "rtk/thor32/thor32:9/PPR1.180610.011/1:userdebug/test-keys",
            "test-keys",
        )

        /** A Realtek board whose build names only its chip: a Zidoo, or not. */
        val REALTEK_BUILD = DeviceIdentity(
            "Realtek",
            "Realtek",
            "RTD1619",
            "rtd1619",
            "rtd1619",
            "Realtek/rtd1619/rtd1619:9/PPR1/1:user/release-keys",
            "release-keys",
        )

        val OTHER_BUILD = DeviceIdentity(
            "Google",
            "google",
            "Chromecast",
            "sabrina",
            "sabrina",
            "google/sabrina/sabrina:12/STTE/1:user/release-keys",
            "release-keys",
        )
    }
}
