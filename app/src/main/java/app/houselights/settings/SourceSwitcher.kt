package app.houselights.settings

import app.houselights.core.SourceConfig
import app.houselights.providers.jellyfin.JellyfinClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Replaces the library source, whichever screen chose the new one (welcome, adding a folder,
 * signing in). The new source is saved first; only then does a Jellyfin server left behind forget
 * the app's token. Until then the current source stays as it is: a screen left halfway changes
 * nothing.
 */
class SourceSwitcher(
    private val current: () -> AppSettings,
    private val save: suspend (SourceConfig) -> Unit,
    private val scope: CoroutineScope,
    private val signOut: suspend (SourceConfig.Jellyfin, String) -> Unit = { server, deviceId ->
        JellyfinClient.signOut(server, deviceId)
    },
) {
    suspend fun switchTo(source: SourceConfig) {
        val before = current()
        save(source)
        val left = before.source as? SourceConfig.Jellyfin ?: return
        // Signing in again to the same server may hand back the same token: the one now in use.
        if ((source as? SourceConfig.Jellyfin)?.accessToken == left.accessToken) return
        // Not tied to the screen that asked: it closes as soon as the new source is saved.
        scope.launch { signOut(left, before.deviceId) }
    }
}
