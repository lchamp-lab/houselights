package app.houselights.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * The device as its build names it ([Build]): what tells a Zidoo, and what About and the log say of
 * it. These are read-only properties of the firmware: no app can change them.
 */
data class DeviceIdentity(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val product: String,
    val device: String,
    val fingerprint: String,
) {
    /** Some field names Zidoo: a Realtek build may keep its chip maker's name in some of them, not in all. */
    val namesZidoo: Boolean
        get() = listOf(manufacturer, brand, model, product, device, fingerprint).any { it.contains(ZIDOO, ignoreCase = true) }

    companion object {
        private const val ZIDOO = "zidoo"

        fun current() = DeviceIdentity(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            product = Build.PRODUCT.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            fingerprint = Build.FINGERPRINT.orEmpty(),
        )
    }
}

/**
 * Finds the Zidoo player, the app a film is handed to ([choose]): playback and About ask it alike.
 *
 * The Z1000 Pro's firmware plays with [KNOWN_ACTIVITY]; a newer one may play with another app of
 * its own, under a name nobody published. On a Zidoo, that app is looked for among the firmware's
 * apps that open a video; elsewhere, only the known one is taken.
 */
class ZidooPlayer(
    private val context: Context,
    /** The device as its build names it. */
    private val identity: () -> DeviceIdentity = DeviceIdentity::current,
) {
    private val packages: PackageManager get() = context.packageManager

    /** An activity that opens a video, and whether the firmware installed its app. */
    data class Candidate(val component: ComponentName, val system: Boolean)

    /** The device: its build, and the apps of Zidoo's its firmware installed. */
    data class Device(val identity: DeviceIdentity, val zidooApps: List<String>) {
        /** A Zidoo: its build says so, or its firmware installed an app of Zidoo's, which no ordinary app can fake. */
        val zidoo: Boolean get() = identity.namesZidoo || zidooApps.isNotEmpty()
    }

    /** What [choose] found: the [player], or none, among every [candidates] seen, on [device]. */
    data class Choice(val player: Candidate?, val candidates: List<Candidate>, val device: Device)

    /**
     * The player: [KNOWN_ACTIVITY] when the package manager really resolves it (an intent naming a
     * component would return it unchecked); else, on a Zidoo, the firmware's app that opens a video,
     * in the order of [preferred].
     */
    fun choose(): Choice {
        val device = device()
        val known = known()
        val found = if (device.zidoo) discover() else emptyList()
        val candidates = (listOfNotNull(known) + found).distinctBy { it.component }
        return Choice(known ?: found.firstOrNull(), candidates, device)
    }

    private fun device(): Device {
        // From Android 11, only the apps this one may see (see <queries> in the manifest).
        val zidooApps = packages.getInstalledApplications(0)
            .filter { it.isSystem() && it.packageName.startsWith(ZIDOO_PREFIX) }
            .map { it.packageName }
            .sorted()
        return Device(identity(), zidooApps)
    }

    private fun known(): Candidate? {
        val info = packages.resolveActivity(Intent().setComponent(ComponentName(KNOWN_PACKAGE, KNOWN_ACTIVITY)), 0)?.activityInfo
        return info?.let(::candidate)
    }

    /**
     * The firmware's activities that open a video, by type or by the schemes the app hands over
     * (an app whose filter names a scheme answers no intent without one): its system apps, or apps
     * under a name of Zidoo's, never ours.
     */
    private fun discover(): List<Candidate> {
        val probes = probes()
        val defaults = probes.mapNotNull { packages.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo }
            .map { it.component() }
            .toSet()
        return probes.flatMap { probe -> QUERY_FLAGS.flatMap { packages.queryIntentActivities(probe, it) } }
            .mapNotNull { it.activityInfo }
            .filter { !it.packageName.isOurs() && (it.applicationInfo.isSystem() || it.packageName.startsWith(ZIDOO_PREFIX)) }
            .distinctBy { it.component() }
            .map(::candidate)
            .sortedWith(preferred(defaults))
    }

    /**
     * The order players are taken in: an app of Zidoo's first, then the system's default, then a
     * system app, then an activity named for films ("MovieActivity", "VideoPlayer"); by name last,
     * so the same apps always give the same player.
     */
    private fun preferred(defaults: Set<ComponentName>) =
        compareByDescending<Candidate> { it.component.packageName.startsWith(ZIDOO_PREFIX) }
            .thenByDescending { it.component in defaults }
            .thenByDescending { it.system }
            .thenByDescending { FILM_WORDS.containsMatchIn(it.component.className.substringAfterLast('.')) }
            .thenBy { it.component.packageName }
            .thenBy { it.component.className }

    private fun candidate(info: ActivityInfo) = Candidate(info.component(), info.applicationInfo.isSystem())

    private fun ActivityInfo.component() = ComponentName(packageName, name)

    private fun ApplicationInfo?.isSystem() = this != null && flags and ApplicationInfo.FLAG_SYSTEM != 0

    private fun String.isOurs() = this == OWN_PACKAGE || startsWith("$OWN_PACKAGE.")

    companion object {
        /** The Z1000 Pro's player. */
        const val KNOWN_PACKAGE = "com.android.gallery3d"
        const val KNOWN_ACTIVITY = "com.android.gallery3d.app.MovieActivity"
        private const val ZIDOO_PREFIX = "com.zidoo."

        /** This app's package, its debug and bench builds' too. */
        private const val OWN_PACKAGE = "app.houselights"

        /** What a player is asked to open: a video by its type, and at the addresses the app hands over. */
        private fun probes() = listOf(
            Intent(Intent.ACTION_VIEW).setType(VIDEO),
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("nfs://nas/film.mkv"), VIDEO),
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("smb://nas/film.mkv"), VIDEO),
        )

        private const val VIDEO = "video/*"

        /** Filters that say they are a default, and the others. */
        private val QUERY_FLAGS = listOf(PackageManager.MATCH_DEFAULT_ONLY, 0)
        private val FILM_WORDS = Regex("(?i)movie|video|player")
    }
}
