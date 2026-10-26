package app.houselights.update

import app.houselights.core.Typeset
import kotlinx.serialization.Serializable
import java.io.IOException

/** Why an update did not go through, each said with what to do (the sheet's words, ui/settings/UpdateWords.kt). */
enum class UpdateFailure {
    /** GitHub does not answer: no network, or no answer in time. */
    OFFLINE,

    /** TLS refused GitHub's certificate: the device's date and time, most often. */
    CERTIFICATE,

    /** GitHub asks to wait ([UpdateState.waitUntil]): too many requests from this network. */
    BUSY,

    /** GitHub answered something else than what it serves. */
    GITHUB_FAILED,

    /** The newest release's manifest is one this version cannot read. */
    UNREADABLE,

    /** Not enough room on the device for the download ([UpdateOffer.spaceNeeded]). */
    STORAGE,

    /** The download stopped before its end. */
    DOWNLOAD_CUT,

    /** The file received is not the one the release names: its size or SHA-256. */
    CORRUPT,

    /** The file is not the project's release: another package, version or signing key. */
    NOT_GENUINE,

    /** The version is not newer than the one installed. */
    NOT_NEWER,

    /** Android refused to let the app install. */
    BLOCKED,

    /** The installed app is not signed as the releases are. */
    CONFLICT,

    /** Android says this version does not suit the device. */
    INCOMPATIBLE,

    /** Android did not install it, for another reason (the log says which). */
    INSTALL_FAILED,
}

/** A failure the updater named itself, its [message] for the log only. */
class UpdateException(val failure: UpdateFailure, message: String, cause: Throwable? = null) : IOException(message, cause)

/** Stops what is under way with [failure]; [message] goes to the log only. */
internal fun fail(failure: UpdateFailure, message: String, cause: Throwable? = null): Nothing =
    throw UpdateException(failure, message, cause)

/** A release offered: newer than the installed version, its manifest checked against GitHub's listing. */
data class UpdateOffer(
    val tag: String,
    val versionName: String,
    val versionCode: Long,
    val prerelease: Boolean,
    /** The APK's size in bytes and its SHA-256, from the release's manifest. */
    val size: Long,
    val sha256: String,
    /** Where GitHub serves the APK, under the repository's release downloads. */
    val url: String,
    /** What is new, by language ("en" always, "fr" when written), each at most [NOTES_MAX] characters. */
    val notes: Map<String, String> = emptyMap(),
) {
    /** The room the download needs on the device: the file, its copy into Android's installer, and some to spare. */
    val spaceNeeded: Long get() = size * SPACE_FACTOR

    /** What is new in [language], else in English, a line a paragraph, as the interface writes. */
    fun notesIn(language: String): List<String> {
        val text = notes[language] ?: notes[ENGLISH] ?: return emptyList()
        return text.lines()
            .map { it.trim().removePrefix("- ").removePrefix("* ").removePrefix("• ").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { Typeset.text(it, french = language == FRENCH && notes.containsKey(FRENCH)) }
    }

    companion object {
        const val NOTES_MAX = 4_000
        const val SPACE_FACTOR = 3L
        private const val ENGLISH = "en"
        private const val FRENCH = "fr"
    }
}

/** Where an update stands; the sheet's action row follows it. */
sealed interface UpdateStep {
    data object Idle : UpdateStep

    /** Asking GitHub, on demand: the automatic check never says it. */
    data object Checking : UpdateStep

    data class Downloading(val read: Long, val total: Long) : UpdateStep

    /** The file's size and SHA-256, then its package, version and signer. */
    data object Verifying : UpdateStep

    /** Verified, waiting for OK: the sheet closed before it was, so Android's window never opens by itself. */
    data object Ready : UpdateStep

    /**
     * Android must let the app install: [screen] is the settings screen that may allow it; [stuck] when there is none, or
     * the user came back without allowing it, so the sheet gives the other ways in words.
     */
    data class NeedsPermission(val screen: PermissionScreen, val refused: Boolean = false) : UpdateStep {
        val stuck: Boolean get() = refused || screen == PermissionScreen.NONE
    }

    /**
     * Handed to Android, which asks the user to confirm and installs: as long as Android holds the session, nothing
     * abandons it. [reopen] when the user left Android's window (the Home key): OK opens it again.
     */
    data class Installing(val reopen: Boolean = false) : UpdateStep
}

/** The version the last update installed ([installed]) or did not, said once on home and for a day in About. */
@Serializable
data class Updated(val versionName: String, val installed: Boolean, val at: Long, val said: Boolean = false)

/** The updater as the screens read it. */
data class UpdateState(
    /** This build updates itself ([UpdaterAvailability]); without it, the screens show none of it. */
    val on: Boolean = false,
    /** The installed version's full name (0.4.0, 0.4.1-beta.2). */
    val installed: String = "",
    /** When GitHub last answered, by the device's clock; null when it never did. */
    val checkedAt: Long? = null,
    /** « Recherche automatique », as the settings say it. */
    val automatic: Boolean = true,
    /** Betas offered: chosen in the settings, else when a beta is installed. */
    val beta: Boolean = false,
    val offer: UpdateOffer? = null,
    val step: UpdateStep = UpdateStep.Idle,
    /** Why what was asked failed; an automatic check's failures are only logged. */
    val failure: UpdateFailure? = null,
    /** Until when GitHub asks to wait ([UpdateFailure.BUSY]). */
    val waitUntil: Long? = null,
    /** The last update, said once on home and for a day in About. */
    val updated: Updated? = null,
    /** The newest versionCode whose sheet home has opened. */
    val seenOnHome: Long = 0,
    /** The sheet opens again on home where it stood: Android closed the app while the user was allowing installs. */
    val backOnHome: Boolean = false,
) {
    /**
     * The version offered opens its sheet on home: once per version (« Plus tard » waits for a newer one), never when
     * the automatic check is off, nor in a build without the updater.
     */
    val offeredOnHome: Boolean
        get() = on && automatic && offer != null && (offer.versionCode > seenOnHome || backOnHome)
}
