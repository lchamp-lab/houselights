package app.houselights.settings

import android.util.Log
import app.houselights.core.FailureKind
import app.houselights.core.SourceConfig
import app.houselights.core.failureKind
import app.houselights.core.suspendRunCatching
import app.houselights.providers.jellyfin.JellyfinClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** How resuming the kept server went. */
sealed interface Resumed {
    /** It took its token: it is the source again. */
    data object Done : Resumed

    /** It no longer takes its token, or was forgotten meanwhile: [server] is no longer kept, signing in is needed. */
    data class Refused(val server: SourceConfig.Jellyfin) : Resumed
}

/**
 * Replaces the library source from any screen; only once the new source is saved, so a screen abandoned halfway
 * changes nothing. A Jellyfin server left for the folders stays signed in, kept to be resumed; one at most. A server's
 * token is revoked once nothing uses or keeps it: forgotten, signed out of, or replaced by a new sign-in; never used
 * again after.
 */
class SourceSwitcher(
    private val current: () -> AppSettings,
    /** Saves the source and the server kept beside it (null for none), in one write. */
    private val save: suspend (SourceConfig, SourceConfig.Jellyfin?) -> Unit,
    private val scope: CoroutineScope,
    private val signOut: suspend (SourceConfig.Jellyfin, String) -> Unit = { server, deviceId ->
        JellyfinClient.signOut(server, deviceId)
    },
    /** Asks the server something light with the token; throws once it refuses it, or when it cannot be reached. */
    private val check: suspend (SourceConfig.Jellyfin, String) -> Unit = { server, deviceId ->
        JellyfinClient.checkToken(server, deviceId)
    },
) {
    /** Makes [source] the library; a server left for anything else stays signed in, kept, unless [keepServer] is false. */
    suspend fun switchTo(source: SourceConfig, keepServer: Boolean = true) {
        val before = current()
        val left = before.source as? SourceConfig.Jellyfin
        val kept = when {
            source is SourceConfig.Jellyfin -> null
            left != null -> left.takeIf { keepServer }
            else -> before.keptServer
        }
        val failure = suspendRunCatching { save(source, kept) }.exceptionOrNull()
        if (failure == null) return revoke(before, kept, source)
        // The folders never wait on the Keystore: a server it cannot seal is signed out of instead.
        if (kept == null || failure.failureKind() != FailureKind.KEYSTORE) throw failure
        Log.w(TAG, "The server left could not be kept", failure)
        save(source, null)
        revoke(before, keep = null, inUse = source)
    }

    /** Forgets the kept server and revokes its token. */
    suspend fun forget() {
        val before = current()
        if (before.keptServer == null) return
        save(before.source ?: SourceConfig.Local, null)
        revoke(before, keep = null, inUse = before.source)
    }

    /**
     * Makes [server], the kept one, the source again once it takes its token. A token it refuses, or a server
     * forgotten meanwhile, is never used: the sign-in is needed. Throws when the server cannot be asked.
     */
    suspend fun resume(server: SourceConfig.Jellyfin): Resumed {
        val before = current()
        if (before.keptServer != server) return Resumed.Refused(server)
        val refused = suspendRunCatching { check(server, before.deviceId) }.exceptionOrNull()
        if (refused != null) {
            if (refused.failureKind() != FailureKind.REFUSED) throw refused
            save(before.source ?: SourceConfig.Local, null)
            return Resumed.Refused(server)
        }
        switchTo(server)
        return Resumed.Done
    }

    /** Revokes the servers [before] held that are neither [inUse] nor kept ([keep]); signing in again may hand the same token back. */
    private fun revoke(before: AppSettings, keep: SourceConfig.Jellyfin?, inUse: SourceConfig?) {
        val held = setOf((inUse as? SourceConfig.Jellyfin)?.accessToken, keep?.accessToken)
        listOfNotNull(before.source as? SourceConfig.Jellyfin, before.keptServer)
            .distinctBy { it.accessToken }
            .filter { it.accessToken !in held }
            // Not tied to the calling screen, which closes as soon as the new source is saved.
            .forEach { scope.launch { signOut(it, before.deviceId) } }
    }

    private companion object {
        const val TAG = "Sources"
    }
}
