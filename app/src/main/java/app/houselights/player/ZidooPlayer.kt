package app.houselights.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.security.MessageDigest
import java.util.Locale

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
    /** "release-keys", or "test-keys" for a build signed with AOSP's keys, which anyone has. */
    val tags: String,
) {
    /** Signed with AOSP's test keys: a certificate the firmware's apps share proves nothing. */
    val testKeys: Boolean get() = tags.contains("test-keys")

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
            tags = Build.TAGS.orEmpty(),
        )
    }
}

/**
 * Finds the Zidoo player, the app a film is handed to, and whether a password may go to it
 * ([choose]): playback and About ask it alike.
 *
 * The Z1000 Pro's firmware plays with [KNOWN_ACTIVITY]; a newer one may play with another app of
 * its own, under a name nobody published. On a Zidoo, that app is looked for among the firmware's
 * apps that open a video; elsewhere, only the known one is taken, and never trusted ([Decision]).
 */
class ZidooPlayer(
    private val context: Context,
    /** The device as its build names it. */
    private val identity: () -> DeviceIdentity = DeviceIdentity::current,
) {
    private val packages: PackageManager get() = context.packageManager

    /** The player a secret last went to ([remember]): its package and its certificate. */
    private val trusted = context.getSharedPreferences("player", Context.MODE_PRIVATE)

    /** An activity that opens a video, and whether the firmware installed its app. */
    data class Candidate(val component: ComponentName, val system: Boolean)

    /** The device: its build, and the apps of Zidoo's its firmware installed. */
    data class Device(val identity: DeviceIdentity, val zidooApps: List<String>) {
        /** A Zidoo: its build says so, or its firmware installed an app of Zidoo's, which no ordinary app can fake. */
        val zidoo: Boolean get() = identity.namesZidoo || zidooApps.isNotEmpty()
    }

    /**
     * What [choose] found: the [player], or none, among every [candidates] seen, on [device]; the
     * SHA-256 of its signing [certificate] (public), and whether a password may go to it ([decision]).
     */
    data class Choice(
        val player: Candidate?,
        val candidates: List<Candidate>,
        val device: Device,
        val certificate: String?,
        val decision: Decision,
    )

    /** Whether a password may go to the player found, and why. */
    enum class Decision(val trusted: Boolean) {
        /** No app opens a video for the app to hand it to. */
        NO_PLAYER(false),

        /** This device is no Zidoo: no password ever leaves the app. */
        NOT_A_ZIDOO(false),

        /** Installed with the firmware: a system app, which no ordinary app can replace. */
        FIRMWARE(true),

        /** An ordinary app signed with the certificate of an app of Zidoo's the firmware installed. */
        SIGNED_LIKE_FIRMWARE(true),

        /** Neither, but the player a password last went to, unchanged since. */
        REMEMBERED(true),

        /** Neither, and no password went to any player yet: this one is trusted from the first ([remember]). */
        FIRST_USE(true),

        /** Neither, and not the player a password last went to: another app, or the same with another certificate. */
        CHANGED(false),

        /** Its certificate cannot be read: nothing would tell it changed. */
        NO_CERTIFICATE(false),
    }

    /**
     * The player: the one a password last went to ([remember]) while it is there; [KNOWN_ACTIVITY]
     * when the package manager really resolves it (an intent naming a component would return it
     * unchecked); else, on a Zidoo, the firmware's app that opens a video, in the order of [preferred].
     */
    fun choose(): Choice {
        val device = device()
        val known = known()
        val found = if (device.zidoo) discover() else emptyList()
        val candidates = (listOfNotNull(known) + found).distinctBy { it.component }
        val remembered = trusted.getString(KEY_PACKAGE, null)
        val player = candidates.firstOrNull { it.component.packageName == remembered } ?: known ?: found.firstOrNull()
        val certificate = player?.let { certificate(it.component.packageName) }
        return Choice(player, candidates, device, certificate, decide(player, device, certificate))
    }

    /**
     * A password is going to [choice]'s player, which is trusted: from now on, no ordinary app but
     * that one, with that certificate, gets one. Remembered whatever trusts it, the firmware's own
     * player too: an app put in its place later is no first use.
     */
    fun remember(choice: Choice) {
        val pkg = choice.player?.component?.packageName ?: return
        val certificate = choice.certificate ?: return
        if (!choice.decision.trusted) return
        if (trusted.getString(KEY_PACKAGE, null) == pkg && trusted.getString(KEY_CERTIFICATE, null) == certificate) return
        trusted.edit().putString(KEY_PACKAGE, pkg).putString(KEY_CERTIFICATE, certificate).apply()
    }

    /**
     * Never off a Zidoo; on one, a system app, or one signed like an app of Zidoo's its firmware
     * installed (not on a build signed with AOSP's test keys, whose certificate anyone can sign
     * with), or else the player a password last went to, or the first one, as long as it does not
     * change.
     */
    private fun decide(player: Candidate?, device: Device, certificate: String?): Decision = when {
        player == null -> Decision.NO_PLAYER
        !device.zidoo -> Decision.NOT_A_ZIDOO
        player.system -> Decision.FIRMWARE
        !device.identity.testKeys && device.zidooApps.any { signedAlike(player, it) } -> Decision.SIGNED_LIKE_FIRMWARE
        certificate == null -> Decision.NO_CERTIFICATE
        else -> when (trusted.getString(KEY_PACKAGE, null)) {
            null -> Decision.FIRST_USE
            player.component.packageName ->
                if (trusted.getString(KEY_CERTIFICATE, null) == certificate) Decision.REMEMBERED else Decision.CHANGED
            else -> Decision.CHANGED
        }
    }

    private fun signedAlike(player: Candidate, firmwareApp: String) =
        packages.checkSignatures(player.component.packageName, firmwareApp) == PackageManager.SIGNATURE_MATCH

    /** The SHA-256 of [pkg]'s signing certificates, in hex as apksigner prints it; null when none can be read. */
    private fun certificate(pkg: String): String? {
        val signing = try {
            packages.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val signers = signing?.apkContentsSigners.orEmpty()
        if (signers.isEmpty()) return null
        val sha256 = MessageDigest.getInstance("SHA-256")
        return signers.map { sha256.digest(it.toByteArray()).toHex() }.sorted().joinToString(",")
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(Locale.ROOT, it) }

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

        private const val KEY_PACKAGE = "trusted_package"
        private const val KEY_CERTIFICATE = "trusted_certificate"

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
