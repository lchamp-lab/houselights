package app.houselights.player

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import app.houselights.core.SigningCertificates
import app.houselights.core.isWebUrl

/** The device as its build names it ([Build]), read-only firmware properties no app can change. */
data class DeviceIdentity(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val product: String,
    val device: String,
    val fingerprint: String,
    /** "release-keys", or "test-keys" for a build signed with AOSP's public keys. */
    val tags: String,
) {
    /** A test-keys build: a certificate shared with the firmware's apps proves nothing. */
    val testKeys: Boolean get() = tags.contains("test-keys")

    /** "ZIDOO Z9X Pro": the maker is not repeated when the model already names it. */
    val name: String
        get() = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model".trim()

    /** Any field names Zidoo: a Realtek build may keep the chip maker's name in some of them. */
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
 * A player playback refuses as changed, as the trust sheet and About name it ([ZidooPlayer.facts]): its [label] and
 * [version] as the app itself declares them, so an update may claim any, and the day Android last installed it
 * ([updatedAt], ms since the epoch), which no update chooses; whether it is the firmware's own player updated ([system],
 * on a test-keys build) rather than an app installed in its place, and one signed otherwise under the package passwords
 * last went to ([otherSignature]). Then what trusting it pins: its [certificate]'s SHA-256 and, for the firmware's
 * player updated, its [build] (versionCode@lastUpdateTime). An unknown label or version is null.
 */
data class PlayerFacts(
    val packageName: String,
    val label: String?,
    val version: String?,
    val updatedAt: Long,
    val system: Boolean,
    val otherSignature: Boolean,
    val certificate: String,
    val build: String?,
)

/** What About shows of the player, from the decision playback takes ([ZidooPlayer.choose]). */
sealed interface PlayerStatus {
    data class Detected(val packageName: String) : PlayerStatus

    /** Found, but no password goes to it, for [reason]; one [ZidooPlayer.Decision.CHANGED] with what names it ([facts]). */
    data class Unrecognized(val packageName: String, val reason: ZidooPlayer.Decision, val facts: PlayerFacts? = null) : PlayerStatus

    data object NotDetected : PlayerStatus
}

/**
 * Finds the player a film is handed to, and whether a password may go to it ([choose]). The Z1000 Pro
 * plays with [KNOWN_ACTIVITY]; newer firmware may use another, unpublished app, looked for among the
 * firmware's video apps on a Zidoo only. Elsewhere only the known one is taken, never trusted ([Decision]).
 * Each choice asks Android's package manager, a few dozen calls to another process: About and the tracks check
 * ask off the main thread; playback's hand-off ([PlaybackManager.intentFor], the room already dark) and the
 * trust sheet ([app.houselights.ui.settings.ZidooTrust]), both rare, still ask on it.
 */
class ZidooPlayer(
    private val context: Context,
    private val identity: () -> DeviceIdentity = DeviceIdentity::current,
) {
    private val packages: PackageManager get() = context.packageManager

    /**
     * The player a secret last went to ([remember]), or the user trusted ([trust]): its package, its certificate, and its
     * build where it is pinned with it ([KEY_BUILD]).
     */
    private val trusted = context.getSharedPreferences("player", Context.MODE_PRIVATE)

    /**
     * Zidoo's system apps, read once: Android lists every app to find them, and only a firmware update, which restarts
     * the app, changes them. The players are read at each choice.
     */
    private val zidooApps: List<String> by lazy { systemZidooApps() }

    /**
     * An app that opens a video; [streams] when it opens shares or streams, not only files (a poster wall); [updated], a
     * system app an update was installed over ([ApplicationInfo.FLAG_UPDATED_SYSTEM_APP]).
     */
    data class Candidate(
        val component: ComponentName,
        val system: Boolean,
        val signedLikeFirmware: Boolean = false,
        val streams: Boolean = true,
        val updated: Boolean = false,
    )

    data class Device(val identity: DeviceIdentity, val zidooApps: List<String>) {
        /** A Zidoo: its build says so, or it has a Zidoo system app, which no ordinary app can fake. */
        val zidoo: Boolean get() = identity.namesZidoo || zidooApps.isNotEmpty()
    }

    /**
     * What [choose] found: the [player] among [candidates], its [certificate]'s SHA-256, the [decision], and the
     * player's [build] (versionCode@lastUpdateTime) where it is pinned with it: the firmware's player updated, on a
     * test-keys build ([pinsBuild]); else null.
     */
    data class Choice(
        val player: Candidate?,
        val candidates: List<Candidate>,
        val device: Device,
        val certificate: String?,
        val decision: Decision,
        val build: String? = null,
    )

    /** Whether a password may go to the player found, and why. */
    enum class Decision(val trusted: Boolean) {
        NO_PLAYER(false),

        /** Not a Zidoo: no password ever leaves the app. */
        NOT_A_ZIDOO(false),

        /**
         * A system app, which only an update signed with its key replaces; on a test-keys build, whose keys anyone has,
         * one never updated alone: an update installed over it goes through the pin, its build with it (#58).
         */
        FIRMWARE(true),

        /** An ordinary app signed with a Zidoo system app's certificate. */
        SIGNED_LIKE_FIRMWARE(true),

        /**
         * Neither, but the player a password last went to, or the user trusted, unchanged since: its package and
         * certificate, and the firmware's player updated its build too.
         */
        REMEMBERED(true),

        /** Neither, and no password has gone to any player yet: trusted on first use ([remember]), with nothing to compare. */
        FIRST_USE(true),

        /**
         * Neither, and not the remembered player: another app, the same with another certificate, or the firmware's
         * player updated since it was pinned; trusted again only by the user ([trust]).
         */
        CHANGED(false),

        /** Its certificate cannot be read, so a change would go unnoticed. */
        NO_CERTIFICATE(false),
    }

    /**
     * The remembered player while it keeps its certificate; else the first of [preferred] among [KNOWN_ACTIVITY]
     * (if really resolved: a component intent returns it unchecked) and, on a Zidoo, the firmware's video apps.
     */
    fun choose(): Choice {
        val device = device()
        val found = listOfNotNull(known(device)) + if (device.zidoo) discover(device) else emptyList()
        val defaults = if (device.zidoo) defaults() else emptySet()
        val candidates = found.distinctBy { it.component }.sortedWith(preferred(defaults))
        val player = candidates.firstOrNull(::remembered) ?: candidates.firstOrNull()
        val info = player?.let { signed(it.component.packageName) }
        val certificate = certificateOf(info)
        val build = if (player != null && info != null && pinsBuild(player, device)) buildOf(info) else null
        adoptBuild(player, certificate, build)
        return Choice(player, candidates, device, certificate, decide(player, device, certificate, build), build)
    }

    /**
     * Whether [player] is pinned with its build: the firmware's player updated on a test-keys build, whose keys anyone
     * has, so that anyone may sign an update Android installs over it (#58).
     */
    private fun pinsBuild(player: Candidate, device: Device) = player.system && player.updated && device.identity.testKeys

    /**
     * A pin 0.3 took, which has no build ([KEY_BUILD] absent), takes [player]'s build once, unasked, when it is the same
     * package and certificate: 0.3 trusted every system player of the firmware, updated or not. From then on a pin
     * without a build is a player never updated, whose first update is a change ([Decision.CHANGED]).
     */
    private fun adoptBuild(player: Candidate?, certificate: String?, build: String?) {
        val pkg = player?.component?.packageName ?: return
        if (certificate == null || trusted.contains(KEY_BUILD) || trusted.getString(KEY_PACKAGE, null) != pkg) return
        if (trusted.getString(KEY_CERTIFICATE, null) != certificate) return
        trusted.edit { putString(KEY_BUILD, build.orEmpty()) }
        Log.i(TAG, "The player 0.3 trusted, $pkg, keeps its build: ${build ?: "none, never updated"}")
    }

    /**
     * « Faire confiance à ce lecteur »: pins the player the trust sheet named ([seen]), its package, certificate and
     * build, if it is still the one playback refuses as changed; false, nothing pinned, when it changed meanwhile.
     * Logged as a warning, without secrets; only the app's own sheet asks it, no intent reaches it.
     */
    fun trust(seen: PlayerFacts): Boolean {
        val choice = choose()
        val pkg = choice.player?.component?.packageName
        val certificate = choice.certificate
        if (choice.decision != Decision.CHANGED || pkg != seen.packageName || certificate != seen.certificate) return false
        if (choice.build != seen.build) return false
        trusted.edit { putString(KEY_PACKAGE, pkg).putString(KEY_CERTIFICATE, certificate).putString(KEY_BUILD, choice.build.orEmpty()) }
        Log.w(TAG, "Trusted by the user: $pkg, certificate SHA-256 $certificate, build ${choice.build}")
        return true
    }

    /**
     * What the trust sheet names of [choice]'s player while playback refuses it as changed: its name and version as it
     * declares them, the day Android last installed it, whether it is the firmware's player updated or signed otherwise
     * than the one passwords last went to; null for any other decision, or a player Android no longer gives.
     */
    fun facts(choice: Choice): PlayerFacts? {
        val player = choice.player?.takeIf { choice.decision == Decision.CHANGED } ?: return null
        val certificate = choice.certificate ?: return null
        val pkg = player.component.packageName
        val info = signed(pkg) ?: return null
        val label = info.applicationInfo?.loadLabel(packages)?.toString()?.trim()
        return PlayerFacts(
            packageName = pkg,
            label = label?.takeIf { it.isNotEmpty() && it != pkg },
            version = info.versionName?.trim()?.takeIf { it.isNotEmpty() },
            updatedAt = info.lastUpdateTime,
            system = player.system,
            otherSignature = trusted.getString(KEY_PACKAGE, null) == pkg && trusted.getString(KEY_CERTIFICATE, null) != certificate,
            certificate = certificate,
            build = choice.build,
        )
    }

    /** [candidate] is the remembered player, same certificate: an impostor under its name does not come first. */
    private fun remembered(candidate: Candidate): Boolean {
        val pkg = candidate.component.packageName
        return pkg == trusted.getString(KEY_PACKAGE, null) && certificate(pkg) == trusted.getString(KEY_CERTIFICATE, null)
    }

    /**
     * Whether [player] opens a video at [uri]: the old player opens any address; another gets a stream only
     * if it answers its address ([probe]). Shares are not probed: the old player opens them without answering.
     */
    fun opens(player: Candidate, uri: String): Boolean {
        if (player.component.packageName == KNOWN_PACKAGE || !isWebUrl(uri)) return true
        return packages.queryIntentActivities(probe(uri.toUri()), 0).any { it.activityInfo?.component() == player.component }
    }

    fun status(): PlayerStatus {
        val choice = choose()
        val pkg = choice.player?.component?.packageName ?: return PlayerStatus.NotDetected
        return if (choice.decision.trusted) PlayerStatus.Detected(pkg) else PlayerStatus.Unrecognized(pkg, choice.decision, facts(choice))
    }

    /**
     * Remembers [choice]'s player once it started with a password, so no other app or certificate gets one
     * after it; the firmware player too, so a later replacement is not a first use, and its first update on a
     * test-keys build is a change: pinned without a build, it is a player never updated.
     */
    fun remember(choice: Choice) {
        val pkg = choice.player?.component?.packageName ?: return
        val certificate = choice.certificate ?: return
        if (!choice.decision.trusted) return
        val build = choice.build.orEmpty()
        val same = trusted.getString(KEY_PACKAGE, null) == pkg && trusted.getString(KEY_CERTIFICATE, null) == certificate &&
            trusted.getString(KEY_BUILD, null) == build
        if (same) return
        trusted.edit { putString(KEY_PACKAGE, pkg).putString(KEY_CERTIFICATE, certificate).putString(KEY_BUILD, build) }
    }

    /** [choice] in one log line, without secrets: decision and why, player, certificate, candidates, build. */
    fun describe(choice: Choice): String {
        val candidates = choice.candidates.joinToString("; ") {
            val kind = when {
                it.system && it.updated -> "system, updated"
                it.system -> "system"
                it.signedLikeFirmware -> "ordinary, signed like the firmware"
                else -> "ordinary"
            }
            "${it.component.flattenToShortString()} $kind, installed by ${installer(it.component.packageName)}"
        }
        val build = choice.build?.let { ", build $it" }.orEmpty()
        return "${choice.decision}: ${why(choice)}; player ${choice.player?.component?.flattenToShortString()}, " +
            "certificate SHA-256 ${choice.certificate}$build; candidates [$candidates]; ${choice.device.identity}; " +
            "system apps of Zidoo's ${choice.device.zidooApps}"
    }

    private fun why(choice: Choice): String = when (choice.decision) {
        Decision.NO_PLAYER -> if (choice.device.zidoo) "no app of the firmware's opens a video" else "not a Zidoo, nor $KNOWN_PACKAGE"
        Decision.NOT_A_ZIDOO -> "no field of the build names Zidoo, and no system app of Zidoo's is seen"
        Decision.FIRMWARE -> "a system app" + if (choice.device.identity.testKeys) ", never updated" else ""
        Decision.SIGNED_LIKE_FIRMWARE -> "signed like a system app of Zidoo's"
        Decision.REMEMBERED ->
            "the player a secret last went to, with the same certificate" + if (choice.build != null) " and build" else ""
        Decision.FIRST_USE ->
            "no secret went to a player yet" +
                if (choice.device.identity.testKeys) "; test-keys: a certificate shared with the firmware proves nothing" else ""
        Decision.CHANGED -> "a secret last went to ${trusted.getString(KEY_PACKAGE, null)}, " +
            "certificate SHA-256 ${trusted.getString(KEY_CERTIFICATE, null)}, build ${trusted.getString(KEY_BUILD, null)}"
        Decision.NO_CERTIFICATE -> "its certificate cannot be read"
    }

    /** Who installed [pkg]: the firmware's updater, a store, or null. */
    private fun installer(pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packages.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION") // Before Android 11, which brought getInstallSourceInfo.
            packages.getInstallerPackageName(pkg)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: IllegalArgumentException) {
        // Android 9 throws this for a package that is not installed.
        null
    }

    private fun decide(player: Candidate?, device: Device, certificate: String?, build: String?): Decision = when {
        player == null -> Decision.NO_PLAYER
        !device.zidoo -> Decision.NOT_A_ZIDOO
        player.system && !pinsBuild(player, device) -> Decision.FIRMWARE
        player.signedLikeFirmware -> Decision.SIGNED_LIKE_FIRMWARE
        certificate == null -> Decision.NO_CERTIFICATE
        else -> pinned(player.component.packageName, certificate, build)
    }

    /**
     * [pkg] against the pin: none, a first use; the same package and certificate, and the same [build] for the firmware's
     * player updated (a pin without one, of a player never updated, is not), remembered; anything else, changed.
     */
    private fun pinned(pkg: String, certificate: String, build: String?): Decision {
        val pin = trusted.getString(KEY_PACKAGE, null) ?: return Decision.FIRST_USE
        val same = pin == pkg && trusted.getString(KEY_CERTIFICATE, null) == certificate &&
            (build == null || trusted.getString(KEY_BUILD, null) == build)
        return if (same) Decision.REMEMBERED else Decision.CHANGED
    }

    /** [pkg] shares a Zidoo system app's certificate; never on a test-keys build (Z1000 Pro): its keys are public. */
    private fun signedLikeFirmware(pkg: String, device: Device) =
        !device.identity.testKeys && device.zidooApps.any { packages.checkSignatures(pkg, it) == PackageManager.SIGNATURE_MATCH }

    /** SHA-256 of [pkg]'s signing certificates in hex, as apksigner prints it; null when unreadable. */
    private fun certificate(pkg: String): String? = certificateOf(signed(pkg))

    /** [pkg] as Android gives it, with its signing certificates; null when it is not installed. */
    private fun signed(pkg: String): PackageInfo? = try {
        packages.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun certificateOf(info: PackageInfo?): String? =
        SigningCertificates.of(info).signers.takeIf { it.isNotEmpty() }?.joinToString(",")

    /** The build an update installed: its versionCode, and when Android installed it, which an update cannot choose. */
    private fun buildOf(info: PackageInfo): String = "${info.longVersionCode}@${info.lastUpdateTime}"

    private fun device(): Device = Device(identity(), zidooApps)

    // From Android 11, only the apps visible to this one (see <queries> in the manifest): never every app.
    @SuppressLint("QueryPermissionsNeeded")
    private fun systemZidooApps(): List<String> = packages.getInstalledApplications(0)
        .filter { it.isSystem() && it.packageName.startsWith(ZIDOO_PREFIX) }
        .map { it.packageName }
        .sorted()

    private fun known(device: Device): Candidate? {
        val info = packages.resolveActivity(Intent().setComponent(ComponentName(KNOWN_PACKAGE, KNOWN_ACTIVITY)), 0)?.activityInfo
        return info?.takeIf(::startable)?.let { candidate(it, device) }
    }

    /**
     * The firmware's activities that open a video by type or at any [probes] scheme (a filter naming a scheme
     * matches no intent without one): system apps, apps signed like them or under a Zidoo name; never ours.
     */
    private fun discover(device: Device): List<Candidate> {
        val answered = probes().flatMapIndexed { index, probe ->
            QUERY_FLAGS.flatMap { packages.queryIntentActivities(probe, it) }.mapNotNull { it.activityInfo }.map { it to index }
        }.filter { (info, _) -> startable(info) && !info.packageName.isOurs() }
        val streaming = answered.filter { (_, probe) -> probe < FIRST_FILE_PROBE }.mapTo(HashSet()) { (info, _) -> info.component() }
        return answered.map { it.first }
            .distinctBy { it.component() }
            .map { candidate(it, device).copy(streams = it.component() in streaming) }
            .filter { it.system || it.signedLikeFirmware || it.component.packageName.startsWith(ZIDOO_PREFIX) }
    }

    /** What the system opens a video with unasked, for any of [probes]. */
    private fun defaults(): Set<ComponentName> =
        probes().mapNotNull { packages.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo }
            .map { it.component() }
            .toSet()

    /**
     * Player order, unfakeable first: system apps and apps signed like them, then ordinary apps. Within
     * each, streaming players first (a Zidoo app that opens only files cannot play a share), then by rank,
     * the system default, an activity named for films; by name last, so the same apps give the same player.
     */
    private fun preferred(defaults: Set<ComponentName>) =
        compareBy<Candidate> { rank(it) >= Rank.SIGNED_LIKE_FIRMWARE }
            .thenByDescending { it.streams }
            .thenBy { rank(it) }
            .thenByDescending { it.component in defaults }
            .thenByDescending { FILM_WORDS.containsMatchIn(it.component.className.substringAfterLast('.')) }
            .thenBy { it.component.packageName }
            .thenBy { it.component.className }

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

    /** Exported, needing no permission we lack: Android may list an activity that throws SecurityException on start. */
    private fun startable(info: ActivityInfo) =
        info.exported && info.permission.let { it == null || context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun candidate(info: ActivityInfo, device: Device): Candidate {
        val system = info.applicationInfo.isSystem()
        return Candidate(
            info.component(),
            system,
            signedLikeFirmware = !system && signedLikeFirmware(info.packageName, device),
            updated = system && info.applicationInfo.isUpdated(),
        )
    }

    private fun ActivityInfo.component() = ComponentName(packageName, name)

    private fun ApplicationInfo?.isSystem() = this != null && flags and ApplicationInfo.FLAG_SYSTEM != 0

    private fun ApplicationInfo?.isUpdated() = this != null && flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0

    private fun String.isOurs() = this == OWN_PACKAGE || startsWith("$OWN_PACKAGE.")

    companion object {
        /** The Z1000 Pro's player. */
        const val KNOWN_PACKAGE = "com.android.gallery3d"
        const val KNOWN_ACTIVITY = "com.android.gallery3d.app.MovieActivity"
        private const val ZIDOO_PREFIX = "com.zidoo."

        /** Whether [player] takes audio_idx and subtitle_idx: checked on the known player only; another may number otherwise. */
        fun takesTracks(player: ComponentName?): Boolean = player?.packageName == KNOWN_PACKAGE

        private const val KEY_PACKAGE = "trusted_package"
        private const val KEY_CERTIFICATE = "trusted_certificate"

        /**
         * The pinned player's build, versionCode@lastUpdateTime, for the firmware's player updated on a test-keys build;
         * empty for any other player, or one never updated; absent from a pin 0.3 took ([adoptBuild]).
         */
        private const val KEY_BUILD = "trusted_build"
        private const val TAG = "ZidooPlayer"

        /** This app's package, also the prefix of its debug and bench builds'. */
        private const val OWN_PACKAGE = "app.houselights"

        /**
         * Probe intents: a video by type and at each scheme the app hands over. The Z1000 Pro's player
         * answers http, file and content, never smb or nfs, which it gets by component name. From
         * Android 11 the manifest's <queries> must declare each, or no app answers it.
         */
        internal fun probes() = listOf(Intent(Intent.ACTION_VIEW).setType(VIDEO)) + listOf(
            "nfs://nas/film.mkv",
            "smb://nas/film.mkv",
            "http://nas/film.mkv",
            "https://nas/film.mkv",
            "file:///storage/film.mkv",
            "content://media/external/video/media/1",
        ).map { Intent(Intent.ACTION_VIEW).setDataAndType(it.toUri(), VIDEO) }

        /** Index of the first file or content probe in [probes]; earlier ones are share or stream addresses. */
        private const val FIRST_FILE_PROBE = 5

        /** Probe for [opens]: [uri]'s scheme, host and port only; user info, path and query may hold secrets. */
        internal fun probe(uri: Uri): Intent {
            val authority = uri.host.orEmpty() + if (uri.port >= 0) ":${uri.port}" else ""
            return Intent(Intent.ACTION_VIEW).setDataAndType(Uri.Builder().scheme(uri.scheme).encodedAuthority(authority).build(), VIDEO)
        }

        private const val VIDEO = "video/*"

        /** Default filters first, then the others. */
        private val QUERY_FLAGS = listOf(PackageManager.MATCH_DEFAULT_ONLY, 0)
        private val FILM_WORDS = Regex("(?i)movie|video|player")
    }
}
