package app.houselights.ui.settings

import android.util.Log
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
import app.houselights.core.MediaProvider
import app.houselights.core.PathMapping
import app.houselights.core.suspendRunCatching
import app.houselights.providers.local.CleanupResult
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.TmdbClient
import app.houselights.providers.local.TmdbKeyCheck
import app.houselights.settings.AppSettings
import app.houselights.settings.SettingsStore
import app.houselights.ui.util.UiError
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.launchAction
import app.houselights.ui.util.toWriteError
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/** How saving a personal TMDB key went. */
internal sealed interface TmdbKeySave {
    /** TMDB answered ([TmdbKeyCheck.REFUSED]: not saved), or could not be asked (saved all the same). */
    data class Checked(val check: TmdbKeyCheck) : TmdbKeySave

    /** The key could not be written (the device's storage, its Keystore): nothing was saved. */
    data class Failed(val error: UiError) : TmdbKeySave
}

/**
 * What the settings show and change. A change never takes the app down: one that fails is said in
 * [actionError] until the next one. Removing a folder and saving a TMDB key run in [work], which
 * outlives the screen: leaving the pane, or the settings, while one waits (for a scan's file, for
 * TMDB) never cuts it off. Holds the image loader rather than a context, so nothing of a screen
 * outlives it.
 */
internal class SettingsViewModel(
    private val store: SettingsStore,
    val library: LocalLibrary,
    private val provider: () -> MediaProvider,
    private val work: CoroutineScope,
    private val images: ImageLoader?,
    private val checkTmdbKey: suspend (String) -> TmdbKeyCheck = { TmdbClient(it).checkKey() },
) : ViewModel() {
    constructor(app: AppContainer, images: ImageLoader) : this(app.settings, app.library, app::provider, app.scope, images)

    val settings: StateFlow<AppSettings> = store.state

    /** What the current source can do; the settings show what applies to it. */
    val capabilities get() = provider().capabilities

    private val _storage = MutableStateFlow<StorageStats?>(null)
    val storage: StateFlow<StorageStats?> = _storage

    /** Why the last change made here did not take, shown at the head of the pane until the next one. */
    var actionError by mutableStateOf<UiError?>(null)
        private set

    /** Folders being removed: each waits for the file a scan may be reading in it. */
    var removing by mutableStateOf(emptySet<Long>())
        private set

    /** A personal TMDB key being checked with TMDB, then saved. */
    var savingTmdbKey by mutableStateOf(false)
        private set

    init {
        refreshStorage()
    }

    /** What [actionError] said is no longer where the user is (another pane). */
    fun clearActionError() {
        actionError = null
    }

    /** Runs a change the user asked for in [scope]: a failure is logged and said in [actionError]. */
    private fun change(what: String, scope: CoroutineScope = viewModelScope, block: suspend CoroutineScope.() -> Unit) {
        actionError = null
        scope.launchAction(TAG, what, onError = { actionError = it }, errorOf = Throwable::toWriteError, block = block)
    }

    @OptIn(ExperimentalCoilApi::class)
    fun refreshStorage() {
        viewModelScope.launchAction(TAG, "Reading the storage used") {
            val local = library.storage()
            val disk = images?.diskCache
            _storage.value = StorageStats(
                artworkFiles = local.artworkFiles,
                artworkBytes = local.artworkBytes,
                imageCacheBytes = disk?.size ?: 0L,
                imageCacheMax = disk?.maxSize ?: 0L,
                databaseBytes = local.databaseBytes,
            )
        }
    }

    fun setForceHttp(value: Boolean) = change("Saving the stream setting") { store.setForceHttp(value) }

    fun setUi4k(value: Boolean) = change("Saving the 4K setting") { store.setUi4k(value) }

    fun setCollectionMinFilms(value: Int) = change("Saving the collections setting") {
        store.setCollectionMinFilms(value)
        // Libraries and detail pages read the setting: let them reload.
        library.settingsChanged()
    }

    /**
     * Saves a personal TMDB key once TMDB has taken it. A key TMDB refuses is not saved; one it could
     * not check (out of reach) is saved all the same. A blank key goes back to the built-in one. In
     * [work]: TMDB may take some seconds, the sheet may close meanwhile, the key is saved all the same;
     * [done] hears how it went.
     */
    fun saveTmdbApiKey(key: String, done: (TmdbKeySave) -> Unit) {
        if (savingTmdbKey) return
        savingTmdbKey = true
        val typed = key.trim()
        work.launchAction(
            TAG,
            "Saving the TMDB key",
            onError = {
                savingTmdbKey = false
                done(TmdbKeySave.Failed(it))
            },
            errorOf = Throwable::toWriteError,
        ) {
            val check = if (typed.isEmpty()) TmdbKeyCheck.ACCEPTED else checkTmdbKey(typed)
            if (check != TmdbKeyCheck.REFUSED) store.setTmdbApiKey(typed)
            savingTmdbKey = false
            done(TmdbKeySave.Checked(check))
        }
    }

    /**
     * Removes [folder] and what it holds, in [work]: it waits for the file a scan may be reading in it,
     * and leaving the settings meanwhile never cuts it off. [removing] holds it until then.
     */
    fun removeFolder(folder: SourceEntity) {
        if (folder.id in removing) return
        removing += folder.id
        change("Removing the folder ${folder.id}", work) {
            try {
                library.removeFolder(folder)
            } finally {
                removing -= folder.id
            }
        }
    }

    private val _mappings = mutableStateListOf<PathMapping>().apply { addAll(store.state.value.pathMappings) }

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
        change("Saving the path mappings") {
            store.setPathMappings(complete)
            mappingsSaved = true
        }
    }

    private fun completeMappings() = _mappings.filter { it.from.isNotBlank() && it.to.isNotBlank() }

    override fun onCleared() {
        // Settings left from inside a field still keep what was typed.
        if (mappingsEdited) {
            val complete = completeMappings()
            work.launchAction(TAG, "Saving the path mappings") { store.setPathMappings(complete) }
        }
    }

    /** Deletes the artwork no title uses; null when it could not ([actionError] says why). */
    suspend fun cleanup(): CleanupResult? = action("Cleaning up the artwork") { library.cleanup() }?.also { refreshStorage() }

    /** Empties the image cache; false when it could not ([actionError] says why). */
    @OptIn(ExperimentalCoilApi::class)
    suspend fun clearImageCache(): Boolean = action("Clearing the image cache") {
        images?.memoryCache?.clear()
        withContext(Dispatchers.IO) { images?.diskCache?.clear() }
        Unit
    }.also { refreshStorage() } != null

    /** [block], for a screen that waits for its answer; null when it failed, which [actionError] says. */
    private suspend fun <T : Any> action(what: String, block: suspend () -> T): T? {
        actionError = null
        return suspendRunCatching { block() }
            .onFailure { e ->
                Log.w(TAG, "$what failed", e)
                actionError = e.toWriteError()
            }
            .getOrNull()
    }

    /** A file path as the server gives it, to show what path mappings do. */
    suspend fun samplePath(): String? = suspendRunCatching { provider().samplePath() }.getOrNull()

    private companion object {
        const val TAG = "Settings"
    }
}

/** The screen's ViewModel, shared by all its panes. */
@Composable
internal fun settingsViewModel(): SettingsViewModel {
    val app = appContainer()
    val images = LocalContext.current.imageLoader
    return viewModel { SettingsViewModel(app, images) }
}
