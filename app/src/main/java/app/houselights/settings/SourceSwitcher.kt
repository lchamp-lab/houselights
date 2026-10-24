package app.houselights.settings

import android.util.Log
import app.houselights.core.FailureKind
import app.houselights.core.SourceConfig
import app.houselights.core.failureKind
import app.houselights.core.suspendRunCatching
import app.houselights.providers.jellyfin.JellyfinClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How resuming the kept server went. */
sealed interface Resumed {
    /** It took its token: it is the source again. */
    data object Done : Resumed

    /** It no longer takes its token, or was forgotten before it was asked: [server] is no longer kept, signing in is needed. */
    data class Refused(val server: SourceConfig.Jellyfin) : Resumed

    /** Forgotten or replaced while it was asked: that choice stands, nothing is done. */
    data object Overtaken : Resumed
}

/**
 * Replaces the library source from any screen; only once the new source is saved, so a screen abandoned halfway
 * changes nothing. A Jellyfin server left for the folders stays signed in, kept to be resumed; one at most. A server's
 * token is revoked once nothing uses or keeps it: forgotten, signed out of, or replaced by a new sign-in; never used
 * again after. Changes are made one at a time, each on the settings the last one left.
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
    /** Saves the source, keeping the server source it replaces, which the Keystore did not open for now, as stored. */
    private val keepUnopened: suspend (SourceConfig) -> Unit = { save(it, null) },
) {
    private val changing = Mutex()

    /** Makes [source] the library; a server left for anything else stays signed in, kept, unless [keepServer] is false. */
    suspend fun switchTo(source: SourceConfig, keepServer: Boolean = true) = changing.withLock { switch(source, keepServer) }

    private suspend fun switch(source: SourceConfig, keepServer: Boolean) {
        val before = current()
        // A server the Keystore did not open for now is kept all the same, sealed as it is stored.
        if (before.unreadSource && keepServer && source !is SourceConfig.Jellyfin) {
            return keepUnopened(source)
        }
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
    suspend fun forget() = changing.withLock {
        val before = current()
        if (before.keptServer == null) return@withLock
        save(before.source ?: SourceConfig.Local, null)
        revoke(before, keep = null, inUse = before.source)
    }

    /**
     * Makes [server], the kept one, the source again once it takes its token. A token it refuses, or a server
     * forgotten before, is never used: the sign-in is needed. One forgotten or replaced while it is asked, its token
     * maybe revoked already, is left alone. Throws when the server cannot be asked.
     */
    suspend fun resume(server: SourceConfig.Jellyfin): Resumed {
        val before = current()
        if (before.keptServer != server) return Resumed.Refused(server)
        // Not under the lock: a server waking up may take seconds to answer, and forgetting it must not wait.
        val refused = suspendRunCatching { check(server, before.deviceId) }.exceptionOrNull()
        return changing.withLock {
            val now = current()
            when {
                now.keptServer != server -> Resumed.Overtaken
                refused == null -> {
                    switch(server, keepServer = true)
                    Resumed.Done
                }
                refused.failureKind() != FailureKind.REFUSED -> throw refused
                else -> {
                    save(now.source ?: SourceConfig.Local, null)
                    Resumed.Refused(server)
                }
            }
        }
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
