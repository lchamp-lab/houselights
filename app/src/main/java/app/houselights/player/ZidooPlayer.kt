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

    /** "ZIDOO Z9X Pro": its maker and its model, the maker said once when the model already names it. */
    val name: String
        get() = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model".trim()

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

/** What About says of the player: the decision playback takes ([ZidooPlayer.choose]), in brief. */
sealed interface PlayerStatus {
    /** Found, and a password may go to it. */
    data class Detected(val packageName: String) : PlayerStatus

    /** Found, but no password goes to it, for [reason]. */
    data class Unrecognized(val packageName: String, val reason: ZidooPlayer.Decision) : PlayerStatus

    /** No app to hand a film to. */
    data object NotDetected : PlayerStatus
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

    /**
     * An activity that opens a video; whether the firmware installed its app ([system]), or else it is
     * signed like a system app of Zidoo's on a build of the firmware's own keys ([signedLikeFirmware]).
     */
    data class Candidate(val component: ComponentName, val system: Boolean, val signedLikeFirmware: Boolean = false)

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
     * The player: the one a password last went to ([remember]) while it is there with the same
     * certificate; else the first of [preferred], among [KNOWN_ACTIVITY] when the package manager
     * really resolves it (an intent naming a component would return it unchecked) and, on a Zidoo,
     * the firmware's apps that open a video.
     */
    fun choose(): Choice {
        val device = device()
        val found = listOfNotNull(known(device)) + if (device.zidoo) discover(device) else emptyList()
        val defaults = if (device.zidoo) defaults() else emptySet()
        val candidates = found.distinctBy { it.component }.sortedWith(preferred(defaults))
        val player = candidates.firstOrNull(::remembered) ?: candidates.firstOrNull()
        val certificate = player?.let { certificate(it.component.packageName) }
        return Choice(player, candidates, device, certificate, decide(player, device, certificate))
    }

    /**
     * [candidate] is the player a password last went to ([remember]), with the same certificate: an
     * app put in its place, under its name, does not come first for it.
     */
    private fun remembered(candidate: Candidate): Boolean {
        val pkg = candidate.component.packageName
        return pkg == trusted.getString(KEY_PACKAGE, null) && certificate(pkg) == trusted.getString(KEY_CERTIFICATE, null)
    }

    /** What About says of the player: what [choose] decides. */
    fun status(): PlayerStatus {
        val choice = choose()
        val pkg = choice.player?.component?.packageName ?: return PlayerStatus.NotDetected
        return if (choice.decision.trusted) PlayerStatus.Detected(pkg) else PlayerStatus.Unrecognized(pkg, choice.decision)
    }

    /**
     * A password went to [choice]'s player, which is trusted and has started (not one that could not
     * be, under a name it took): from now on, no ordinary app but that one, with that certificate, gets
     * one. Remembered whatever trusts it, the firmware's own player too: an app put in its place later
     * is no first use.
     */
    fun remember(choice: Choice) {
        val pkg = choice.player?.component?.packageName ?: return
        val certificate = choice.certificate ?: return
        if (!choice.decision.trusted) return
        if (trusted.getString(KEY_PACKAGE, null) == pkg && trusted.getString(KEY_CERTIFICATE, null) == certificate) return
        trusted.edit().putString(KEY_PACKAGE, pkg).putString(KEY_CERTIFICATE, certificate).apply()
    }

    /**
     * [choice] in one line for the log, without a secret: the decision and why, the player and the
     * SHA-256 of its certificate, every candidate seen (system app or not, and who installed it), the
     * device's build and the system apps of Zidoo's seen. The evening it goes wrong, it says why.
     */
    fun describe(choice: Choice): String {
        val candidates = choice.candidates.joinToString("; ") {
            val kind = when {
                it.system -> "system"
                it.signedLikeFirmware -> "ordinary, signed like the firmware"
                else -> "ordinary"
            }
            "${it.component.flattenToShortString()} $kind, installed by ${installer(it.component.packageName)}"
        }
        return "${choice.decision}: ${why(choice)}; player ${choice.player?.component?.flattenToShortString()}, " +
            "certificate SHA-256 ${choice.certificate}; candidates [$candidates]; ${choice.device.identity}; " +
            "system apps of Zidoo's ${choice.device.zidooApps}"
    }

    private fun why(choice: Choice): String = when (choice.decision) {
        Decision.NO_PLAYER -> if (choice.device.zidoo) "no app of the firmware's opens a video" else "not a Zidoo, nor $KNOWN_PACKAGE"
        Decision.NOT_A_ZIDOO -> "no field of the build names Zidoo, and no system app of Zidoo's is seen"
        Decision.FIRMWARE -> "a system app"
        Decision.SIGNED_LIKE_FIRMWARE -> "signed like a system app of Zidoo's"
        Decision.REMEMBERED -> "the player a secret last went to, with the same certificate"
        Decision.FIRST_USE -> "no secret went to a player yet" +
            if (choice.device.identity.testKeys) "; test-keys: a certificate shared with the firmware proves nothing" else ""
        Decision.CHANGED -> "a secret last went to ${trusted.getString(KEY_PACKAGE, null)}, " +
            "certificate SHA-256 ${trusted.getString(KEY_CERTIFICATE, null)}"
        Decision.NO_CERTIFICATE -> "its certificate cannot be read"
    }

    /** Who installed [pkg]: the firmware's own updater, a store, or nobody said (null). */
    private fun installer(pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packages.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packages.getInstallerPackageName(pkg)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: IllegalArgumentException) {
        // Android 9 says so of a package that is not installed.
        null
    }

    /**
     * Never off a Zidoo; on one, a system app, or one signed like an app of Zidoo's its firmware
     * installed ([signedLikeFirmware]), or else the player a password last went to, or the first one,
     * as long as it does not change.
     */
    private fun decide(player: Candidate?, device: Device, certificate: String?): Decision = when {
        player == null -> Decision.NO_PLAYER
        !device.zidoo -> Decision.NOT_A_ZIDOO
        player.system -> Decision.FIRMWARE
        player.signedLikeFirmware -> Decision.SIGNED_LIKE_FIRMWARE
        certificate == null -> Decision.NO_CERTIFICATE
        else -> when (trusted.getString(KEY_PACKAGE, null)) {
            null -> Decision.FIRST_USE
            player.component.packageName ->
                if (trusted.getString(KEY_CERTIFICATE, null) == certificate) Decision.REMEMBERED else Decision.CHANGED
            else -> Decision.CHANGED
        }
    }

    /**
     * [pkg] is signed like an app of Zidoo's the firmware installed, which only Zidoo can sign: not on
     * a build signed with AOSP's test keys, as the Z1000 Pro's is, whose certificate anyone can sign with.
     */
    private fun signedLikeFirmware(pkg: String, device: Device) =
        !device.identity.testKeys && device.zidooApps.any { packages.checkSignatures(pkg, it) == PackageManager.SIGNATURE_MATCH }

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

    private fun known(device: Device): Candidate? {
        val info = packages.resolveActivity(Intent().setComponent(ComponentName(KNOWN_PACKAGE, KNOWN_ACTIVITY)), 0)?.activityInfo
        return info?.takeIf(::startable)?.let { candidate(it, device) }
    }

    /**
     * The firmware's activities that open a video, by type or at an address of each scheme [probes]
     * asks (an app whose filter names a scheme answers no intent without one): its system apps, apps
     * signed like them ([Candidate.signedLikeFirmware]), or apps under a name of Zidoo's, never ours,
     * and only what this app may start ([startable]).
     */
    private fun discover(device: Device): List<Candidate> =
        probes().flatMap { probe -> QUERY_FLAGS.flatMap { packages.queryIntentActivities(probe, it) } }
            .mapNotNull { it.activityInfo }
            .filter { startable(it) && !it.packageName.isOurs() }
            .distinctBy { it.component() }
            .map { candidate(it, device) }
            .filter { it.system || it.signedLikeFirmware || it.component.packageName.startsWith(ZIDOO_PREFIX) }

    /** What the system opens a video with unasked, at any address [probes] asks. */
    private fun defaults(): Set<ComponentName> =
        probes().mapNotNull { packages.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo }
            .map { it.component() }
            .toSet()

    /**
     * The order players are taken in, what no ordinary app can fake first: the system's apps, the old
     * player, then an app of Zidoo's, then any other; then an app signed like them; then an ordinary
     * app, under the old player's name or one of Zidoo's, which any app may take. Within each, the
     * system's default, then an activity named for films ("MovieActivity", "VideoPlayer"); by name
     * last, so the same apps always give the same player.
     */
    private fun preferred(defaults: Set<ComponentName>) =
        compareBy<Candidate> { rank(it) }
            .thenByDescending { it.component in defaults }
            .thenByDescending { FILM_WORDS.containsMatchIn(it.component.className.substringAfterLast('.')) }
            .thenBy { it.component.packageName }
            .thenBy { it.component.className }

    /** The ranks of [preferred], first to last. */
    private enum class Rank { OLD_PLAYER, ZIDOOS, SYSTEM, SIGNED_LIKE_FIRMWARE, ORDINARY }

    private fun rank(candidate: Candidate): Rank {
        val pkg = candidate.component.packageName
        return when {
            candidate.signedLikeFirmware -> Rank.SIGNED_LIKE_FIRMWARE
            !candidate.system -> Rank.ORDINARY
            pkg == KNOWN_PACKAGE -> Rank.OLD_PLAYER
            pkg.startsWith(ZIDOO_PREFIX) -> Rank.ZIDOOS
            else -> Rank.SYSTEM
        }
    }

    /**
     * Another app may start it: exported, and under no permission this one lacks. Android may list
     * one that is not among those that answer an intent, and starting it throws (SecurityException):
     * every film, a plain one too, would then find no player.
     */
    private fun startable(info: ActivityInfo) =
        info.exported && info.permission.let { it == null || context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun candidate(info: ActivityInfo, device: Device): Candidate {
        val system = info.applicationInfo.isSystem()
        return Candidate(info.component(), system, signedLikeFirmware = !system && signedLikeFirmware(info.packageName, device))
    }

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

        /**
         * What a player is asked to open: a video by its type, at the addresses the app hands over, and
         * at those the Z1000 Pro's player answers, read on the device: http, https, file and content
         * only, never smb nor nfs, which it is handed by name. A firmware's player under another name
         * answers as it does, if at all. From Android 11, the manifest's <queries> must declare each,
         * or no app answers it.
         */
        internal fun probes() = listOf(Intent(Intent.ACTION_VIEW).setType(VIDEO)) + listOf(
            "nfs://nas/film.mkv",
            "smb://nas/film.mkv",
            "http://nas/film.mkv",
            "https://nas/film.mkv",
            "file:///storage/film.mkv",
            "content://media/external/video/media/1",
        ).map { Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(it), VIDEO) }

        private const val VIDEO = "video/*"

        /** Filters that say they are a default, and the others. */
        private val QUERY_FLAGS = listOf(PackageManager.MATCH_DEFAULT_ONLY, 0)
        private val FILM_WORDS = Regex("(?i)movie|video|player")
    }
}
