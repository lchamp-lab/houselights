package app.houselights.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Context
import app.houselights.AppContainer
import app.houselights.core.PathMapping
import app.houselights.core.SourceConfig
import app.houselights.core.suspendRunCatching
import app.houselights.providers.jellyfin.JellyfinClient
import app.houselights.providers.local.CleanupResult
import app.houselights.providers.local.LocalLibrary
import app.houselights.settings.AppSettings
import app.houselights.ui.util.appContainer
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What Houselights keeps on the device. */
internal data class StorageStats(
    val artworkFiles: Int,
    val artworkBytes: Long,
    val imageCacheBytes: Long,
    val imageCacheMax: Long,
    val databaseBytes: Long,
) {
    val total get() = artworkBytes + imageCacheBytes + databaseBytes
}

/**
 * What the settings show and change. Changes run in the ViewModel's scope: leaving the screen while
 * one is written never cuts it off.
 */
internal class SettingsViewModel(private val app: AppContainer, private val context: Context) : ViewModel() {
    val settings: StateFlow<AppSettings> = app.settings.state
    val library: LocalLibrary = app.library

    private val _storage = MutableStateFlow<StorageStats?>(null)
    val storage: StateFlow<StorageStats?> = _storage

    init {
        refreshStorage()
    }

    @OptIn(ExperimentalCoilApi::class)
    fun refreshStorage() {
        viewModelScope.launch {
            val local = library.storage()
            val images = context.imageLoader.diskCache
            _storage.value = StorageStats(
                artworkFiles = local.artworkFiles,
                artworkBytes = local.artworkBytes,
                imageCacheBytes = images?.size ?: 0L,
                imageCacheMax = images?.maxSize ?: 0L,
                databaseBytes = local.databaseBytes,
            )
        }
    }

    fun setForceHttp(value: Boolean) {
        viewModelScope.launch { app.settings.setForceHttp(value) }
    }

    fun setUi4k(value: Boolean) {
        viewModelScope.launch { app.settings.setUi4k(value) }
    }

    fun setCollectionMinFilms(value: Int) {
        viewModelScope.launch {
            app.settings.setCollectionMinFilms(value)
            // Libraries and detail pages read the setting: let them reload.
            library.settingsChanged()
        }
    }

    fun setTmdbApiKey(key: String, done: () -> Unit) {
        viewModelScope.launch {
            app.settings.setTmdbApiKey(key)
            done()
        }
    }

    fun setPathMappings(mappings: List<PathMapping>, done: () -> Unit) {
        viewModelScope.launch {
            app.settings.setPathMappings(mappings.filter { it.from.isNotBlank() && it.to.isNotBlank() })
            done()
        }
    }

    /** Leaves the current source; a Jellyfin server forgets the app's token first. */
    fun changeSource(done: () -> Unit) {
        viewModelScope.launch {
            val s = settings.value
            (s.source as? SourceConfig.Jellyfin)?.let { JellyfinClient.signOut(it, s.deviceId) }
            app.settings.setSource(null)
            done()
        }
    }

    suspend fun cleanup(): CleanupResult = library.cleanup().also { refreshStorage() }

    @OptIn(ExperimentalCoilApi::class)
    suspend fun clearImageCache() {
        context.imageLoader.memoryCache?.clear()
        withContext(Dispatchers.IO) { context.imageLoader.diskCache?.clear() }
        refreshStorage()
    }

    /** A file path as the server gives it, to show what path mappings do. */
    suspend fun samplePath(): String? = suspendRunCatching { app.provider.samplePath() }.getOrNull()
}

/** The screen's ViewModel, shared by all its panes. */
@Composable
internal fun settingsViewModel(): SettingsViewModel {
    val app = appContainer()
    val context = LocalContext.current.applicationContext
    return viewModel { SettingsViewModel(app, context) }
}
