package app.houselights.update

import android.content.Context
import android.content.pm.PackageManager
import app.houselights.BuildConfig
import app.houselights.core.SigningCertificates

/** When the updater runs: in the project's release alone, as built and as signed. */
object UpdaterAvailability {
    /** The release's package: debug and bench builds have their own beside it. */
    const val RELEASE_PACKAGE = "app.houselights"

    /**
     * Whether a build with the updater ([built]), of [packageName], whose [signers] and [history] are these, updates
     * itself: only the release package signed with [pinned]. CI's release build (the runner's debug key), the builds
     * :baselineprofile makes, a fork's or one signed for F-Droid never do, nor debug and bench.
     */
    fun available(built: Boolean, packageName: String, signers: List<String>, history: List<String>, pinned: String): Boolean =
        built && packageName == RELEASE_PACKAGE && SigningCertificates.signedBy(pinned, signers, history)
}

/** The running app as the updater reads it. */
interface InstalledApp {
    /** Whether it updates itself ([UpdaterAvailability]). */
    val available: Boolean
    val packageName: String

    /** Its versionCode as Android has it installed: the release's is set at build time, never read from BuildConfig. */
    fun versionCode(): Long

    /** Its full version name (0.4.0, 0.4.1-beta.2). */
    fun versionName(): String
}

/** This app, as its package manager has it installed. */
class ThisApp(
    private val context: Context,
    private val built: Boolean = BuildConfig.UPDATER,
    private val pinned: String = BuildConfig.RELEASE_CERT_SHA256,
) : InstalledApp {
    override val packageName: String get() = context.packageName

    override val available: Boolean by lazy {
        // Read only for a build with the updater: the others never ask.
        built && run {
            val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val (signers, history) = SigningCertificates.of(info)
            UpdaterAvailability.available(built, packageName, signers, history, pinned)
        }
    }

    override fun versionCode(): Long = context.packageManager.getPackageInfo(packageName, 0).longVersionCode

    override fun versionName(): String = context.packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
}
