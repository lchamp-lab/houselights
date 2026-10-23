package app.houselights.settings

import app.houselights.core.SourceConfig
import app.houselights.providers.trakt.TraktSync
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Trakt follows the account and the source: it syncs while connected with the folders as the source, and pauses,
 * still connected, its queue kept, under another. Marks and stops are queued while there is an account with the
 * folders. A session the Keystore could not open at launch is no sign-out: nothing is forgotten, neither what waits
 * nor where the sync stands, and marks are still queued until it opens again.
 */
class TraktFollow(private val settings: StateFlow<AppSettings>) {
    /** Whether a mark or a stop is queued now; a playback's end may ask before the settings are read. */
    suspend fun queues(): Boolean = queues(settings.first { it.loaded })

    /** Tells [sync] each change of the account or the source, until cancelled. */
    suspend fun drive(sync: TraktSync) {
        settings.filter { it.loaded }
            .map { s -> Followed(s.trakt?.ended == false, s.source == SourceConfig.Local, s.trakt == null && !unread(s), unread(s)) }
            .distinctUntilChanged()
            .collect { sync.follow(it.connected, it.folders, it.signedOut, it.unread) }
    }

    /** What [TraktSync.follow] is told of the settings. */
    private data class Followed(val connected: Boolean, val folders: Boolean, val signedOut: Boolean, val unread: Boolean)

    internal companion object {
        fun queues(s: AppSettings): Boolean = (s.trakt != null || unread(s)) && s.source == SourceConfig.Local

        /** The session is saved but could not be opened at this launch. */
        private fun unread(s: AppSettings) = SecretSetting.TRAKT in s.lostSecrets
    }
}
