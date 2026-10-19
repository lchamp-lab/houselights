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
    /** TMDB answered ([TmdbKeyCheck.REFUSED]: not saved), or could not be reached (saved anyway). */
    data class Checked(val check: TmdbKeyCheck) : TmdbKeySave

    /** The key could not be written (storage or Keystore): nothing was saved. */
    data class Failed(val error: UiError) : TmdbKeySave
}

/** TMDB key attempt [number], as typed ([key]), with its [outcome], null while TMDB checks it. */
internal data class TmdbKeyAttempt(val number: Int, val key: String, val outcome: TmdbKeySave? = null)

/** A change made from a settings row: its failure is reported on that row, where focus is. */
internal enum class SettingAction {
    FILE_ACCESS,
    PATH_MAPPINGS,
    UI_RESOLUTION,
    COLLECTIONS,
    METADATA_LANGUAGE,
    REMOVE_FOLDER,
    CLEANUP,
    CLEAR_IMAGE_CACHE,
    SOURCE,
}

/** Why the change made from [action] failed; [folderId] is the folder a removal targeted. */
internal data class ActionError(val action: SettingAction, val error: UiError, val folderId: Long? = null)

/**
 * What Settings shows and changes. A failed change shows in [actionError] on its row until the next one. Folder
 * removal and TMDB key saving run in [work], which outlives the screen. Holds the image loader, not a context,
 * so no screen leaks.
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

    /** Whether the current source counts its library ([MediaProvider.catalog]), for the library in numbers. */
    val counts get() = provider().catalog != null

    private val _storage = MutableStateFlow<StorageStats?>(null)
    val storage: StateFlow<StorageStats?> = _storage

    /** Why the last change made here failed, shown on its row until the next one. */
    var actionError by mutableStateOf<ActionError?>(null)
        private set

    /** Why the last change from [action]'s row (for folder [folderId]) failed; null when it did not. */
    fun errorOf(action: SettingAction, folderId: Long? = null): UiError? =
        actionError?.takeIf { it.action == action && it.folderId == folderId }?.error

    /** Records a failure of [action] that happened elsewhere (making the folders the source, on its own screen). */
    fun actionFailed(action: SettingAction, error: UiError) {
        actionError = ActionError(action, error)
    }

    /** Folders being removed: each waits for any file a scan is reading in it. */
    var removing by mutableStateOf(emptySet<Long>())
        private set

    /** A personal TMDB key is being checked with TMDB, then saved. */
    var savingTmdbKey by mutableStateOf(false)
        private set

    /** The last TMDB key attempt until a new key is typed; read by the sheet, which may close and reopen mid-check. */
    var tmdbKey by mutableStateOf<TmdbKeyAttempt?>(null)
        private set

    private var tmdbKeyAttempts = 0

    /** A key is being typed: the last outcome no longer applies. */
    fun editTmdbKey() {
        tmdbKey = null
    }

    init {
        refreshStorage()
    }

    /** Clears [actionError] once it is no longer where focus is (another pane). */
    fun clearActionError() {
        actionError = null
    }

    /** Runs a change requested from [action]'s row in [scope]; a failure is logged and set in [actionError]. */
    private fun change(
        what: String,
        action: SettingAction,
        scope: CoroutineScope = viewModelScope,
        folderId: Long? = null,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        actionError = null
        val failed = { error: UiError -> actionError = ActionError(action, error, folderId) }
        scope.launchAction(TAG, what, onError = failed, errorOf = Throwable::toWriteError, block = block)
    }

    @OptIn(ExperimentalCoilApi::class)
    fun refreshStorage() {
        viewModelScope.launchAction(TAG, "Reading the storage used") {
            val local = library.storage()
            val disk = images?.diskCache
            // Off the main thread: Coil's cache reads its journal on first access and blocks during cleanup.
            val imageCacheBytes = withContext(Dispatchers.IO) { disk?.size ?: 0L }
            _storage.value = StorageStats(
                artworkFiles = local.artworkFiles,
                artworkBytes = local.artworkBytes,
                imageCacheBytes = imageCacheBytes,
                imageCacheMax = disk?.maxSize ?: 0L,
                databaseBytes = local.databaseBytes,
            )
        }
    }

    fun setForceHttp(value: Boolean) = change("Saving the stream setting", SettingAction.FILE_ACCESS) { store.setForceHttp(value) }

    fun setUi4k(value: Boolean) = change("Saving the 4K setting", SettingAction.UI_RESOLUTION) { store.setUi4k(value) }

    fun setCollectionMinFilms(value: Int) = change("Saving the collections setting", SettingAction.COLLECTIONS) {
        store.setCollectionMinFilms(value)
        // Libraries and detail pages read the setting: let them reload.
        library.settingsChanged()
    }

    /** Saves TMDB's language ([tag], null for the device's); the app then reads every title again in it. */
    fun setMetadataLanguage(tag: String?) = change("Saving the details language", SettingAction.METADATA_LANGUAGE) {
        store.setMetadataLanguage(tag)
    }

    /**
     * Saves a personal TMDB key unless TMDB refuses it (blank reverts to the built-in one), in [work] as the sheet
     * may close meanwhile; [tmdbKey] reports the outcome. Returns the attempt number, or null while another runs.
     */
    fun saveTmdbApiKey(key: String): Int? {
        if (savingTmdbKey) return null
        savingTmdbKey = true
        val attempt = TmdbKeyAttempt(++tmdbKeyAttempts, key.trim())
        tmdbKey = attempt
        fun went(outcome: TmdbKeySave) {
            savingTmdbKey = false
            // A key typed meanwhile is a new attempt: this outcome must not overwrite it.
            if (tmdbKey?.number == attempt.number) tmdbKey = attempt.copy(outcome = outcome)
        }
        work.launchAction(TAG, "Saving the TMDB key", onError = { went(TmdbKeySave.Failed(it)) }, errorOf = Throwable::toWriteError) {
            val check = if (attempt.key.isEmpty()) TmdbKeyCheck.ACCEPTED else checkTmdbKey(attempt.key)
            if (check != TmdbKeyCheck.REFUSED) store.setTmdbApiKey(attempt.key)
            went(TmdbKeySave.Checked(check))
        }
        return attempt.number
    }

    /** Removes [folder] and its titles in [work], waiting for a scan's open file; [removing] holds it meanwhile. */
    fun removeFolder(folder: SourceEntity) {
        if (folder.id in removing) return
        removing += folder.id
        change("Removing the folder ${folder.id}", SettingAction.REMOVE_FOLDER, work, folder.id) {
            try {
                library.removeFolder(folder)
            } finally {
                removing -= folder.id
            }
        }
    }

    private val _mappings = mutableStateListOf<PathMapping>().apply { addAll(store.state.value.pathMappings) }

    /** The path mappings as typed, incomplete lines included: they survive pane changes and scrolling. */
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

    /** Saves the complete lines on leaving a field, not while typing, so a half-typed path never plays. */
    fun saveMappings() {
        if (!mappingsEdited) return
        mappingsEdited = false
        val complete = completeMappings()
        change("Saving the path mappings", SettingAction.PATH_MAPPINGS) {
            store.setPathMappings(complete)
            mappingsSaved = true
        }
    }

    private fun completeMappings() = _mappings.filter { it.from.isNotBlank() && it.to.isNotBlank() }

    override fun onCleared() {
        // Leaving Settings from inside a field still saves what was typed.
        if (mappingsEdited) {
            val complete = completeMappings()
            work.launchAction(TAG, "Saving the path mappings") { store.setPathMappings(complete) }
        }
    }

    /** Deletes the artwork no title uses; null when it fails ([actionError] says why). */
    suspend fun cleanup(): CleanupResult? =
        action("Cleaning up the artwork", SettingAction.CLEANUP) { library.cleanup() }?.also { refreshStorage() }

    /** Empties the image cache; false when it fails ([actionError] says why). */
    @OptIn(ExperimentalCoilApi::class)
    suspend fun clearImageCache(): Boolean = action("Clearing the image cache", SettingAction.CLEAR_IMAGE_CACHE) {
        images?.memoryCache?.clear()
        withContext(Dispatchers.IO) { images?.diskCache?.clear() }
        Unit
    }.also { refreshStorage() } != null

    /** Runs [block] for a screen that awaits its result; null on failure, which [actionError] reports. */
    private suspend fun <T : Any> action(what: String, action: SettingAction, block: suspend () -> T): T? {
        actionError = null
        return suspendRunCatching { block() }
            .onFailure { e ->
                Log.w(TAG, "$what failed", e)
                actionError = ActionError(action, e.toWriteError())
            }
            .getOrNull()
    }

    /** A file path as the server reports it, to show what path mappings do. */
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
