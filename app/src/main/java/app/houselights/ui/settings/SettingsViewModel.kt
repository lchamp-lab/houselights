package app.houselights.ui.settings

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.houselights.AppContainer
import app.houselights.core.MediaProvider
import app.houselights.core.PathMapping
import app.houselights.core.ProviderCapabilities
import app.houselights.core.SourceConfig
import app.houselights.core.TrackPreferences
import app.houselights.core.suspendRunCatching
import app.houselights.providers.local.CleanupResult
import app.houselights.providers.local.Folder
import app.houselights.providers.local.IgnoredFileRow
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.TmdbClient
import app.houselights.providers.local.TmdbKeyCheck
import app.houselights.settings.AppSettings
import app.houselights.settings.InterfaceLanguage
import app.houselights.settings.Resumed
import app.houselights.settings.SecretSetting
import app.houselights.settings.SettingsStore
import app.houselights.settings.SourceSwitcher
import app.houselights.settings.TextSize
import app.houselights.ui.util.UiError
import app.houselights.ui.util.launchAction
import app.houselights.ui.util.toWriteError
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

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
    INTERFACE_LANGUAGE,
    TEXT_SIZE,
    REMOVE_FOLDER,
    CLEANUP,
    CLEAR_IMAGE_CACHE,
    SOURCE,

    /** Resuming or forgetting the server kept beside the folders, said on the source's row. */
    KEPT_SERVER,
    TRACKS,

    /** « Recherche automatique » and « Versions proposées », in About's « Mises à jour ». */
    UPDATE_CHECK,
    UPDATE_CHANNEL,

    /** Answering for a folder's titles held while their files are missing, said on its showcase. */
    HELD,
}

/** Why the change made from [action] failed; [folderId] is the folder a removal targeted. */
internal data class ActionError(val action: SettingAction, val error: UiError, val folderId: Long? = null)

/**
 * What Settings shows and changes. A failed change shows in [actionError] on its row until the next one. Folder
 * removal and TMDB key saving run in [work], which outlives the screen. Holds the image loader, not a context,
 * so no screen leaks.
 */
@Suppress("TooManyFunctions") // What Settings changes, a function a setting.
internal class SettingsViewModel(
    private val store: SettingsStore,
    val library: LocalLibrary,
    /** The source's provider; null while there is none (its connection could not be read back). */
    private val provider: () -> MediaProvider?,
    private val work: CoroutineScope,
    private val sources: SourceSwitcher,
    private val images: ImageLoader?,
    private val checkTmdbKey: suspend (String) -> TmdbKeyCheck = { TmdbClient(it).checkKey() },
    private val chooseLanguage: suspend (String?) -> Unit = {},
    /** The device's locale, which the details follow while no language is chosen for them. */
    val deviceLocale: StateFlow<Locale> = MutableStateFlow(Locale.getDefault()),
    /** The language chosen for the interface ([InterfaceLanguage.chosen]); null follows the device. */
    val interfaceLanguage: StateFlow<String?> = MutableStateFlow(null),
    /** A Zidoo player takes the tracks asked of it: the tracks settings apply ([AppContainer.asksTracks]). */
    val asksTracks: Boolean = false,
    /** How many films and shows are to identify ([LocalLibrary.toIdentifyCount]). */
    toIdentifyCount: Flow<Int> = library.toIdentifyCount,
    /** The videos the shows folders hold that the library leaves out ([LocalLibrary.ignored]). */
    ignoredFiles: Flow<List<IgnoredFileRow>> = library.ignored,
) : ViewModel() {
    constructor(app: AppContainer, images: ImageLoader) : this(
        app.settings, app.library, app::providerOrNull, app.scope, app.sources, images,
        chooseLanguage = app.language::choose, deviceLocale = app.deviceLocale, interfaceLanguage = app.language.chosen,
        asksTracks = app.asksTracks,
    )

    val settings: StateFlow<AppSettings> = store.state

    /** True while the secrets the Keystore did not open are read again ([readSecretsAgain]). */
    val readingSecrets: StateFlow<Boolean> get() = store.reading

    /** Réessayer: reads again the secrets the Keystore did not open. */
    fun readSecretsAgain() = store.readAgain()

    /** What the current source can do; the settings show what applies to it. */
    val capabilities get() = provider()?.capabilities ?: ProviderCapabilities.NONE

    /** Whether the current source counts its library ([MediaProvider.catalog]), for the library in numbers. */
    val counts get() = provider()?.catalog != null

    /**
     * How many films and shows are to identify; null until read. Held while the settings are open beneath another
     * screen, so that coming back finds the row as it is.
     */
    val toIdentify: StateFlow<Int?> = toIdentifyCount.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The videos the shows folders hold that the library leaves out, by folder then path; held as [toIdentify] is. */
    val ignored: StateFlow<List<IgnoredFileRow>> = ignoredFiles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    /** The kept server is being asked whether it still takes its token. */
    var resuming by mutableStateOf(false)
        private set

    /**
     * Makes [server], the one kept beside the folders, the source again, then [onResumed]; one that no longer takes its
     * token goes to [onRefused], for the sign-in. A server that cannot be asked says so on the source's row.
     */
    fun resumeServer(server: SourceConfig.Jellyfin, onResumed: () -> Unit, onRefused: (SourceConfig.Jellyfin) -> Unit) {
        if (resuming) return
        resuming = true
        actionError = null
        val failed = { error: UiError -> actionError = ActionError(SettingAction.KEPT_SERVER, error) }
        viewModelScope.launchAction(TAG, "Resuming the kept server", onError = failed) {
            try {
                when (val resumed = sources.resume(server)) {
                    Resumed.Done -> onResumed()
                    is Resumed.Refused -> onRefused(resumed.server)
                    // Forgotten or replaced meanwhile: that choice stands.
                    Resumed.Overtaken -> Unit
                }
            } finally {
                resuming = false
            }
        }
    }

    /** Forgets the server kept beside the folders and revokes its token. */
    fun forgetServer() = change("Forgetting the kept server", SettingAction.KEPT_SERVER) { sources.forget() }

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
    fun setTracks(tracks: TrackPreferences) = change("Saving the tracks", SettingAction.TRACKS) {
        store.setTracks(tracks)
    }

    fun setMetadataLanguage(tag: String?) = change("Saving the details language", SettingAction.METADATA_LANGUAGE) {
        store.setMetadataLanguage(tag)
    }

    /** Saves the app's language ([tag], null for the device's); the activity then makes its screens again in it. */
    fun setInterfaceLanguage(tag: String?) = change("Saving the interface language", SettingAction.INTERFACE_LANGUAGE) {
        chooseLanguage(tag)
    }

    /** Saves « Taille du texte »; every screen is laid out again at it. */
    fun setTextSize(size: TextSize) = change("Saving the text size", SettingAction.TEXT_SIZE) { store.setTextSize(size) }

    /** « Recherche automatique »: once a day, or only when asked. */
    fun setUpdateCheck(daily: Boolean) = change("Saving the update check", SettingAction.UPDATE_CHECK) { store.setUpdateCheck(daily) }

    /** « Versions proposées »: the betas too, or the releases alone. */
    fun setUpdatePrereleases(betas: Boolean) = change("Saving the versions offered", SettingAction.UPDATE_CHANNEL) {
        store.setUpdatePrereleases(betas)
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

    /** Whether [folder] is the last of [folders] not already being removed: removing it leaves no folder. */
    fun isLast(folder: Folder, folders: List<Folder>): Boolean = folders.none { it.id != folder.id && it.id !in removing }

    /**
     * Removes [folder] and its titles in [work], waiting for a scan's open file; [removing] holds it meanwhile. The
     * [last] one gives way to the kept server: made the source first, without asking it, then [onResumed]; if it
     * cannot be, nothing is removed.
     */
    fun removeFolder(folder: Folder, last: Boolean = false, onResumed: () -> Unit = {}) {
        if (folder.id in removing) return
        removing += folder.id
        val kept = store.state.value.keptServer.takeIf { last }
        change("Removing the folder ${folder.id}", SettingAction.REMOVE_FOLDER, work, folder.id) {
            try {
                if (kept != null) {
                    sources.switchTo(kept)
                    onResumed()
                }
                library.removeFolder(folder)
            } finally {
                removing -= folder.id
            }
        }
    }

    /**
     * « Garder » (they wait for their files) and « Retirer » (they leave, what the user did kept by title) for a folder's
     * titles held, in [work]; a failure is said on the folder's showcase.
     */
    val heldAnswers = HeldAnswers(
        keep = { held -> answer(held, "Keeping") { library.keepHeld(held.folder.id, held.ids) } },
        remove = { held -> answer(held, "Removing") { library.removeHeld(held.ids) } },
    )

    private fun answer(held: HeldTitles, what: String, save: suspend () -> Unit) =
        change("$what the titles held in folder ${held.folder.id}", SettingAction.HELD, work, held.folder.id) { save() }

    private val _mappings = mutableStateListOf<PathMapping>().apply { addAll(store.state.value.pathMappings) }

    /** The path mappings as typed, incomplete lines included: they survive pane changes and scrolling. */
    val mappings: List<PathMapping> = _mappings

    private var mappingsEdited = false

    /** The stored mappings were not opened when this screen opened: [mappings] takes them in once they are. */
    private var mappingsAwaited = SecretSetting.PATH_MAPPINGS in store.state.value.unreadSecrets

    init {
        if (mappingsAwaited) viewModelScope.launch { store.state.first { mappingsReadable(it) } }
    }

    /** Whether [s] holds the stored mappings, then taken in if awaited; never while unread, which an edit would write over. */
    private fun mappingsReadable(s: AppSettings = store.state.value): Boolean {
        if (SecretSetting.PATH_MAPPINGS in s.unreadSecrets) return false
        if (mappingsAwaited) {
            mappingsAwaited = false
            _mappings.clear()
            _mappings.addAll(s.pathMappings)
        }
        return true
    }

    /**
     * Saves [mapping] at line [index], or as a new line when null, kept only once both paths are given: a line whose
     * paths are emptied goes. Nothing changes while the stored mappings are not opened.
     */
    fun putMapping(index: Int?, mapping: PathMapping) {
        if (!mappingsReadable()) return
        val incomplete = mapping.from.isBlank() || mapping.to.isBlank()
        when {
            index == null -> if (incomplete) return else _mappings += mapping
            _mappings.getOrNull(index) == mapping -> return
            incomplete -> _mappings.removeAt(index)
            else -> _mappings[index] = mapping
        }
        mappingsEdited = true
        saveMappings()
    }

    fun removeMapping(index: Int) {
        if (!mappingsReadable()) return
        _mappings.removeAt(index)
        mappingsEdited = true
        saveMappings()
    }

    /** Saves the complete lines as the sheet closes, not while typing, so a half-typed path never plays. */
    private fun saveMappings() {
        if (!mappingsEdited) return
        mappingsEdited = false
        val complete = completeMappings()
        change("Saving the path mappings", SettingAction.PATH_MAPPINGS) { store.setPathMappings(complete) }
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
    suspend fun samplePath(): String? = suspendRunCatching { provider()?.samplePath() }.getOrNull()

    private companion object {
        const val TAG = "Settings"
    }
}

/** The ViewModel the panes share; none outside [SettingsScreen], where a pane fails at once rather than make another. */
internal val LocalSettingsViewModel = staticCompositionLocalOf<SettingsViewModel> {
    error("A settings pane is composed outside SettingsScreen, which provides its ViewModel")
}

/** The screen's ViewModel, shared by all its panes. */
@Composable
internal fun settingsViewModel(): SettingsViewModel = LocalSettingsViewModel.current
