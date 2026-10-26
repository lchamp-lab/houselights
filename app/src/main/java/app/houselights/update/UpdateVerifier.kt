package app.houselights.update

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import app.houselights.core.SigningCertificates
import java.io.File

/** What Android reads of an APK: its package, version, and the SHA-256 of its signers and signing history. */
data class ArchiveFacts(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val signers: List<String>,
    /** The single signer's history (v3 key rotation), oldest first; empty without one. */
    val history: List<String> = emptyList(),
)

/** Reads an APK as Android would install it; null when Android cannot. */
fun interface ArchiveReader {
    fun read(file: File): ArchiveFacts?
}

/**
 * The APK as Android's package manager reads it. Both signature flags: Android 9 reads an archive's certificates only
 * for GET_SIGNATURES, and fills its signing history only for GET_SIGNING_CERTIFICATES.
 */
class PackageArchive(private val archiveInfo: (path: String, flags: Int) -> PackageInfo?) : ArchiveReader {
    // The flags as an Int: PackageInfoFlags is Android 13's.
    @Suppress("DEPRECATION")
    constructor(packages: PackageManager) : this({ path, flags -> packages.getPackageArchiveInfo(path, flags) })

    override fun read(file: File): ArchiveFacts? {
        val info = archiveInfo(file.path, FLAGS) ?: return null
        val signing = info.signingInfo
        @Suppress("DEPRECATION") // The archive's signers where Android gave no signing info.
        val signers = SigningCertificates.sha256(signing?.apkContentsSigners ?: info.signatures)
        val history = signing?.takeUnless { it.hasMultipleSigners() }?.signingCertificateHistory.orEmpty()
            .map { SigningCertificates.sha256(it) }
        return ArchiveFacts(info.packageName, info.longVersionCode, info.versionName, signers, history)
    }

    companion object {
        @Suppress("DEPRECATION") // GET_SIGNATURES, which Android 9 needs for an archive.
        const val FLAGS = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
    }
}

/**
 * Whether a downloaded APK is the release offered, after its size and SHA-256 ([ApkDownload]): the app's own package,
 * the manifest's versionCode above the installed one, its versionName the tag's (with "-" only for a pre-release), and
 * signed with the project's key ([pinned]) alone, or by a rotated key whose history holds it. Android would refuse
 * another key or a downgrade anyway; this says why before it is asked.
 */
class UpdateVerifier(private val archive: ArchiveReader, private val pinned: String) {
    /** Throws an [UpdateException] unless [apk] is [offer] for [packageName], newer than [installedCode]. */
    fun verify(apk: File, offer: UpdateOffer, packageName: String, installedCode: Long) {
        val facts = archive.read(apk) ?: fail(UpdateFailure.NOT_GENUINE, "Android cannot read the archive")
        val tagged = offer.tag.removePrefix("v")
        val refusal = when {
            facts.packageName != packageName -> "package ${facts.packageName}"
            !SigningCertificates.signedBy(pinned, facts.signers, facts.history) -> "signed by ${facts.signers}"
            facts.versionCode != offer.versionCode -> "versionCode ${facts.versionCode}, manifest ${offer.versionCode}"
            facts.versionName != offer.versionName || offer.versionName != tagged -> "versionName ${facts.versionName}"
            '-' in offer.versionName && !offer.prerelease -> "versionName ${offer.versionName} on a release"
            else -> null
        }
        if (refusal != null) fail(UpdateFailure.NOT_GENUINE, "${offer.tag} refused: $refusal")
        if (facts.versionCode <= installedCode) fail(UpdateFailure.NOT_NEWER, "${facts.versionCode}, installed $installedCode")
    }
}
