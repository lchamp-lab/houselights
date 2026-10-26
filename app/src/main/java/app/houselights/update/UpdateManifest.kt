package app.houselights.update

import android.util.Log
import app.houselights.core.lenientJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * A release's update.json, written by the release workflow from the APK it built (CONTRIBUTING, Releases): the
 * version, the APK's name, size and SHA-256, and what is new. [certSha256] says which key signed it, for whoever reads
 * the file: the app trusts only the APK's own signer ([UpdateVerifier]).
 */
@Serializable
data class UpdateManifest(
    val schema: Int,
    val tag: String,
    val versionName: String,
    val versionCode: Long,
    val prerelease: Boolean,
    val minSdk: Int,
    val apk: ManifestApk,
    val certSha256: String,
    val notes: Map<String, String> = emptyMap(),
) {
    companion object {
        /** The manifests this version reads; a newer schema is a release it cannot ([UpdateFailure.UNREADABLE]). */
        const val SCHEMA = 1
        const val FILE = "update.json"
    }
}

@Serializable
data class ManifestApk(val name: String, val size: Long, val sha256: String)

/** A release's version, from its tag (v0.4.0, v0.4.1-beta.2): a final ranks above its betas. */
data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int, val beta: Int?) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.beta ?: Int.MAX_VALUE })

    companion object {
        private val TAG = Regex("""v(\d{1,4})\.(\d{1,4})\.(\d{1,4})(?:-beta\.(\d{1,4}))?""")

        /** The version [tag] names, or null for a tag no release of the project carries. */
        fun of(tag: String): ReleaseVersion? {
            // Its numbers, a beta's last (none for a final).
            val numbers = TAG.matchEntire(tag)?.groupValues?.drop(1)?.map(String::toIntOrNull) ?: return null
            val (major, minor, patch) = numbers.map { it ?: 0 }
            return ReleaseVersion(major, minor, patch, beta = numbers.last())
        }
    }
}

/** A release worth reading the manifest of: published, of the channel, with its APK and manifest under GitHub's downloads. */
data class Candidate(val release: ListedRelease, val version: ReleaseVersion, val apk: ListedAsset, val manifest: ListedAsset)

/** What a release's manifest says, read against GitHub's listing. */
sealed interface Verdict {
    data class Valid(val offer: UpdateOffer) : Verdict

    /** Written in a schema, or a shape, this version does not read. */
    data class Unreadable(val why: String) : Verdict

    /** Disagreeing with its listing or the device: skipped, and logged. */
    data class Invalid(val why: String) : Verdict
}

/** What the releases hold for the installed version. */
sealed interface Found {
    data class Offer(val offer: UpdateOffer) : Found

    /** Nothing newer than the installed version. */
    data object UpToDate : Found

    /** A newer release exists that this version cannot read: the manual way is said. */
    data object Unreadable : Found
}

/**
 * Chooses the release to offer from GitHub's list: ordered by tag (a final above its betas), the newest of the
 * channel whose manifest agrees with its listing, offered when its versionCode is above the installed one (the release
 * workflow keeps versionCodes growing with the tags).
 */
class ReleaseChoice(
    /** Where the release's files must be served ([GithubReleases.downloads]). */
    private val downloads: (tag: String) -> String,
    private val pinned: String,
    private val sdk: Int,
) {
    /** The releases of the channel ([beta] includes pre-releases) worth reading, newest first, three at most. */
    fun candidates(listed: List<ListedRelease>, beta: Boolean): List<Candidate> = listed.asSequence()
        .filter { !it.draft && (beta || !it.prerelease) }
        .mapNotNull(::candidate)
        .sortedByDescending { it.version }
        .take(TRIED)
        .toList()

    private fun candidate(release: ListedRelease): Candidate? {
        val version = ReleaseVersion.of(release.tag) ?: return null
        // A pre-release is a beta, and only a beta is a pre-release.
        if (release.prerelease != (version.beta != null)) return null
        // Each file served under the release's own downloads, nowhere else.
        val under = downloads(release.tag)
        fun asset(name: String) = release.assets.firstOrNull { it.name == name && it.url == under + name }
        val apk = asset(apkName(release.tag.removePrefix("v")))
        val manifest = asset(UpdateManifest.FILE)
        return if (apk != null && manifest != null) Candidate(release, version, apk, manifest) else null
    }

    /** What [text], [candidate]'s manifest, offers. */
    fun read(candidate: Candidate, text: String): Verdict {
        val manifest = decoded(text)
        val why = manifest?.let { disagreement(candidate, it) }
        return when {
            manifest == null -> Verdict.Unreadable("not a manifest")
            manifest.schema != UpdateManifest.SCHEMA -> Verdict.Unreadable("schema ${manifest.schema}")
            why != null -> Verdict.Invalid(why)
            else -> Verdict.Valid(offerOf(candidate, manifest))
        }
    }

    /** [text] as a manifest; null when it is none this version reads (its cause logged, never its text). */
    private fun decoded(text: String): UpdateManifest? = try {
        lenientJson.decodeFromString(UpdateManifest.serializer(), text)
    } catch (e: SerializationException) {
        Log.w(TAG, "Manifest unreadable: ${e.javaClass.simpleName}")
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Manifest unreadable: ${e.javaClass.simpleName}")
        null
    }

    private fun offerOf(candidate: Candidate, manifest: UpdateManifest): UpdateOffer {
        if (manifest.certSha256 != pinned) Log.i(TAG, "${manifest.tag} names another certificate; its APK's signer decides")
        return UpdateOffer(
            tag = manifest.tag,
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            prerelease = manifest.prerelease,
            size = manifest.apk.size,
            sha256 = manifest.apk.sha256,
            url = candidate.apk.url,
            notes = manifest.notes.mapValues { it.value.take(UpdateOffer.NOTES_MAX) },
        )
    }

    /** Where [manifest] disagrees with [candidate]'s listing, or does not suit this device; null when it does not. */
    @Suppress("CyclomaticComplexMethod") // One rule a line, each the release's file to trust.
    private fun disagreement(candidate: Candidate, manifest: UpdateManifest): String? {
        val release = candidate.release
        val apk = manifest.apk
        val digest = candidate.apk.digest
        return when {
            manifest.tag != release.tag -> "tag ${manifest.tag}"
            manifest.versionName != release.tag.removePrefix("v") -> "versionName ${manifest.versionName}"
            manifest.prerelease != release.prerelease -> "prerelease ${manifest.prerelease}"
            manifest.versionCode <= 0 -> "versionCode ${manifest.versionCode}"
            apk.name != candidate.apk.name -> "APK ${apk.name}"
            apk.size != candidate.apk.size -> "size ${apk.size}, listed ${candidate.apk.size}"
            apk.size !in 1..MAX_APK_BYTES -> "size ${apk.size}"
            !SHA256.matches(apk.sha256) -> "SHA-256 ${apk.sha256}"
            digest != null && digest != "sha256:${apk.sha256}" -> "SHA-256, GitHub's $digest"
            manifest.minSdk > sdk -> "minSdk ${manifest.minSdk}"
            else -> null
        }
    }

    /** What the releases hold above [installedCode], reading each candidate's manifest through [manifest]. */
    suspend fun pick(listed: List<ListedRelease>, beta: Boolean, installedCode: Long, manifest: suspend (Candidate) -> String?): Found {
        var unreadable = false
        for (candidate in candidates(listed, beta)) {
            // Unknown, as when only what was kept is read: no answer rather than a guess.
            val text = manifest(candidate) ?: return Found.UpToDate
            when (val verdict = read(candidate, text)) {
                is Verdict.Valid ->
                    return if (verdict.offer.versionCode > installedCode) {
                        Found.Offer(verdict.offer)
                    } else if (unreadable) {
                        Found.Unreadable
                    } else {
                        Found.UpToDate
                    }
                is Verdict.Unreadable -> {
                    unreadable = true
                    Log.w(TAG, "${candidate.release.tag}: manifest unreadable, ${verdict.why}")
                }
                is Verdict.Invalid -> Log.w(TAG, "${candidate.release.tag} skipped: its manifest says another ${verdict.why}")
            }
        }
        return if (unreadable) Found.Unreadable else Found.UpToDate
    }

    companion object {
        /** The release's APK: Houselights-0.4.1.apk, Houselights-0.4.1-beta.2.apk. */
        fun apkName(versionName: String) = "Houselights-$versionName.apk"

        /** An APK larger than this is no release of the project (6.4 MB in 0.3.0). */
        const val MAX_APK_BYTES = 64L shl 20

        /** Manifests read at most a check: the newest of the channel, then two older ones should it disagree. */
        private const val TRIED = 3
        private val SHA256 = Regex("[0-9a-f]{64}")
        private const val TAG = "Updates"
    }
}
