package app.houselights.settings

import app.houselights.core.SourceConfig
import app.houselights.providers.jellyfin.JellyfinClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Replaces the library source from any screen; only once the new source is saved, so a screen abandoned halfway
 * changes nothing, the old Jellyfin server's token is revoked, and leaving the folders disconnects their Trakt account.
 */
class SourceSwitcher(
    private val current: () -> AppSettings,
    private val save: suspend (SourceConfig) -> Unit,
    private val scope: CoroutineScope,
    private val signOut: suspend (SourceConfig.Jellyfin, String) -> Unit = { server, deviceId ->
        JellyfinClient.signOut(server, deviceId)
    },
    /** The folders are no longer the source: Trakt, which keeps their history, is disconnected. */
    private val leftFolders: suspend () -> Unit = {},
) {
    suspend fun switchTo(source: SourceConfig) {
        val before = current()
        save(source)
        if (before.source == SourceConfig.Local && source != SourceConfig.Local) scope.launch { leftFolders() }
        val left = before.source as? SourceConfig.Jellyfin ?: return
        // Signing in again to the same server may return the same token, which is now in use.
        if ((source as? SourceConfig.Jellyfin)?.accessToken == left.accessToken) return
        // Not tied to the calling screen, which closes as soon as the new source is saved.
        scope.launch { signOut(left, before.deviceId) }
    }
}
