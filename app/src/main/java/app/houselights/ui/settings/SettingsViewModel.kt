package app.houselights.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.houselights.AppContainer
import app.houselights.core.PathMapping
import app.houselights.core.suspendRunCatching
import app.houselights.providers.local.CleanupResult
import app.houselights.providers.local.LocalLibrary
import app.houselights.settings.AppSettings
import app.houselights.ui.util.appContainer
import coil.ImageLoader
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
/** Holds the image loader rather than a context, so nothing of a screen outlives it. */
internal class SettingsViewModel(private val app: AppContainer, private val images: ImageLoader) : ViewModel() {
    val settings: StateFlow<AppSettings> = app.settings.state
    val library: LocalLibrary = app.library

    /** What the current source can do; the settings show what applies to it. */
    val capabilities get() = app.provider.capabilities

    private val _storage = MutableStateFlow<StorageStats?>(null)
    val storage: StateFlow<StorageStats?> = _storage

    init {
        refreshStorage()
    }

    @OptIn(ExperimentalCoilApi::class)
    fun refreshStorage() {
        viewModelScope.launch {
            val local = library.storage()
            val disk = images.diskCache
            _storage.value = StorageStats(
                artworkFiles = local.artworkFiles,
                artworkBytes = local.artworkBytes,
                imageCacheBytes = disk?.size ?: 0L,
                imageCacheMax = disk?.maxSize ?: 0L,
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

    private val _mappings = mutableStateListOf<PathMapping>().apply { addAll(app.settings.state.value.pathMappings) }

    /** The path mappings as typed, lines not yet complete included: they outlive panes and scrolling. */
    val mappings: List<PathMapping> = _mappings

    private var mappingsEdited = false

    /** True once the mappings have been saved, until the next edit. */
    var mappingsSaved by mutableStateOf(false)
        private set

    fun editMapping(index: Int, mapping: PathMapping) {
        _mappings[index] = mapping
        mappingsEdited = true
        mappingsSaved = false
    }

    fun addMapping() {
        _mappings += PathMapping("", "")
    }

    fun removeMapping(index: Int) {
        _mappings.removeAt(index)
        mappingsEdited = true
        saveMappings()
    }

    /**
     * Saves the complete lines, when a field is left rather than while typing: a path typed halfway
     * is never used to play.
     */
    fun saveMappings() {
        if (!mappingsEdited) return
        mappingsEdited = false
        val complete = completeMappings()
        viewModelScope.launch {
            app.settings.setPathMappings(complete)
            mappingsSaved = true
        }
    }

    private fun completeMappings() = _mappings.filter { it.from.isNotBlank() && it.to.isNotBlank() }

    override fun onCleared() {
        // Settings left from inside a field still keep what was typed.
        if (mappingsEdited) {
            val complete = completeMappings()
            app.scope.launch { app.settings.setPathMappings(complete) }
        }
    }

    suspend fun cleanup(): CleanupResult = library.cleanup().also { refreshStorage() }

    @OptIn(ExperimentalCoilApi::class)
    suspend fun clearImageCache() {
        images.memoryCache?.clear()
        withContext(Dispatchers.IO) { images.diskCache?.clear() }
        refreshStorage()
    }

    /** A file path as the server gives it, to show what path mappings do. */
    suspend fun samplePath(): String? = suspendRunCatching { app.provider.samplePath() }.getOrNull()
}

/** The screen's ViewModel, shared by all its panes. */
@Composable
internal fun settingsViewModel(): SettingsViewModel {
    val app = appContainer()
    val images = LocalContext.current.imageLoader
    return viewModel { SettingsViewModel(app, images) }
}
